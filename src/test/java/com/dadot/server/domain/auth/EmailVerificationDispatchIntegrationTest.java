package com.dadot.server.domain.auth;

import com.dadot.server.domain.auth.dto.request.IssueEmailVerificationRequest;
import com.dadot.server.domain.auth.dto.response.IssueEmailVerificationResponse;
import com.dadot.server.domain.auth.entity.VerificationPurpose;
import com.dadot.server.domain.auth.repository.EmailVerificationDispatchRepository;
import com.dadot.server.domain.auth.repository.VerificationRequestStore;
import com.dadot.server.domain.auth.service.EmailVerificationConfirmationService;
import com.dadot.server.domain.auth.service.EmailVerificationDispatchTransaction;
import com.dadot.server.domain.auth.service.EmailVerificationIssuer;
import com.dadot.server.domain.auth.service.VerificationMailer;
import com.dadot.server.domain.auth.service.VerificationMaintenance;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"app.dart.worker-enabled=false", "app.dart.originals.enabled=false"})
@ActiveProfiles("test")
@Import(EmailVerificationDispatchIntegrationTest.Config.class)
class EmailVerificationDispatchIntegrationTest {
    private static final Instant BASE = Instant.parse("2026-11-17T01:00:00Z");
    @Autowired JdbcTemplate jdbc;
    @Autowired EmailVerificationIssuer issuer;
    @Autowired EmailVerificationDispatchTransaction transaction;
    @Autowired EmailVerificationConfirmationService confirmation;
    @Autowired VerificationTokenCrypto crypto;
    @Autowired ControlledMailer mailer;
    @MockitoSpyBean Clock clock;
    @MockitoSpyBean VerificationRequestStore requests;
    @MockitoSpyBean EmailVerificationDispatchRepository deliveries;
    private String email;

    @BeforeEach
    void setup() {
        assertEquals("dadot_test_db", jdbc.queryForObject("select database()", String.class));
        reset(clock, requests, deliveries);
        doReturn(BASE).when(clock).instant();
        email = "dispatch-" + UUID.randomUUID() + "@example.invalid";
        mailer.messages.clear();
        mailer.entered = new CountDownLatch(0);
        mailer.release = new CountDownLatch(0);
        mailer.fail = false;
    }

