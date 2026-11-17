package com.dadot.server.domain.auth;

import com.dadot.server.domain.auth.dto.request.IssueEmailVerificationRequest;
import com.dadot.server.domain.auth.entity.VerificationPurpose;
import com.dadot.server.domain.auth.service.EmailVerificationIssuer;
import com.dadot.server.domain.auth.service.VerificationMailer;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"app.dart.worker-enabled=false", "app.dart.originals.enabled=false",
        "spring.datasource.hikari.maximum-pool-size=5", "spring.datasource.hikari.minimum-idle=1",
        "spring.datasource.hikari.connection-timeout=1000"})
@ActiveProfiles("test")
@Import(EmailVerificationConnectionRegressionTest.Config.class)
class EmailVerificationConnectionRegressionTest {
    @Autowired EmailVerificationIssuer issuer;
    @Autowired HikariDataSource dataSource;
    @Autowired WaitingMailer mailer;

    @BeforeEach void clear() { mailer.inTransaction.set(false); }

    @Test
    void smtpRunsWithoutAnActiveDatabaseTransaction() {
        mailer.entered = new CountDownLatch(0); mailer.release = new CountDownLatch(0);
        issue();
        assertFalse(mailer.inTransaction.get());
    }

    @Test
    void fivePendingMailSendsLeaveTheDatabasePoolAvailable() throws Exception {
        mailer.entered = new CountDownLatch(5); mailer.release = new CountDownLatch(1);
        var futures = new ArrayList<Future<?>>();
        try(var executor = Executors.newFixedThreadPool(5)) {
            try {
                for (int i = 0;i<5;i++) futures.add(executor.submit(this::issue));
                assertTrue(mailer.entered.await(5, TimeUnit.SECONDS));
                try(var connection = dataSource.getConnection(); var statement = connection.createStatement();
                    var result = statement.executeQuery("select 1")) {
                    assertTrue(result.next()); assertEquals(1, result.getInt(1));
                }
            } finally { mailer.release.countDown(); }
            for (var future:futures) future.get(5, TimeUnit.SECONDS);
        }
    }

    private void issue() {
        issuer.issue(new IssueEmailVerificationRequest("connection-" + UUID.randomUUID() + "@example.invalid",
                VerificationPurpose.SIGN_UP), UUID.randomUUID().toString(), null);
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Config { @Bean @Primary WaitingMailer waitingMailer() { return new WaitingMailer(); } }
    static class WaitingMailer implements VerificationMailer {
        volatile CountDownLatch entered = new CountDownLatch(0), release = new CountDownLatch(0);
        final AtomicBoolean inTransaction = new AtomicBoolean();
        public void send(String email, String code, VerificationPurpose purpose) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) inTransaction.set(true);
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        }
    }
}
