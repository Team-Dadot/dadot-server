package com.dadot.server.domain.interview.service;

import com.dadot.server.domain.interview.model.InterviewGeneration.Attempt;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.interview.model.InterviewReview.*;
import com.dadot.server.domain.interview.repository.InterviewReadRepository;
import com.dadot.server.domain.interview.repository.InterviewReviewRepository;
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
public class InterviewReviewService {
    private static final Logger log = LoggerFactory.getLogger(InterviewReviewService.class);
    private final InterviewReviewRepository repository;
    private final InterviewReadRepository reads;
    private final UserAccess access;
    private final Clock clock;
    private final TransactionTemplate read, write;

    public InterviewReviewService(InterviewReviewRepository repository, InterviewReadRepository reads,
                                  UserAccess access, Clock clock, PlatformTransactionManager manager) {
        this.repository = repository; this.reads = reads; this.access = access; this.clock = clock;
        read = transaction(manager, true); write = transaction(manager, false);
    }

    public Page<Question> questions(SessionAuthenticationToken actor, long company, Paging paging) {
        InterviewPolicy.id(company);
        if (paging == null) throw InterviewPolicy.invalid();
        return storage("questions", () -> Objects.requireNonNull(read.execute(tx -> {
            long user = access.require(actor, false);
            repository.requireCompany(user, company);
            return repository.questions(user, company, paging);
        })));
    }

    public Page<Attempt> attempts(SessionAuthenticationToken actor, long question, Paging paging) {
        InterviewPolicy.id(question);
        if (paging == null) throw InterviewPolicy.invalid();
        return storage("attempts", () -> Objects.requireNonNull(read.execute(tx ->
                repository.attempts(access.require(actor, false), question, paging))));
    }

    public void deleteQuestion(SessionAuthenticationToken actor, long question) {
        InterviewPolicy.id(question);
        storage("deleteQuestion", () -> write.execute(tx -> {
            long user = access.require(actor, true);
            long company = repository.questionCompany(user, question, false, true);
            reads.preparation(user, company, true);
            repository.questionCompany(user, question, true, true);
            repository.deleteQuestion(user, question, clock.instant());
            access.require(actor, true);
            return Boolean.TRUE;
        }));
    }

    public void deleteAttempt(SessionAuthenticationToken actor, long attempt) {
        InterviewPolicy.id(attempt);
        storage("deleteAttempt", () -> write.execute(tx -> {
            long user = access.require(actor, true);
            long question = repository.questionForAttempt(user, attempt);
            long company = repository.questionCompany(user, question, false, true);
            reads.preparation(user, company, true);
            repository.questionCompany(user, question, true, true);
            repository.deleteAttempt(user, question, attempt, clock.instant());
            access.require(actor, true);
            return Boolean.TRUE;
        }));
    }

    private static TransactionTemplate transaction(PlatformTransactionManager manager, boolean readOnly) {
        var tx = new TransactionTemplate(manager);
        tx.setReadOnly(readOnly); tx.setTimeout(15);
        tx.setIsolationLevel(readOnly ? TransactionDefinition.ISOLATION_REPEATABLE_READ : TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    // SQL·개인 원문이 포함될 수 있는 원인 대신 검증한 메타데이터만 기록한다.
    @SuppressWarnings("PMD.PreserveStackTrace")
    private static <T> T storage(String operation, Supplier<T> action) {
        try { return action.get(); }
        catch (TransientDataAccessException | DataAccessResourceFailureException | TransactionException ex) {
            Throwable root = ex; SQLException sql = null;
            var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
            for (Throwable cause = ex; cause != null && visited.add(cause); cause = cause.getCause()) {
                root = cause;
                if (sql == null && cause instanceof SQLException value) sql = value;
            }
            String state = sql == null ? null : sql.getSQLState();
            if (state != null && !state.matches("[0-9A-Z]{5}")) state = null;
            log.warn("Interview review unavailable: operation={}, exceptionClass={}, rootCauseClass={}, sqlState={}, dbErrorCode={}",
                    operation, ex.getClass().getName(), root.getClass().getName(), state, sql == null ? null : sql.getErrorCode());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}