package com.dadot.server.domain.auth;

import com.dadot.server.domain.auth.dto.request.IssueEmailVerificationRequest;
import com.dadot.server.domain.auth.dto.response.VerifyEmailVerificationResponse;
import com.dadot.server.domain.auth.entity.VerificationPurpose;
import com.dadot.server.domain.auth.service.*;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.UserPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmailVerificationIntegrationTest.TestConfig.class)
class EmailVerificationConfirmationIntegrationTest {

    private static final String EMAIL = "confirm@example.invalid";
    @Autowired EmailVerificationService issuance;
    @Autowired EmailVerificationConfirmationService confirmation;
    @Autowired JdbcTemplate jdbc;
    @Autowired VerificationTokenCrypto crypto;
    @Autowired EmailVerificationIntegrationTest.MutableClock clock;
    @Autowired EmailVerificationIntegrationTest.FakeMailer mailer;
    @Autowired MockMvc mvc;

    @BeforeEach
    void reset() {
        assertEquals("dadot_test_db", jdbc.queryForObject("select database()", String.class),
                "반드시 전용 테스트 DB를 사용해야 합니다.");
        for (String table : List.of("api_idempotency_requests", "auth_request_guards",
                "auth_proofs", "email_verifications", "auth_sessions", "auth_identities",
                "user_agreements", "user_onboarding", "users")) {
            jdbc.update("delete from " + table);
        }
        clock.set(java.time.Instant.parse("2026-10-23T10:00:00Z"));
        mailer.clear();
    }

    @Test
    void successStoresOnlyHashAndBindsSource() {
        Challenge challenge = issue(VerificationPurpose.SIGN_UP, null);
        var response = verify(challenge, key(), null);
        assertEquals(clock.instant().plusSeconds(600), response.expiresAt());
        assertEquals(32, Base64.getUrlDecoder().decode(response.verificationToken()).length);
        var row = jdbc.queryForMap("select * from auth_proofs");
        assertEquals(crypto.tokenHash(response.verificationToken()), row.get("token_hash"));
        assertEquals(challenge.id(), row.get("verification_id"));
        assertEquals("EMAIL", row.get("provider"));
        assertEquals("SIGN_UP", row.get("purpose"));
        assertEquals(EMAIL, row.get("subject_email"));
        assertNull(row.get("user_id"));
        assertNotNull(jdbc.queryForObject("select verified_at from email_verifications",
                LocalDateTime.class));
    }

    @Test
    void sameKeyReturnsSameEncryptedTokenWithoutAnotherProof() {
        Challenge challenge = issue(VerificationPurpose.SIGN_UP, null);
        String requestKey = key();
        var first = verify(challenge, requestKey, null);
        assertEquals(first, verify(challenge, requestKey, null));
        assertEquals(1, count("auth_proofs"));
        var row = jdbc.queryForMap("select * from api_idempotency_requests where operation like '%/verify'");
        assertNull(row.get("response_payload"));
        assertFalse(row.get("encrypted_response").toString().contains(first.verificationToken()));
        assertEquals("v1", row.get("response_key_version"));
    }

    @Test
    void wrongCodeCommitsAttemptAndSameKeyDoesNotCountTwice() {
        Challenge challenge = issue(VerificationPurpose.SIGN_UP, null);
        String requestKey = key();
        for (int i = 0; i < 2; i++) {
            assertCode(ErrorCode.INVALID_VERIFICATION_CODE, () -> confirmation.verify(
                    challenge.id(), wrong(challenge), requestKey, null));
        }
        assertEquals(1, attempts());
        assertEquals(0, count("auth_proofs"));
        assertNotNull(verify(challenge, key(), null));
    }

    @Test
    void sameKeyWithChangedCodeConflictsWithoutAnotherAttempt() {
        Challenge challenge = issue(VerificationPurpose.SIGN_UP, null);
        String requestKey = key();
        assertCode(ErrorCode.INVALID_VERIFICATION_CODE, () -> confirmation.verify(
                challenge.id(), wrong(challenge), requestKey, null));
        assertCode(ErrorCode.IDEMPOTENCY_CONFLICT, () -> verify(challenge, requestKey, null));
        assertEquals(1, attempts());
    }

