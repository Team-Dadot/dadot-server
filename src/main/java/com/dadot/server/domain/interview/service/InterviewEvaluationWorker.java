package com.dadot.server.domain.interview.service;

import com.dadot.server.domain.interview.model.InterviewEvaluation.*;
import com.dadot.server.domain.interview.model.InterviewGeneration.Failure;
import com.dadot.server.domain.interview.repository.InterviewEvaluationInputRepository;
import com.dadot.server.domain.interview.repository.InterviewEvaluationJobRepository;
import com.dadot.server.domain.interview.repository.InterviewEvaluationResultRepository;
import com.dadot.server.domain.interview.support.InterviewEvaluationProperties;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.infrastructure.openai.OpenAiEvaluationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

@Component
public class InterviewEvaluationWorker {
    private static final Logger log = LoggerFactory.getLogger(InterviewEvaluationWorker.class);
    private final InterviewEvaluationInputRepository inputs;
    private final InterviewEvaluationJobRepository jobs;
    private final InterviewEvaluationResultRepository results;
    private final OpenAiEvaluationClient client;
    private final InterviewEvaluationProperties properties;
    private final Clock clock;
    private final TransactionTemplate write;

    public InterviewEvaluationWorker(InterviewEvaluationInputRepository inputs, InterviewEvaluationJobRepository jobs,
                                     InterviewEvaluationResultRepository results, OpenAiEvaluationClient client,
                                     InterviewEvaluationProperties properties, Clock clock, PlatformTransactionManager manager) {
        this.inputs = inputs; this.jobs = jobs; this.results = results; this.client = client;
        this.properties = properties; this.clock = clock;
        write = new TransactionTemplate(manager); write.setTimeout(15);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Scheduled(fixedDelayString = "${app.interview-evaluation.worker-delay-ms:1000}")
    public void poll() {
        if (!properties.workerEnabled() || !client.ready()) return;
        try { runOnce(); }
        catch (RuntimeException ex) { log.error("Interview evaluation storage failure type={}", ex.getClass().getName()); }
    }

    public void runOnce() {
        if (!client.ready()) return;
        write.executeWithoutResult(tx -> inputs.prepareNext(clock.instant()));
        Claim claim = write.execute(tx -> jobs.claim(clock.instant()));
        if (claim == null) return;
        Output output = null;
        try {
            Lease lease = write.execute(tx -> jobs.load(claim, clock.instant()));
            if (lease == null) return;
            // 외부 HTTP 동안 DB 트랜잭션과 사용자 잠금을 유지하지 않는다.
            output = client.evaluate(lease);
            Output completed = output;
            write.executeWithoutResult(tx -> results.save(claim, completed, clock.instant()));
        } catch (Failure ex) {
            Failure failure = ex.metadata() != null || output == null ? ex
                    : new Failure(ex.code(), ex.retryable(), ex.retryAfter(), output.metadata());
            write.executeWithoutResult(tx -> jobs.fail(claim, failure, clock.instant()));
            log.warn("Interview evaluation task={} code={}", claim.taskId(), failure.code());
        } catch (BusinessException ex) {
            Failure failure = new Failure("CONTEXT_UNAVAILABLE", false, 0, output == null ? null : output.metadata());
            write.executeWithoutResult(tx -> jobs.fail(claim, failure, clock.instant()));
        }
    }
}