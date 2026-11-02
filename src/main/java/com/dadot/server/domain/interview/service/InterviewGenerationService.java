package com.dadot.server.domain.interview.service;

import com.dadot.server.domain.interview.model.InterviewGeneration.*;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.interview.repository.InterviewGenerationRepository;
import com.dadot.server.domain.interview.repository.InterviewReadRepository;
import com.dadot.server.domain.interview.repository.InterviewSourceRepository;
import com.dadot.server.domain.interview.support.InterviewGenerationProperties;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.SessionAuthenticationToken;
import com.dadot.server.global.security.UserAccess;
import com.dadot.server.infrastructure.openai.OpenAiInterviewClient;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class InterviewGenerationService {
    private final InterviewGenerationRepository repository;
    private final InterviewReadRepository reads;
    private final InterviewSourceRepository sources;
    private final OpenAiInterviewClient client;
    private final InterviewGenerationProperties properties;
    private final UserAccess access;
    private final Clock clock;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public InterviewGenerationService(
            InterviewGenerationRepository repository,
            InterviewReadRepository reads,
            InterviewSourceRepository sources,
            OpenAiInterviewClient client,
            InterviewGenerationProperties properties,
            UserAccess access,
            Clock clock,
            PlatformTransactionManager manager
    ) {
        this.repository = repository;
        this.reads = reads;
        this.sources = sources;
        this.client = client;
        this.properties = properties;
        this.access = access;
        this.clock = clock;
        read = transaction(manager, true);
        write = transaction(manager, false);
    }

    public Accepted create(
            SessionAuthenticationToken actor,
            long companyId,
            String idempotencyKey
    ) {
        InterviewPolicy.id(companyId);

        return storage(() -> Objects.requireNonNull(write.execute(tx -> {
            long user = access.require(actor, true);
            var now = clock.instant();
            var key = repository.key(user, companyId, idempotencyKey);
            Preparation prep = reads.preparation(user, companyId, true);

            String replay = repository.replay(key, user, companyId, now);
            if (replay != null) return new Accepted(replay, "PROCESSING");

            if (!prep.active()) {
                throw new BusinessException(ErrorCode.FORBIDDEN);
            }

            Daily daily = reads.daily(user, companyId, now);

            if (daily.remaining() == 0 || daily.questionRemaining() == 0) {
                throw new BusinessException(ErrorCode.DAILY_LIMIT_EXCEEDED);
            }

            if (reads.reusable(user, companyId, true, false, now) != null
                    || reads.reusable(user, companyId, false, true, now) != null) {
                throw new BusinessException(
                        ErrorCode.EXISTING_QUESTION_AVAILABLE
                );
            }

            String pending = reads.processingTask(user, companyId);

            if (pending != null) {
                repository.complete(key, user, pending, now);
                return new Accepted(pending, "PROCESSING");
            }

            if (!client.ready()) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            }

            Policy policy = repository.policy(prep);
            var materials = sources.materials(user, null);
            Plan plan = sources.plan(companyId, prep.jobName(), materials);

            var input = new Input(
                    prep.companyName(),
                    new Job(
                            prep.jobId(), prep.jobName(), prep.jobId() == null
                    ),
                    plan.sources(),
                    plan.sections(),
                    materials,
                    repository.previous(user, companyId)
            );

            try {
                client.encode(properties.model(), policy.rules(), input);
            } catch (Failure ex) {
                throw new BusinessException(
                        ErrorCode.INTERVIEW_INPUT_TOO_LARGE
                );
            }

            repository.admission(user, now);

            String task = repository.create(
                    prep, policy, plan, materials, key,
                    properties.model(), client.policyHash(policy.rules()), now
            );

            repository.complete(key, user, task, now);
            access.require(actor, true);

            return new Accepted(task, "PROCESSING");
        })));
    }

    public Entry entry(
            SessionAuthenticationToken actor,
            long companyId,
            String taskId
    ) {
        InterviewPolicy.id(companyId);

        return storage(() -> Objects.requireNonNull(read.execute(tx -> {
            long user = access.require(actor, false);
            var now = clock.instant();
            Preparation prep = reads.preparation(user, companyId, false);
            Daily daily = reads.daily(user, companyId, now);

            String pending = taskId == null
                    ? reads.processingTask(user, companyId)
                    : taskId;

            Task task = pending == null
                    ? null : reads.task(user, companyId, pending);

            if (!prep.active() || daily.remaining() == 0) {
                return new Entry(companyId, null, "REVIEW", task, daily);
            }

            Long question = reads.reusable(
                    user, companyId, true, false, now
            );

            if (question != null) {
                return new Entry(
                        companyId, question, "RESUME_DRAFT", task, daily
                );
            }

            question = reads.reusable(
                    user, companyId, false, true, now
            );

            if (question == null) {
                question = reads.reusable(
                        user, companyId, false, false, now
                );
            }

            if (question != null) {
                return new Entry(
                        companyId, question, "ANSWER_QUESTION", task, daily
                );
            }

            String next = daily.questionRemaining() == 0
                    ? "REVIEW"
                    : task != null && "PROCESSING".equals(task.status())
                    ? "WAIT_GENERATION"
                    : "GENERATE";

            return new Entry(companyId, null, next, task, daily);
        })));
    }

    public Detail detail(
            SessionAuthenticationToken actor,
            long questionId
    ) {
        InterviewPolicy.id(questionId);

        return storage(() -> Objects.requireNonNull(read.execute(tx ->
                reads.detail(
                        access.require(actor, false),
                        questionId,
                        clock.instant()
                )
        )));
    }

    private static TransactionTemplate transaction(
            PlatformTransactionManager manager,
            boolean readOnly
    ) {
        var tx = new TransactionTemplate(manager);
        tx.setReadOnly(readOnly);
        tx.setTimeout(15);
        tx.setIsolationLevel(
                readOnly
                        ? TransactionDefinition.ISOLATION_REPEATABLE_READ
                        : TransactionDefinition.ISOLATION_READ_COMMITTED
        );
        return tx;
    }

    private <T> T storage(Supplier<T> action) {
        try {
            return action.get();
        } catch (TransientDataAccessException
                 | DataAccessResourceFailureException
                 | TransactionException ex) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}