    @Test
    void fifthMismatchInvalidatesAndCorrectCodeCannotRecoverIt() {
        Challenge challenge = issue(VerificationPurpose.SIGN_UP, null);
        for (int i = 1; i <= 5; i++) {
            assertCode(i == 5 ? ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED
                            : ErrorCode.INVALID_VERIFICATION_CODE,
                    () -> confirmation.verify(challenge.id(), wrong(challenge), key(), null));
        }
        assertEquals(5, attempts());
        assertNotNull(jdbc.queryForObject("select invalidated_at from email_verifications",
                LocalDateTime.class));
        assertCode(ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED, () -> verify(challenge, key(), null));
        assertEquals(0, count("auth_proofs"));
    }

    @Test
    void exactTenMinuteBoundaryIsExpired() {
        Challenge challenge = issue(VerificationPurpose.SIGN_UP, null);
        clock.advance(600);
        assertCode(ErrorCode.VERIFICATION_EXPIRED, () -> verify(challenge, key(), null));
        assertEquals(0, attempts());
    }

    @Test
    void newKeyCannotMintAnotherProofAfterSuccess() {
        Challenge challenge = issue(VerificationPurpose.SIGN_UP, null);
        verify(challenge, key(), null);
        assertCode(ErrorCode.VERIFICATION_EXPIRED, () -> verify(challenge, key(), null));
        assertEquals(1, count("auth_proofs"));
    }

    @Test
    void resendInvalidatesOldCodeAndOldSuccessfulReplay() {
        Challenge old = issue(VerificationPurpose.SIGN_UP, null);
        String requestKey = key();
        verify(old, requestKey, null);
        clock.advance(60);
        Challenge fresh = issue(VerificationPurpose.SIGN_UP, null);
        assertCode(ErrorCode.VERIFICATION_EXPIRED, () -> verify(old, requestKey, null));
        assertNotNull(verify(fresh, key(), null));
    }

    @Test
    void proofHasItsOwnTenMinuteLifetimeAfterVerification() {
        Challenge challenge = issue(VerificationPurpose.SIGN_UP, null);
        clock.advance(590);
        String requestKey = key();
        var first = verify(challenge, requestKey, null);
        clock.advance(20); // 원본 코드는 만료됐지만 증빙은 유효하다.
        assertEquals(first, verify(challenge, requestKey, null));
        clock.advance(580);
        assertCode(ErrorCode.VERIFICATION_EXPIRED, () -> verify(challenge, requestKey, null));
    }

    @Test
    void consumedOrInvalidatedProofCannotBeReplayed() {
        Challenge challenge = issue(VerificationPurpose.SIGN_UP, null);
        String requestKey = key();
        verify(challenge, requestKey, null);
        jdbc.update("update auth_proofs set consumed_at = issued_at");
        assertCode(ErrorCode.VERIFICATION_EXPIRED, () -> verify(challenge, requestKey, null));
        jdbc.update("update auth_proofs set consumed_at = null, invalidated_at = issued_at");
        assertCode(ErrorCode.VERIFICATION_EXPIRED, () -> verify(challenge, requestKey, null));
    }

    @Test
    void expiredIdempotencyKeyRemainsAConflict() {
        Challenge challenge = issue(VerificationPurpose.SIGN_UP, null);
        String requestKey = key();
        verify(challenge, requestKey, null);
        clock.advance(86400);
        assertCode(ErrorCode.IDEMPOTENCY_CONFLICT, () -> verify(challenge, requestKey, null));
    }

    @Test
    void missingResetAccountCannotObtainProof() {
        Challenge challenge = issue(VerificationPurpose.PASSWORD_RESET, null);
        assertCode(ErrorCode.INVALID_VERIFICATION_CODE, () -> verify(challenge, key(), null));
        assertEquals(0, count("auth_proofs"));
    }

    @Test
    void resetProofIsBoundToOriginalActiveAccount() {
        long user = createIdentity();
        Challenge challenge = issue(VerificationPurpose.PASSWORD_RESET, null);
        verify(challenge, key(), null);
        assertEquals(user, jdbc.queryForObject("select user_id from auth_proofs", Long.class));
    }

    @Test
    void deletingAccountCannotVerifyOrReplay() {
        long user = createIdentity();
        Challenge challenge = issue(VerificationPurpose.PASSWORD_RESET, null);
        String requestKey = key();
        verify(challenge, requestKey, null);
        jdbc.update("update users set status = 'DELETING' where id = ?", user);
        assertCode(ErrorCode.INVALID_VERIFICATION_CODE, () -> verify(challenge, requestKey, null));
    }

