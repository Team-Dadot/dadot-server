package com.dadot.server.domain.interview.service;

import com.dadot.server.domain.interview.model.InterviewGeneration.*;
import com.dadot.server.domain.interview.repository.InterviewGenerationRepository;
import com.dadot.server.domain.interview.support.InterviewGenerationProperties;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.infrastructure.openai.OpenAiInterviewClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

@Component
public class InterviewGenerationWorker {
    private static final Logger log =
            LoggerFactory.getLogger(InterviewGenerationWorker.class);

    private final InterviewGenerationRepository repository;
    private final OpenAiInterviewClient client;
    private final InterviewGenerationProperties properties;
    private final Clock clock;
    private final TransactionTemplate write;

    public InterviewGenerationWorker(
            InterviewGenerationRepository repository,
            OpenAiInterviewClient client,
            InterviewGenerationProperties properties,
            Clock clock,
            PlatformTransactionManager manager
    ) {
        this.repository = repository;
        this.client = client;
        this.properties = properties;
        this.clock = clock;

        write = new TransactionTemplate(manager);
        write.setTimeout(15);
        write.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED
        );
    }

    @Scheduled(
            fixedDelayString = "${app.interview-generation.worker-delay-ms:1000}"
    )
    public void poll() {
        if (!properties.workerEnabled() || !client.ready()) return;

        try {
            runOnce();
        } catch (RuntimeException ex) {
            log.error(
                    "Interview generation storage failure type={}",
                    ex.getClass().getSimpleName()
            );
        }
    }

    public void runOnce() {
        if (!client.ready()) return;

        var claim = write.execute(
                tx -> repository.claim(clock.instant())
        );

        if (claim == null) return;
        Output output = null;

        try {
            Lease lease = write.execute(
                    tx -> repository.load(claim, clock.instant())
            );

            if (lease == null) return;

            // 외부 HTTP 호출은 DB 트랜잭션 밖에서 실행한다.
            output = client.generate(lease);
            Output completed = output;

            write.executeWithoutResult(
                    tx -> repository.save(
                            claim, completed, clock.instant()
                    )
            );
        } catch (Failure ex) {
            Failure failure = ex.metadata() != null || output == null
                    ? ex
                    : new Failure(
                    ex.code(), ex.retryable(),
                    ex.retryAfter(), output.metadata()
            );

            write.executeWithoutResult(
                    tx -> repository.fail(
                            claim, failure, clock.instant()
                    )
            );

            log.warn(
                    "Interview generation task={} code={}",
                    claim.taskId(), failure.code()
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