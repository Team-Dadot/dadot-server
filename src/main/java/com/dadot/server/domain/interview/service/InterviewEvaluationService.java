package com.dadot.server.domain.interview.service;

import com.dadot.server.domain.interview.model.InterviewEvaluation.View;
import com.dadot.server.domain.interview.model.InterviewGeneration.Accepted;
import com.dadot.server.domain.interview.model.InterviewGeneration.Failure;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.interview.repository.*;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.SessionAuthenticationToken;
import com.dadot.server.global.security.UserAccess;
import com.dadot.server.infrastructure.openai.OpenAiEvaluationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class InterviewEvaluationService {
    private static final Logger log = LoggerFactory.getLogger(InterviewEvaluationService.class);
    private final InterviewSubmissionRepository submissions;
    private final InterviewEvaluationResultRepository results;
    private final InterviewEvaluationInputRepository inputs;
    private final InterviewEvaluationJobRepository jobs;
    private final InterviewEvaluationRequestStore requests;
    private final OpenAiEvaluationClient client;
    private final UserAccess access;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate read, write;

    public InterviewEvaluationService(InterviewSubmissionRepository submissions, InterviewEvaluationResultRepository results,
                                      InterviewEvaluationInputRepository inputs, InterviewEvaluationJobRepository jobs,
                                      InterviewEvaluationRequestStore requests, OpenAiEvaluationClient client, UserAccess access,
                                      Clock clock, JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.submissions = submissions; this.results = results; this.inputs = inputs;
        this.jobs = jobs; this.requests = requests; this.client = client;
        this.access = access; this.clock = clock; this.jdbc = jdbc;
        read = transaction(manager, true); write = transaction(manager, false);
    }

    public View view(SessionAuthenticationToken actor, long attempt) {
        InterviewPolicy.id(attempt);
        return storage("view", () -> Objects.requireNonNull(read.execute(tx -> {
            long user = access.require(actor, false);
            return results.view(user, submissions.attempt(user, attempt), clock.instant());
        })));
    }

    public Accepted retry(SessionAuthenticationToken actor, long attempt, String value) {
        InterviewPolicy.id(attempt);
        return storage("retry", () -> Objects.requireNonNull(write.execute(tx -> {
            long user = access.require(actor, true);
            var saved = submissions.attempt(user, attempt);
            String task = saved.evaluationTaskId();
            var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            var key = requests.key(user, attempt, value);
            Accepted replay = requests.replay(user, task, key, now);
            if (replay != null) return replay;
            if ("SUCCEEDED".equals(saved.evaluationStatus())) throw new BusinessException(ErrorCode.EVALUATION_ALREADY_SUCCEEDED);
            if ("CANCELLED".equals(saved.evaluationStatus())) throw new BusinessException(ErrorCode.CONTEXT_UNAVAILABLE);
            try { inputs.prepare(task, now); }
            catch (Failure ex) { throw new BusinessException(ErrorCode.CONTEXT_UNAVAILABLE); }
            String status = jdbc.queryForObject("select status from ai_tasks where id = ? for update", String.class, task);
            if ("FAILED".equals(status)) {
                if (!client.ready()) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
                if (requests.rateLimited(user, now)) throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
                jobs.requeue(task, now);
            } else if (!"PROCESSING".equals(status)) {
                throw new BusinessException(ErrorCode.CONTEXT_UNAVAILABLE);
            }
            Accepted accepted = new Accepted(task, "PROCESSING");
            requests.complete(user, key, accepted, now);
            access.require(actor, true);
            return accepted;
        })));
    }

    private static TransactionTemplate transaction(PlatformTransactionManager manager, boolean readOnly) {
        var tx = new TransactionTemplate(manager);
        tx.setReadOnly(readOnly); tx.setTimeout(15);
        tx.setIsolationLevel(readOnly ? TransactionDefinition.ISOLATION_REPEATABLE_READ : TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    // 원문이 포함될 수 있는 cause 대신 검증된 진단 메타데이터만 기록한다.
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
            log.warn("Interview evaluation unavailable: operation={}, exceptionClass={}, rootCauseClass={}, sqlState={}, dbErrorCode={}",
                    operation, ex.getClass().getName(), root.getClass().getName(), state, sql == null ? null : sql.getErrorCode());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}