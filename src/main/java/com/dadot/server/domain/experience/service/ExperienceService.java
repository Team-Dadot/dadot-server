package com.dadot.server.domain.experience.service;

import com.dadot.server.domain.experience.model.Experience;
import com.dadot.server.domain.experience.model.Experience.*;
import com.dadot.server.domain.experience.repository.ExperienceRepository;
import com.dadot.server.domain.experience.repository.ExperienceRequestStore;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.response.PageResponse;
import com.dadot.server.global.security.SessionAuthenticationToken;
import com.dadot.server.global.security.UserAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class ExperienceService {

    private static final Logger log = LoggerFactory.getLogger(ExperienceService.class);
    private final ExperienceRepository repository;
    private final ExperienceRequestStore requests;
    private final UserAccess access;
    private final Clock clock;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public ExperienceService(ExperienceRepository repository, ExperienceRequestStore requests,
                             UserAccess access, Clock clock, PlatformTransactionManager manager) {
        this.repository = repository;
        this.requests = requests;
        this.access = access;
        this.clock = clock;
        read = transaction(manager, true);
        write = transaction(manager, false);
    }

    public PageResponse<Summary> list(SessionAuthenticationToken actor, Paging paging) {
        if (paging == null) throw Experience.invalid();
        return storage("list", () -> Objects.requireNonNull(read.execute(tx -> {
            long user = access.require(actor, false);
            long total = repository.count(user);
            return new PageResponse<>(repository.list(user, paging), paging.page(), paging.size(),
                    total, (int) ((total + paging.size() - 1) / paging.size()),
                    paging.offset() + paging.size() < total);
        })));
    }

    public Detail detail(SessionAuthenticationToken actor, long id) {
        Experience.id(id);
        return storage("detail", () -> Objects.requireNonNull(read.execute(tx ->
                repository.current(access.require(actor, false), id, false))));
    }

    public Detail create(SessionAuthenticationToken actor, String key, Create request) {
        if (request == null) throw Experience.invalid();
        return storage("create", () -> Objects.requireNonNull(write.execute(tx -> {
            long user = access.require(actor, true);
            var requestKey = requests.key(user, key, request);
            var replay = requests.replay(user, requestKey);
            if (replay.isPresent()) {
                var reference = replay.get();
                Detail result = repository.snapshot(user, reference.experienceId(), reference.version());
                access.require(actor, true);
                return result;
            }
            if (repository.count(user) >= Experience.LIMIT) {
                throw new BusinessException(ErrorCode.EXPERIENCE_LIMIT_EXCEEDED);
            }
            Instant now = now();
            long id = repository.create(user, request, now);
            repository.advancePersonalization(user, now);
            requests.complete(user, requestKey, id, now);
            Detail result = repository.current(user, id, false);
            access.require(actor, true);
            return result;
        })));
    }

    public Detail update(SessionAuthenticationToken actor, long id, Patch request) {
        Experience.id(id);
        if (request == null) throw Experience.invalid();
        return storage("update", () -> Objects.requireNonNull(write.execute(tx -> {
            long user = access.require(actor, true);
            Detail previous = repository.current(user, id, true);
            if (previous.version() != request.version()) {
                throw new BusinessException(ErrorCode.VERSION_CONFLICT);
            }
            Instant now = now();
            repository.update(user, previous, request, now);
            repository.advancePersonalization(user, now);
            Detail result = repository.current(user, id, false);
            access.require(actor, true);
            return result;
        })));
    }

    public void delete(SessionAuthenticationToken actor, long id) {
        Experience.id(id);
        storage("delete", () -> write.execute(tx -> {
            long user = access.require(actor, true);
            repository.current(user, id, true);
            repository.delete(user, id);
            repository.advancePersonalization(user, now());
            access.require(actor, true);
            return null;
        }));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static TransactionTemplate transaction(PlatformTransactionManager manager, boolean readOnly) {
        var template = new TransactionTemplate(manager);
        template.setReadOnly(readOnly);
        template.setTimeout(10);
        template.setIsolationLevel(readOnly ? TransactionDefinition.ISOLATION_REPEATABLE_READ
                : TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    private <T> T storage(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (TransientDataAccessException | DataAccessResourceFailureException
                 | TransactionException ex) {
            Throwable root = ex;
            SQLException sql = null;
            var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
            for (Throwable cause = ex; cause != null && visited.add(cause); cause = cause.getCause()) {
                root = cause;
                if (sql == null && cause instanceof SQLException found) sql = found;
            }
            String state = sql == null ? null : sql.getSQLState();
            if (state != null && !state.matches("[0-9A-Z]{5}")) state = null;
            log.warn("Experience unavailable: operation={}, exceptionClass={}, rootCauseClass={}, "
                            + "sqlState={}, dbErrorCode={}", operation, ex.getClass().getName(),
                    root.getClass().getName(), state, sql == null ? null : sql.getErrorCode());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}