    @Test
    void pendingCodeIsEncryptedAndCannotBeConfirmedBeforeSmtpCompletes() throws Exception {
        mailer.block(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> issue(key()));
            String id;
            try {
                assertTrue(mailer.entered.await(5, TimeUnit.SECONDS));
                var row = jdbc.queryForMap("select * from email_verification_deliveries where email=?", email);
                id = (String) row.get("verification_id");
                String code = mailer.messages.getFirst().code();
                assertNotEquals(code, row.get("encrypted_code"));
                assertEquals(0, count("email_verifications"));
                assertCode(ErrorCode.RESOURCE_NOT_FOUND, () -> confirmation.verify(id, code, key(), null));
            } finally { mailer.release.countDown(); }
            var response = future.get(5, TimeUnit.SECONDS);
            assertEquals(id, response.verificationId());
            assertEquals(0, count("email_verification_deliveries"));
            assertNotNull(confirmation.verify(id, mailer.messages.getFirst().code(), key(), null));
        }
    }

    @Test
    void slowSameKeyRequestsWaitWithoutResendingAndReturnTheSameResponse() throws Exception {
        String key = key();
        mailer.block(1);
        var results = new ArrayList<Future<IssueEmailVerificationResponse>>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            results.add(executor.submit(() -> issue(key)));
            try {
                assertTrue(mailer.entered.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 3; i++) results.add(executor.submit(() -> issue(key)));
                assertEquals(1, mailer.messages.size());
                assertEquals(1, jdbc.queryForObject("select 1", Integer.class));
            } finally { mailer.release.countDown(); }
            var first = results.getFirst().get(5, TimeUnit.SECONDS);
            for (var result : results) assertEquals(first, result.get(5, TimeUnit.SECONDS));
            assertEquals(1, mailer.messages.size());
        }
    }

    @Test
    void anotherKeyForAnAddressWithAnActiveDeliveryIsRateLimited() {
        transaction.reserve(request(), key(), null);
        assertCode(ErrorCode.RATE_LIMITED, () -> issue(key()));
        assertTrue(mailer.messages.isEmpty());
    }

    @Test
    void oneKeyCannotBeReboundToAnotherBodyWhilePending() {
        String key = key();
        transaction.reserve(request(), key, null);
        assertCode(ErrorCode.IDEMPOTENCY_CONFLICT, () -> issuer.issue(new IssueEmailVerificationRequest(
                "another-" + email, VerificationPurpose.SIGN_UP), key, null));
        assertEquals(1, count("email_verification_deliveries"));
    }

    @Test
    void expiredLeaseReusesTheReservedCodeAndVerificationIdAfterProcessLoss() {
        String key = key();
        var reserved = transaction.reserve(request(), key, null).delivery();
        String code = crypto.decrypt(reserved.encryptedCode(), reserved.keyVersion(), reserved.encryptionContext());
        doReturn(BASE.plusSeconds(121)).when(clock).instant();
        var response = issue(key);
        assertEquals(reserved.id(), response.verificationId());
        assertEquals(code, mailer.messages.getFirst().code());
        assertEquals(BASE.plusSeconds(600), response.expiresAt());
    }

    @Test
    void staleLeaseOwnerCannotRecordASuccessfulSend() {
        String key = key();
        var old = transaction.reserve(request(), key, null).delivery();
        doReturn(BASE.plusSeconds(121)).when(clock).instant();
        var replacement = transaction.reserve(request(), key, null).delivery();
        assertEquals(old.id(), replacement.id());
        assertNotEquals(old.owner(), replacement.owner());
        assertCode(ErrorCode.SERVICE_UNAVAILABLE, () -> transaction.sent(old));
        transaction.sent(replacement);
        assertEquals(old.id(), transaction.complete(replacement).verificationId());
        assertEquals(1, count("email_verifications"));
    }

    @Test
    void recordedSmtpSuccessCanBeCompletedAfterRestartWithoutSendingAgain() {
        String key = key();
        var delivery = transaction.reserve(request(), key, null).delivery();
        transaction.sent(delivery);
        assertEquals(delivery.id(), issue(key).verificationId());
        assertTrue(mailer.messages.isEmpty());
        assertEquals(0, count("email_verification_deliveries"));
    }

    @Test
    void finalDatabaseFailureKeepsTheOldCodeAndRecoversWithoutASecondSend() {
        var old = issue(key());
        doReturn(BASE.plusSeconds(60)).when(clock).instant();
        String key = key();
        doThrow(new DataAccessResourceFailureException("synthetic persistence failure"))
                .doCallRealMethod().when(requests).complete(anyString(), anyString(), isNull(), any(), any());
        assertThrows(DataAccessResourceFailureException.class, () -> issue(key));
        assertEquals(2, mailer.messages.size());
        assertEquals(1, count("email_verifications"));
        assertNull(jdbc.queryForObject("select invalidated_at from email_verifications where id=?",
                LocalDateTime.class, old.verificationId()));
        assertNotNull(jdbc.queryForObject("select sent_at from email_verification_deliveries where email=?",
                LocalDateTime.class, email));
        var recovered = issue(key);
        assertNotEquals(old.verificationId(), recovered.verificationId());
        assertEquals(2, mailer.messages.size());
        assertEquals(2, count("email_verifications"));
        assertEquals(0, count("email_verification_deliveries"));
    }

    @Test
    void failureToRecordSmtpAcceptanceReusesTheCodeOnTheNextLease() {
        String key = key();
        doThrow(new DataAccessResourceFailureException("synthetic send-marker failure"))
                .doCallRealMethod().when(deliveries).sent(any(), any());
        assertThrows(DataAccessResourceFailureException.class, () -> issue(key));
        String id = jdbc.queryForObject("select verification_id from email_verification_deliveries where email=?",
                String.class, email);
        assertEquals(0, count("email_verifications"));
        doReturn(BASE.plusSeconds(121)).when(clock).instant();
        var recovered = issue(key);
        assertEquals(id, recovered.verificationId());
        assertEquals(2, mailer.messages.size());
        assertEquals(mailer.messages.get(0).code(), mailer.messages.get(1).code());
        assertEquals(1, count("email_verifications"));
    }

    @Test
    void knownSmtpFailureDoesNotInvalidateTheOldCodeAndSameKeyCanRetry() {
        var old = issue(key());
        doReturn(BASE.plusSeconds(60)).when(clock).instant();
        mailer.fail = true;
        String key = key();
        assertCode(ErrorCode.SERVICE_UNAVAILABLE, () -> issue(key));
        assertEquals(0, count("email_verification_deliveries"));
        assertNull(jdbc.queryForObject("select invalidated_at from email_verifications where id=?",
                LocalDateTime.class, old.verificationId()));
        mailer.fail = false;
        assertNotEquals(old.verificationId(), issue(key).verificationId());
    }

    @Test
    void credentialChangeDuringSmtpPreventsTheEarlierReservationFromBeingActivated() throws Exception {
        long user = account();
        mailer.block(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> issuer.issue(new IssueEmailVerificationRequest(
                    email, VerificationPurpose.PASSWORD_CHANGE), key(), user));
            try {
                assertTrue(mailer.entered.await(5, TimeUnit.SECONDS));
                jdbc.update("update auth_identities set password_hash='different-test-hash' where user_id=?", user);
            } finally { mailer.release.countDown(); }
            var error = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertEquals(ErrorCode.SERVICE_UNAVAILABLE, ((BusinessException) error.getCause()).getErrorCode());
            assertEquals(0, count("email_verifications"));
        }
    }

    @Test
    void accountDeletionDuringSmtpPreventsActivationAndCascadesTheReservation() throws Exception {
        long user = account();
        mailer.block(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> issuer.issue(new IssueEmailVerificationRequest(
                    email, VerificationPurpose.PASSWORD_CHANGE), key(), user));
            try {
                assertTrue(mailer.entered.await(5, TimeUnit.SECONDS));
                jdbc.update("delete from auth_identities where user_id=?", user);
                jdbc.update("delete from users where id=?", user);
            } finally { mailer.release.countDown(); }
            var error = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
            assertInstanceOf(BusinessException.class, error.getCause());
            assertEquals(0, count("email_verifications"));
            assertEquals(0, count("email_verification_deliveries"));
        }
    }

    @Test
    void excessSmtpConcurrencyIsRejectedWithoutLeavingAReservation() throws Exception {
        mailer.block(5);
        var results = new ArrayList<Future<?>>();
        try (var executor = Executors.newFixedThreadPool(5)) {
            try {
                for (int i = 0; i < 5; i++) {
                    String other = "limit-" + UUID.randomUUID() + "@example.invalid";
                    results.add(executor.submit(() -> issuer.issue(new IssueEmailVerificationRequest(
                            other, VerificationPurpose.SIGN_UP), key(), null)));
                }
                assertTrue(mailer.entered.await(5, TimeUnit.SECONDS));
                assertCode(ErrorCode.SERVICE_UNAVAILABLE, () -> issue(key()));
                assertEquals(0, count("email_verification_deliveries"));
                assertEquals(1, jdbc.queryForObject("select 1", Integer.class));
            } finally { mailer.release.countDown(); }
            for (var result : results) result.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void expiredReservationsAreRemovedByExistingMaintenance() {
        transaction.reserve(request(), key(), null);
        doReturn(BASE.plusSeconds(601)).when(clock).instant();
        new VerificationMaintenance(jdbc, clock).clean();
        assertEquals(0, count("email_verification_deliveries"));
    }

    @Test
    void expiredReservationStartsAFreshCodeInsteadOfActivatingAnExpiredOne() {
        String key = key();
        var reserved = transaction.reserve(request(), key, null).delivery();
        doReturn(BASE.plusSeconds(601)).when(clock).instant();
        assertNotEquals(reserved.id(), issue(key).verificationId());
    }

    private IssueEmailVerificationResponse issue(String key) { return issuer.issue(request(), key, null); }
    private IssueEmailVerificationRequest request() { return new IssueEmailVerificationRequest(email, VerificationPurpose.SIGN_UP); }
    private String key() { return UUID.randomUUID().toString(); }
    private static void assertCode(ErrorCode expected, Runnable action) {
        assertEquals(expected, assertThrows(BusinessException.class, action::run).getErrorCode());
    }
    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table + " where email=?", Integer.class, email); }
    private long account() {
        jdbc.update("insert into users(nickname) values (?)", "발송검증");
        long user = jdbc.queryForObject("select max(id) from users", Long.class);
        jdbc.update("""
                insert into auth_identities(user_id,provider,provider_subject,email,password_hash)
                values (?,'EMAIL',?,?,'original-test-hash')
                """, user, email, email);
        return user;
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class Config { @Bean @Primary ControlledMailer controlledMailer() { return new ControlledMailer(); } }
    static class ControlledMailer implements VerificationMailer {
        record Message(String email, String code) { @Override public String toString() { return "Message[REDACTED]"; } }
        final List<Message> messages = new CopyOnWriteArrayList<>();
        volatile CountDownLatch entered = new CountDownLatch(0), release = new CountDownLatch(0);
        volatile boolean fail;
        void block(int count) { entered = new CountDownLatch(count); release = new CountDownLatch(1); }
        public void send(String email, String code, VerificationPurpose purpose) {
            messages.add(new Message(email, code));
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            if (fail) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}
