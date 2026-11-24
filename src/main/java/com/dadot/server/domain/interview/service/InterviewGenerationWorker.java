package com.dadot.server.domain.interview.service;

import com.dadot.server.domain.interview.model.InterviewGeneration.*;
import com.dadot.server.domain.interview.repository.InterviewGenerationRepository;
import com.dadot.server.domain.interview.support.InterviewGenerationProperties;
import com.dadot.server.domain.interview.support.InterviewGenerationCapacity;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.infrastructure.openai.OpenAiInterviewClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

@Component
public class InterviewGenerationWorker {
    private static final int MAX_ATTEMPTS = 3;
    private static final Logger log =
            LoggerFactory.getLogger(InterviewGenerationWorker.class);

    private final InterviewGenerationRepository repository;
    private final OpenAiInterviewClient client;
    private final InterviewGenerationProperties properties;
    private final Clock clock;
    private final TransactionTemplate write;
    private final TaskExecutor executor;
    private final Semaphore slots;

    public InterviewGenerationWorker(
            InterviewGenerationRepository repository,
            OpenAiInterviewClient client,
            InterviewGenerationProperties properties,
            Clock clock,
            PlatformTransactionManager manager,
            InterviewGenerationCapacity capacity,
            @Qualifier("interviewGenerationExecutor") TaskExecutor executor
    ) {
        this.repository = repository;
        this.client = client;
        this.properties = properties;
        this.clock = clock;
        this.executor = executor;
        this.slots = new Semaphore(capacity.maxConcurrent());

        write = new TransactionTemplate(manager);
        write.setTimeout(15);
        write.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED
        );
    }

    @Scheduled(
            scheduler = "interviewGenerationScheduler",
            fixedDelayString = "${app.interview-generation.worker-delay-ms:1000}"
    )
    public void poll() {
        if (!properties.workerEnabled() || !client.ready()) {
            return;
        }

        // 슬롯을 확보한 실행 스레드에서만 DB 작업을 선점한다.
        // 실행기가 포화·종료 상태이면 선점하지 않아 DB의 대기 작업과 시도 횟수를 보존한다.
        if (!slots.tryAcquire()) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    runOnce();
                } catch (RuntimeException ex) {
                    log.error("Interview generation storage failure type={}",
                            ex.getClass().getSimpleName());
                } finally {
                    slots.release();
                }
            });
        } catch (RejectedExecutionException ex) {
            slots.release();
            // 다음 tick에서 DB에 남아 있는 작업을 다시 확인한다.
            log.debug("Interview generation executor unavailable");
        }
    }

    public void runOnce() {
        if (!client.ready()) {
            return;
        }

        var claim = write.execute(
                tx -> repository.claim(clock.instant())
        );

        if (claim == null) {
            return;
        }

        Output output = null;

        try {
            Lease lease = write.execute(
                    tx -> repository.load(claim, clock.instant())
            );

            if (lease == null) {
                return;
            }

            // 외부 HTTP 호출은 DB 트랜잭션 밖에서 실행한다.
            output = client.generate(lease);
            Output completed = output;

            write.executeWithoutResult(
                    tx -> repository.save(
                            claim, completed, clock.instant()
                    )
            );
        } catch (Failure ex) {
            // 생성 결과를 받은 뒤 저장소에서 발생하는 일반 검증 오류는 질문 중복이다.
            String reason = output != null
                    && "INVALID_OUTPUT".equals(ex.reason())
                    ? "QUESTION_DUPLICATE"
                    : ex.reason();

            boolean retry = claim.attempt() < MAX_ATTEMPTS
                    && (
                    ex.retryable()
                            || "QUESTION_TOO_LONG".equals(reason)
                            || "QUESTION_INCOMPLETE".equals(reason)
                            || reason.startsWith("QUESTION_POLICY_")
                            || reason.startsWith("EVIDENCE_INTERPRETATION_")
            );

            Failure failure = new Failure(
                    ex.code(),
                    retry,
                    ex.retryAfter(),
                    ex.metadata() != null || output == null
                            ? ex.metadata()
                            : output.metadata(),
                    reason
            );

            write.executeWithoutResult(
                    tx -> repository.fail(
                            claim, failure, clock.instant()
                    )
            );

            log.warn(
                    "Interview generation task={} attempt={} code={} reason={} retry={}",
                    claim.taskId(),
                    claim.attempt(),
                    failure.code(),
                    failure.reason(),
                    failure.retryable()
            );
        } catch (BusinessException ex) {
            var failure = new Failure(
                    "CONTEXT_UNAVAILABLE",
                    false,
                    0,
                    output == null ? null : output.metadata()
            );

            write.executeWithoutResult(
                    tx -> repository.fail(
                            claim, failure, clock.instant()
                    )
            );
        }
    }
}