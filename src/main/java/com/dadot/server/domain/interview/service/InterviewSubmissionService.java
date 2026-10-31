package com.dadot.server.domain.interview.service;

import com.dadot.server.domain.interview.model.InterviewGeneration.Attempt;
import com.dadot.server.domain.interview.model.InterviewGeneration.Draft;
import com.dadot.server.domain.interview.model.InterviewGeneration.Usage;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.interview.model.InterviewSubmission.*;
import com.dadot.server.domain.interview.repository.InterviewReadRepository;
import com.dadot.server.domain.interview.repository.InterviewSubmissionRepository;
import com.dadot.server.domain.interview.repository.InterviewSubmissionRepository.Slots;
import com.dadot.server.domain.interview.repository.InterviewSubmissionRequestStore;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.SessionAuthenticationToken;
import com.dadot.server.global.security.UserAccess;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class InterviewSubmissionService {
    private final InterviewSubmissionRepository repository;
    private final InterviewSubmissionRequestStore requests;
    private final InterviewReadRepository reads;
    private final UserAccess access;
    private final Clock clock;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public InterviewSubmissionService(InterviewSubmissionRepository repository,
                                      InterviewSubmissionRequestStore requests, InterviewReadRepository reads,
                                      UserAccess access, Clock clock, PlatformTransactionManager manager) {
        this.repository = repository;
        this.requests = requests;
        this.reads = reads;
        this.access = access;
        this.clock = clock;
        read = transaction(manager, true);
        write = transaction(manager, false);
    }

    public Saved save(SessionAuthenticationToken actor, long questionId, SaveDraft input) {
        InterviewPolicy.id(questionId);
        return storage(() -> Objects.requireNonNull(write.execute(tx -> {
            long user = access.require(actor, true);
            Question question = repository.question(user, questionId);
            var prep = reads.preparation(user, question.companyId(), true);
            long counter = repository.lockQuestion(user, questionId);
            var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            if (!prep.active()) throw new BusinessException(ErrorCode.COMPANY_INACTIVE);
            if (reads.usage(user, question.companyId(), questionId, true, now).questionUsed() >= 3) {
                throw new BusinessException(ErrorCode.QUESTION_LIMIT_EXCEEDED);
            }
            checkVersion(repository.draft(questionId), input.version());
            Draft draft = repository.saveDraft(user, question, input.text(), counter, now);
            access.require(actor, true);
            return new Saved(questionId, draft);
        })));
    }

    public Accepted submit(SessionAuthenticationToken actor, long questionId, String key, Submit input) {
        InterviewPolicy.id(questionId);
        return storage(() -> Objects.requireNonNull(write.execute(tx -> {
            long user = access.require(actor, true);
            Question question = repository.question(user, questionId);
            var prep = reads.preparation(user, question.companyId(), true);
            repository.lockQuestion(user, questionId);
            var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            var request = requests.key(user, questionId, key, input);
            Accepted replay = requests.replay(user, request, now);
            if (replay != null) {
                Attempt existing = repository.attempt(user, replay.attemptId());
                if (existing.questionId() != questionId || !existing.evaluationTaskId().equals(replay.taskId())) {
                    throw new IllegalStateException("Invalid interview submission replay resource");
                }
                return replay;
            }

            if (input.draftVersion() != null) {
                checkVersion(repository.draft(questionId), input.draftVersion());
            }
            Usage before = reads.usage(user, question.companyId(), questionId, prep.active(), now);
            if (!before.canSubmit()) throw new BusinessException(blocked(before.blockedReason()));

            long policy = repository.evaluationPolicy(question);
            String context = repository.snapshot(user, question, now);
            var slots = new Slots(before.questionIncludedToday(), before.daily().questionUsed() + 1,
                    before.daily().used() + 1, before.questionUsed() + 1);
            long attempt = repository.submit(user, question, request, input.text(), context, policy, slots, now);
            String task = repository.enqueue(user, question.companyId(), attempt, context, policy, request, now);
            repository.clearDraft(questionId, input.draftVersion());

            Accepted result = new Accepted(task, "PROCESSING", attempt, slots.attemptNumber(), now,
                    reads.usage(user, question.companyId(), questionId, prep.active(), now));
            requests.complete(user, request, result, now);
            access.require(actor, true);
            return result;
        })));
    }

    public Attempt detail(SessionAuthenticationToken actor, long attemptId) {
        InterviewPolicy.id(attemptId);
        return storage(() -> Objects.requireNonNull(read.execute(tx ->
                repository.attempt(access.require(actor, false), attemptId))));
    }

    private static void checkVersion(Draft draft, Long expected) {
        if (draft == null ? expected != null : !Objects.equals(expected, draft.version())) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
    }

    private static ErrorCode blocked(String reason) {
        return switch (reason) {
            case "DAILY_LIMIT_EXCEEDED" -> ErrorCode.DAILY_LIMIT_EXCEEDED;
            case "QUESTION_LIMIT_EXCEEDED" -> ErrorCode.QUESTION_LIMIT_EXCEEDED;
            case "COMPANY_INACTIVE" -> ErrorCode.COMPANY_INACTIVE;
            case "CONTENT_UNAVAILABLE" -> ErrorCode.CONTENT_UNAVAILABLE;
            case "TASK_PROCESSING" -> ErrorCode.TASK_PROCESSING;
            default -> throw new IllegalStateException("Unknown interview submission block");
        };
    }

    private static TransactionTemplate transaction(PlatformTransactionManager manager, boolean readOnly) {
        var tx = new TransactionTemplate(manager);
        tx.setReadOnly(readOnly);
        tx.setTimeout(15);
        tx.setIsolationLevel(readOnly ? TransactionDefinition.ISOLATION_REPEATABLE_READ
                : TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx;
    }

    private static <T> T storage(Supplier<T> action) {
        try {
            return action.get();
        } catch (TransientDataAccessException | DataAccessResourceFailureException | TransactionException ex) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}