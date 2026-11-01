package com.dadot.server.domain.notification.service;

import com.dadot.server.domain.notification.model.NotificationSettings;
import com.dadot.server.domain.notification.model.NotificationSettings.Patch;
import com.dadot.server.domain.notification.model.NotificationSettings.Settings;
import com.dadot.server.domain.notification.repository.NotificationSettingsRepository;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class NotificationSettingsService {
    private static final Logger log = LoggerFactory.getLogger(NotificationSettingsService.class);
    private final NotificationSettingsRepository repository;
    private final UserAccess access;
    private final Clock clock;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public NotificationSettingsService(
            NotificationSettingsRepository repository, UserAccess access,
            Clock clock, PlatformTransactionManager manager
    ) {
        this.repository = repository;
        this.access = access;
        this.clock = clock;
        read = transaction(manager, true);
        write = transaction(manager, false);
    }

    public Settings get(SessionAuthenticationToken actor) {
        return storage("get", () -> Objects.requireNonNull(read.execute(tx -> {
            long user = access.require(actor, false);
            return repository.find(user).orElseGet(Settings::defaults);
        })));
    }

    public Settings patch(SessionAuthenticationToken actor, Patch patch) {
        Objects.requireNonNull(patch);
        return storage("patch", () -> Objects.requireNonNull(write.execute(tx -> {
            long user = access.require(actor, true);
            var stored = repository.find(user);
            Settings current = stored.orElseGet(Settings::defaults);
            if (patch.version() != current.version()) {
                throw new BusinessException(ErrorCode.VERSION_CONFLICT);
            }

            Settings proposed = patch.apply(current);
            if (proposed.equals(current)) return current;
            if (current.version() >= NotificationSettings.MAX_VERSION) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            }

            Settings next = new Settings(
                    proposed.enabled(), proposed.scheduledTime(), proposed.timeZone(),
                    current.version() + 1
            );
            repository.save(user, next, current.version(), stored.isPresent(), clock.instant());
            access.require(actor, true);
            return next;
        })));
    }

    private static TransactionTemplate transaction(PlatformTransactionManager manager, boolean readOnly) {
        var tx = new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(readOnly ? TransactionDefinition.ISOLATION_REPEATABLE_READ
                : TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setReadOnly(readOnly);
        tx.setTimeout(10);
        return tx;
    }

    // 원인 예외에는 SQL·개인정보가 포함될 수 있으므로 허용된 메타데이터만 기록한다.
    @SuppressWarnings("PMD.PreserveStackTrace")
    static <T> T storage(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (TransientDataAccessException | DataAccessResourceFailureException | TransactionException ex) {
            Throwable root = ex;
            SQLException sql = null;
            var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
            for (Throwable cause = ex; cause != null && visited.add(cause); cause = cause.getCause()) {
                root = cause;
                if (sql == null && cause instanceof SQLException value) sql = value;
            }
            String state = sql == null ? null : sql.getSQLState();
            if (state != null && !state.matches("[0-9A-Z]{5}")) state = null;
            log.warn("Notification settings storage unavailable: operation={}, exceptionClass={}, "
                            + "rootCauseClass={}, sqlState={}, dbErrorCode={}",
                    operation, ex.getClass().getName(), root.getClass().getName(),
                    state, sql == null ? null : sql.getErrorCode());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}