    @Test
    void passwordChangeRequiresTheSameAuthenticatedUserIncludingReplay() {
        long user = createIdentity();
        Challenge challenge = issue(VerificationPurpose.PASSWORD_CHANGE, user);
        String requestKey = key();
        assertCode(ErrorCode.UNAUTHENTICATED, () -> verify(challenge, requestKey, null));
        assertCode(ErrorCode.FORBIDDEN, () -> verify(challenge, requestKey, user + 1));
        var first = verify(challenge, requestKey, user);
        assertEquals(first, verify(challenge, requestKey, user));
        assertCode(ErrorCode.UNAUTHENTICATED, () -> verify(challenge, requestKey, null));
    }

    @Test
    void concurrentSameKeyProducesOneProofAndOneToken() throws Exception {
        Challenge challenge = issue(VerificationPurpose.SIGN_UP, null);
        String requestKey = key();
        var responses = parallel(() -> verify(challenge, requestKey, null));
        assertEquals(1, new HashSet<>(responses).size());
        assertEquals(1, count("auth_proofs"));
    }

    @Test
    void concurrentDifferentKeysStillProduceOnlyOneProof() throws Exception {
        Challenge challenge = issue(VerificationPurpose.SIGN_UP, null);
        var outcomes = parallel(() -> {
            try { verify(challenge, key(), null); return "OK"; }
            catch (BusinessException ex) { return ex.getErrorCode().name(); }
        });
        assertEquals(1, Collections.frequency(outcomes, "OK"));
        assertEquals(7, Collections.frequency(outcomes, "VERIFICATION_EXPIRED"));
        assertEquals(1, count("auth_proofs"));
    }

    @Test
    void concurrentWrongCodesCannotExceedFiveAttempts() throws Exception {
        Challenge challenge = issue(VerificationPurpose.SIGN_UP, null);
        var outcomes = parallel(() -> {
            try { confirmation.verify(challenge.id(), wrong(challenge), key(), null); return "OK"; }
            catch (BusinessException ex) { return ex.getErrorCode().name(); }
        });
        assertEquals(4, Collections.frequency(outcomes, "INVALID_VERIFICATION_CODE"));
        assertEquals(4, Collections.frequency(outcomes, "VERIFICATION_ATTEMPTS_EXCEEDED"));
        assertEquals(5, attempts());
    }

    @Test
    void httpSuccessNormalizesUuidUsesEnvelopeAndDoesNotCache() throws Exception {
        Challenge challenge = issue(VerificationPurpose.SIGN_UP, null);
        String requestKey = key();
        mvc.perform(post(path(challenge.id().toUpperCase(Locale.ROOT)))
                        .header("Idempotency-Key", requestKey.toUpperCase(Locale.ROOT))
                        .contentType("application/json").content(body(challenge.code())))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.verificationToken").isString())
                .andExpect(jsonPath("$.data.expiresAt").value("2026-10-23T10:10:00Z"));
        assertNotNull(verify(challenge, requestKey, null));
    }

