package com.dadot.server.domain.disclosure.service;

import com.dadot.server.domain.disclosure.model.DisclosureSync.Lease;
import com.dadot.server.domain.disclosure.repository.DisclosureOriginalRepository;
import com.dadot.server.domain.disclosure.repository.DisclosureRepository;
import com.dadot.server.domain.disclosure.support.OriginalStore;
import com.dadot.server.infrastructure.dart.DartArchiveExtractor;
import com.dadot.server.infrastructure.dart.DartClient;
import com.dadot.server.infrastructure.dart.DartDocumentClient;
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

    private static final Logger log =
            LoggerFactory.getLogger(DisclosureWorker.class);

    private final DisclosureRepository repository;
    private final DartClient dart;
    private final Clock clock;
    private final boolean scheduled;

    private final TransactionTemplate transaction;
    private final TransactionTemplate originalTransaction;

    private final DisclosureOriginalRepository originals;
    private final OriginalStore store;
    private final DartDocumentClient documents;
    private final DartArchiveExtractor extractor;

    public DisclosureWorker(
            DisclosureRepository repository,
            DartClient dart,
            Clock clock,
            PlatformTransactionManager manager,
            @Value("${app.dart.worker-enabled:true}")
            boolean scheduled,
            DisclosureOriginalRepository originals,
            OriginalStore store,
            DartDocumentClient documents,
            DartArchiveExtractor extractor
    ) {
        this.repository = repository;
        this.dart = dart;
        this.clock = clock;
        this.scheduled = scheduled;
        this.originals = originals;
        this.store = store;
        this.documents = documents;
        this.extractor = extractor;

        originalTransaction = new TransactionTemplate(manager);
        originalTransaction.setTimeout(20);
        originalTransaction.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED
        );

        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(10);
        transaction.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED
        );
    }

    @Scheduled(
            fixedDelayString = "${app.dart.worker-delay-ms:1000}"
    )
    public void poll() {
        if (!scheduled || !dart.ready()) {
            return;
        }

        try {
            runOnce();

        } catch (RuntimeException ex) {
            // 예외 원문에 JDBC/HTTP 요청이 포함될 수 있다.
            log.error(
                    "Disclosure worker storage failure type={}",
                    ex.getClass().getSimpleName()
            );
        }
    }

    @Scheduled(
            fixedDelayString = "${app.dart.cleanup-delay-ms:300000}"
    )
    public void cleanup() {
        if (!scheduled) {
            return;
        }

        try {
            repository.clearExpiredResponses(
                    clock.instant()
            );

            store.cleanup();

        } catch (RuntimeException ex) {
            log.error(
                    "Disclosure response cleanup failure type={}",
                    ex.getClass().getSimpleName()
            );
        }
    }

    public void runOnce() {
        Lease lease = transaction.execute(
                status -> repository.claim(
                        clock.instant()
                )
        );

        if (lease == null) {
            return;
        }

        try {
            var p = lease.parameters();

            if (p == null) {
                throw new DartFailure(
                        "DART_INVALID_JOB",
                        false
                );
            }

            if ("DOCUMENTS".equals(p.phase())) {
                runOriginal(lease);
                return;
            }

            if (p.cursor().retries() > 2) {
                throw new DartFailure(
                        "LEASE_EXPIRED",
                        false
                );
            }

            // 외부 HTTP 호출 중에는 DB 트랜잭션을 유지하지 않는다.
            var page = dart.list(
                    lease.corpCode(),
                    p.cursor().fromDate(),
                    p.cursor().end(
                            p.request().toDate()
                    ),
                    p.cursor().page()
            );

            transaction.executeWithoutResult(
                    status -> repository.savePage(
                            lease,
                            page,
                            clock.instant()
                    )
            );

        } catch (DartFailure ex) {
            transaction.executeWithoutResult(
                    status -> repository.fail(
                            lease,
                            ex.code(),
                            ex.retryable(),
                            clock.instant()
                    )
            );

            log.warn(
                    "Disclosure collection task={} code={}",
                    lease.taskId(),
                    ex.code()
            );
        }
    }

    private void runOriginal(Lease lease) {
        if (lease.document() == null) {
            originalTransaction.executeWithoutResult(
                    status -> originals.idle(
                            lease,
                            clock.instant()
                    )
            );

            return;
        }

        try {
            var work = lease.document();

            if (work.attempt() > 3) {
                throw new DartFailure(
                        "LEASE_EXPIRED",
                        false
                );
            }

            if (work.reportType() == null) {
                throw new DartFailure(
                        "DART_SOURCE_MISSING",
                        false
                );
            }

            try (var stage = store.stage()) {
                documents.download(
                        work.receiptNo(),
                        stage.path()
                );

                var result = extractor.extract(
                        stage.path(),
                        work.receiptNo(),
                        lease.corpCode(),
                        work.reportType()
                );

                var saved = store.put(
                        lease.companyId(),
                        work.receiptNo(),
                        stage.path()
                );

                originalTransaction.executeWithoutResult(
                        status -> originals.complete(
                                lease,
                                saved,
                                result,
                                clock.instant()
                        )
                );
            }

        } catch (DartFailure ex) {
            originalTransaction.executeWithoutResult(
                    status -> originals.fail(
                            lease,
                            ex.code(),
                            ex.retryable(),
                            clock.instant()
                    )
            );

            log.warn(
                    "DART original task={} receipt={} code={}",
                    lease.taskId(),
                    lease.document().receiptNo(),
                    ex.code()
            );
        }
    }
}