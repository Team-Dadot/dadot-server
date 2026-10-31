package com.dadot.server.domain.quiz.service;

import com.dadot.server.domain.quiz.model.QuizGeneration;
import com.dadot.server.domain.quiz.model.QuizGeneration.Failure;
import com.dadot.server.domain.quiz.model.QuizGeneration.Output;
import com.dadot.server.domain.quiz.repository.QuizGenerationRepository;
import com.dadot.server.domain.quiz.support.QuizGenerationProperties;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.infrastructure.openai.OpenAiQuizClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

@Component
public class QuizGenerationWorker {
    private static final Logger log = LoggerFactory.getLogger(QuizGenerationWorker.class);
    private final QuizGenerationRepository repository;
    private final OpenAiQuizClient client;
    private final QuizGenerationProperties properties;
    private final Clock clock;
    private final TransactionTemplate write;

    public QuizGenerationWorker(QuizGenerationRepository repository, OpenAiQuizClient client,
                                QuizGenerationProperties properties, Clock clock,
                                PlatformTransactionManager manager) {
        this.repository = repository;
        this.client = client;
        this.properties = properties;
        this.clock = clock;
        write = new TransactionTemplate(manager);
        write.setTimeout(20);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Scheduled(fixedDelayString = "${app.quiz-generation.worker-delay-ms:1000}")
    public void poll() {
        if (!properties.workerEnabled() || !client.ready()) return;
        try { runOnce(); }
        catch (RuntimeException ex) {
            log.error("Quiz generation storage failure type={}", ex.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${app.quiz-generation.cleanup-delay-ms:300000}")
    public void cleanup() {
        if (!properties.workerEnabled()) return;
        try { write.executeWithoutResult(tx -> repository.cleanup(clock.instant())); }
        catch (RuntimeException ex) {
            log.error("Quiz generation cleanup failure type={}", ex.getClass().getSimpleName());
        }
    }

    public void runOnce() {
        if (!client.ready()) return;
        var lease = write.execute(tx -> repository.claim(clock.instant()));
        if (lease == null) return;
        Output output = null;
        try {
            if (!Boolean.TRUE.equals(write.execute(tx -> repository.validateBeforeCall(lease, clock.instant())))) return;
            // 외부 HTTP는 DB 트랜잭션 밖에서 실행한다.
            output = client.generate(lease);
            var checked = QuizGeneration.validate(output.questions(), lease.companyId(), lease.input(), lease.questionsPerBatch());
            var metadata = output.metadata();
            write.executeWithoutResult(tx -> repository.save(lease, checked, metadata, clock.instant()));
        } catch (Failure ex) {
            var failure = ex.metadata() != null || output == null ? ex
                    : new Failure(ex.code(), ex.retryable(), ex.retryAfterSeconds(), output.metadata());
            write.executeWithoutResult(tx -> repository.fail(lease, failure, clock.instant()));
            log.warn("Quiz generation task={} code={}", lease.taskId(), failure.code());
        } catch (BusinessException ex) {
            var failure = new Failure("SOURCE_UNAVAILABLE", false, 0, output == null ? null : output.metadata());
            write.executeWithoutResult(tx -> repository.fail(lease, failure, clock.instant()));
        }
    }
}