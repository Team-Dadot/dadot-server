package com.dadot.server.domain.disclosure.service;

import com.dadot.server.domain.disclosure.model.DisclosureSync.Lease;
import com.dadot.server.domain.disclosure.repository.DisclosureRepository;
import com.dadot.server.infrastructure.dart.DartClient;
import com.dadot.server.infrastructure.dart.DartFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

@Component
public class DisclosureWorker {
    private static final Logger log = LoggerFactory.getLogger(DisclosureWorker.class);

    private final DisclosureRepository repository;
    private final DartClient dart;
    private final Clock clock;
    private final boolean scheduled;
    private final TransactionTemplate transaction;

    public DisclosureWorker(
            DisclosureRepository repository,
            DartClient dart,
            Clock clock,
            PlatformTransactionManager manager,
            @Value("${app.dart.worker-enabled:true}") boolean scheduled
    ) {
        this.repository = repository;
        this.dart = dart;
        this.clock = clock;
        this.scheduled = scheduled;

        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(10);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Scheduled(fixedDelayString = "${app.dart.worker-delay-ms:1000}")
    public void poll() {
        if (!scheduled || !dart.ready()) return;

        try {
            runOnce();
        } catch (RuntimeException ex) {
            // 예외 원문에 JDBC/HTTP 요청이 포함될 수 있어 클래스명만 기록한다.
            // 작업 lease가 만료되면 복구된다.
            log.error(
                    "Disclosure worker storage failure type={}",
                    ex.getClass().getSimpleName()
            );
        }
    }

    @Scheduled(fixedDelayString = "${app.dart.cleanup-delay-ms:300000}")
    public void cleanup() {
        if (!scheduled) return;

        try {
            repository.clearExpiredResponses(clock.instant());
        } catch (RuntimeException ex) {
            log.error(
                    "Disclosure response cleanup failure type={}",
                    ex.getClass().getSimpleName()
            );
        }
    }

    public void runOnce() {
        Lease lease = transaction.execute(
                status -> repository.claim(clock.instant())
        );
        if (lease == null) return;

        try {
            var p = lease.parameters();
            if (p == null) throw new DartFailure("DART_INVALID_JOB", false);
            if (p.cursor().retries() > 2) throw new DartFailure("LEASE_EXPIRED", false);

            // HTTP 동안 DB 연결·행 잠금을 유지하지 않는다.
            var page = dart.list(
                    lease.corpCode(),
                    p.cursor().fromDate(),
                    p.cursor().end(p.request().toDate()),
                    p.cursor().page()
            );

            transaction.executeWithoutResult(
                    status -> repository.savePage(lease, page, clock.instant())
            );

        } catch (DartFailure ex) {
            transaction.executeWithoutResult(
                    status -> repository.fail(
                            lease, ex.code(), ex.retryable(), clock.instant()
                    )
            );

            log.warn(
                    "Disclosure collection task={} code={}",
                    lease.taskId(), ex.code()
            );
        }
    }
}