    @Test
    void httpRejectsMissingKeyMalformedUuidAndNonSixDigitCode() throws Exception {
        Challenge challenge = issue(VerificationPurpose.SIGN_UP, null);
        mvc.perform(post(path(challenge.id())).contentType("application/json")
                        .content(body(challenge.code())))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path("not-a-uuid")).header("Idempotency-Key", key())
                        .contentType("application/json").content(body(challenge.code())))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path(challenge.id())).header("Idempotency-Key", "1-1-1-1-1")
                        .contentType("application/json").content(body(challenge.code())))
                .andExpect(status().isBadRequest());
        for (String code : List.of("12345", "1234567", "abcdef", "１２３４５６", " 123456")) {
            mvc.perform(post(path(challenge.id())).header("Idempotency-Key", key())
                            .contentType("application/json").content(body(code)))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(0, attempts());
    }

    @Test
    void httpPasswordChangeAcceptsOnlyTrustedPrincipal() throws Exception {
        long user = createIdentity();
        Challenge challenge = issue(VerificationPurpose.PASSWORD_CHANGE, user);
        mvc.perform(post(path(challenge.id())).header("Idempotency-Key", key())
                        .contentType("application/json").content(body(challenge.code())))
                .andExpect(status().isUnauthorized());
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                new UserPrincipal(user), null, List.of());
        mvc.perform(post(path(challenge.id())).with(authentication(auth))
                        .header("Idempotency-Key", key()).contentType("application/json")
                        .content(body(challenge.code())))
                .andExpect(status().isOk());
    }

    @Test
    void missingVerificationReturns404() {
        assertCode(ErrorCode.RESOURCE_NOT_FOUND,
                () -> confirmation.verify(key(), "123456", key(), null));
    }

    @Test
    void cleanupRemovesSensitiveResponseButKeepsKeyTombstone() {
        Challenge challenge = issue(VerificationPurpose.SIGN_UP, null);
        String requestKey = key();
        verify(challenge, requestKey, null);
        clock.advance(86401);
        new VerificationMaintenance(jdbc, clock).clean();
        var row = jdbc.queryForMap("select * from api_idempotency_requests where operation like '%/verify'");
        assertNull(row.get("response_payload"));
        assertNull(row.get("encrypted_response"));
        assertNull(row.get("response_key_version"));
        assertNotNull(row.get("key_hash"));
        assertCode(ErrorCode.IDEMPOTENCY_CONFLICT, () -> verify(challenge, requestKey, null));
    }

    @Test
    void databaseFailureRollsBackProofAndVerifiedTimestamp() {
        Challenge challenge = issue(VerificationPurpose.SIGN_UP, null);
        installFailureTrigger();
        try {
            assertThrows(RuntimeException.class, () -> verify(challenge, key(), null));
        } finally {
            jdbc.execute("drop trigger auth02_fail_save");
        }
        assertEquals(0, count("auth_proofs"));
        assertNull(jdbc.queryForObject("select verified_at from email_verifications", LocalDateTime.class));
        assertNotNull(verify(challenge, key(), null));
    }

    @Test
    void databaseFailureDoesNotPartiallySaveWrongAttempt() {
        Challenge challenge = issue(VerificationPurpose.SIGN_UP, null);
        installFailureTrigger();
        try {
            assertThrows(RuntimeException.class, () -> confirmation.verify(
                    challenge.id(), wrong(challenge), key(), null));
        } finally {
            jdbc.execute("drop trigger auth02_fail_save");
        }
        assertEquals(0, attempts());
    }

    private void installFailureTrigger() {
        jdbc.execute("""
                create trigger auth02_fail_save before insert on api_idempotency_requests
                for each row
                begin
                    if new.operation like '%/verify' then
                        signal sqlstate '45000' set message_text = 'synthetic persistence failure';
                    end if;
                end
                """);
    }

    private Challenge issue(VerificationPurpose purpose, Long actor) {
        var response = issuance.issue(new IssueEmailVerificationRequest(EMAIL, purpose),
                key(), "192.0.2.10", actor);
        return new Challenge(response.verificationId(), mailer.messages.getLast().code());
    }

    private VerifyEmailVerificationResponse verify(Challenge challenge, String key, Long actor) {
        return confirmation.verify(challenge.id(), challenge.code(), key, actor);
    }

    private String wrong(Challenge challenge) {
        return challenge.code().equals("000000") ? "000001" : "000000";
    }

    private int attempts() {
        return jdbc.queryForObject("select failed_attempts from email_verifications", Integer.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private String key() { return UUID.randomUUID().toString(); }
    private String path(String id) { return "/v1/auth/email-verifications/" + id + "/verify"; }
    private String body(String code) { return "{\"code\":\"" + code + "\"}"; }

    private void assertCode(ErrorCode code, Runnable action) {
        assertEquals(code, assertThrows(BusinessException.class, action::run).getErrorCode());
    }

    private long createIdentity() {
        jdbc.update("insert into users(nickname, status) values ('인증테스트', 'ACTIVE')");
        long user = jdbc.queryForObject("select max(id) from users", Long.class);
        jdbc.update("""
                insert into auth_identities(user_id, provider, provider_subject, email, password_hash)
                values (?, 'EMAIL', ?, ?, 'synthetic-test-hash')
                """, user, EMAIL, EMAIL);
        return user;
    }

    private <T> List<T> parallel(Callable<T> task) throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            CountDownLatch ready = new CountDownLatch(8);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return task.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) { results.add(future.get(20, TimeUnit.SECONDS)); }
            return results;
        }
    }

    private record Challenge(String id, String code) { }
}
