package com.dadot.server.domain.auth;

import com.dadot.server.domain.auth.dto.request.EmailLoginRequest;
import com.dadot.server.domain.auth.dto.request.IssueEmailVerificationRequest;
import com.dadot.server.domain.auth.dto.request.PasswordChangeRequest;
import com.dadot.server.domain.auth.dto.request.PasswordResetRequest;
import com.dadot.server.domain.auth.entity.VerificationPurpose;
import com.dadot.server.domain.auth.repository.EmailLoginRepository;
import com.dadot.server.domain.auth.repository.PasswordChangeRepository;
import com.dadot.server.domain.auth.repository.VerificationConfirmationRepository;
import com.dadot.server.domain.auth.service.*;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.AccessTokenIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({EmailVerificationIntegrationTest.TestConfig.class,
        EmailSignupIntegrationTest.TestRoutes.class})
class PasswordChangeIntegrationTest {

    static final String PATH = "/v1/users/me/password";
    static final String EMAIL = "change@example.invalid";
    static final String OLD = "OldPassword123!";
    static final String NEW = "NewPassword456!";
    static final String IP = "192.0.2.18";

    @Autowired PasswordChangeService service;
    @Autowired PasswordResetService resetService;
    @Autowired EmailVerificationService issuance;
    @Autowired EmailVerificationConfirmationService confirmation;
    @Autowired EmailLoginService login;
    @Autowired EmailLoginSessionService sessions;
    @Autowired EmailLoginRepository logins;
    @Autowired VerificationTokenCrypto crypto;
    @Autowired AccessTokenIssuer tokens;
    @Autowired EmailVerificationIntegrationTest.MutableClock clock;
    @Autowired EmailVerificationIntegrationTest.FakeMailer mailer;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @MockitoSpyBean PasswordChangeRepository repository;
    @MockitoSpyBean PasswordEncoder passwords;
    @MockitoSpyBean VerificationConfirmationRepository confirmations;

    long userId;
    String sessionId;
    String currentToken;
    String originalHash;

    @BeforeEach
    void setup() {
        assertEquals("dadot_test_db",
                jdbc.queryForObject("select database()", String.class));

        reset(repository, passwords, confirmations);

        for (String table : List.of("user_devices", "api_idempotency_requests",
                "auth_request_guards", "auth_proofs", "email_verifications",
                "auth_sessions", "auth_identities", "user_agreements",
                "user_onboarding", "users")) {
            jdbc.update("delete from " + table);
        }

        clock.set(Instant.parse("2026-10-24T10:00:00Z"));
        mailer.clear();
        originalHash = passwords.encode(OLD);
        userId = member("change-member", EMAIL);
        sessionId = session(userId);
        currentToken = token(userId, sessionId, 0);
        device(userId, sessionId);
        clearInvocations(passwords);
    }

    @AfterEach
    void cleanup() {
        reset(repository, passwords, confirmations);
        jdbc.update("delete from user_devices");
    }

    @Test
    void keepsCurrentSessionAndDeviceWhileRevokingOthers() throws Exception {
        String otherSession = session(userId);
        String otherToken = token(userId, otherSession, 0);
        device(userId, otherSession);

        String proof = proof(VerificationPurpose.PASSWORD_CHANGE);
        String oldProof = proof(VerificationPurpose.PASSWORD_RESET);

        mvc.perform(patch(PATH)
                        .header("Authorization", "Bearer " + currentToken)
                        .header("Idempotency-Key", key())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(body(proof))))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().doesNotExist("Content-Type"))
                .andExpect(header().string("Cache-Control", "no-store"));

        assertTrue(passwords.matches(NEW, hash()));
        assertFalse(passwords.matches(OLD, hash()));
        assertEquals(0, version());
        assertNull(revoked(sessionId));
        assertNotNull(revoked(otherSession));
        assertEquals(1, deviceCount(userId));

        assertEquals(sessionId, jdbc.queryForObject(
                "select session_id from user_devices", String.class));

        assertNotNull(proofTime(proof, "consumed_at"));
        assertNotNull(proofTime(oldProof, "invalidated_at"));

        assertEquals(0, jdbc.queryForObject("""
                select count(*) from email_verifications
                where user_id = ? and invalidated_at is null
                """, Integer.class, userId));

        protectedStatus(currentToken, 200);
        protectedStatus(otherToken, 401);

        assertCode(ErrorCode.INVALID_CREDENTIALS,
                () -> login.login(new EmailLoginRequest(EMAIL, OLD), IP));

        assertNotNull(login.login(
                new EmailLoginRequest(EMAIL, NEW), IP).accessToken());
    }

    @Test
    void hashingRunsOutsideTransactionAndSecretsAreNotStoredInRequestCache() {
        String proof = proof(VerificationPurpose.PASSWORD_CHANGE);

        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return call.callRealMethod();
        }).when(passwords).encode(NEW);

        change(body(proof), key());

        verify(passwords).encode(NEW);
        assertEquals(1, rows());

        assertEquals(0, jdbc.queryForObject("""
                select count(*) from api_idempotency_requests
                where operation = ? and (
                    response_payload is not null or encrypted_response is not null
                    or response_key_version is not null)
                """, Integer.class, PasswordChangeRepository.OPERATION));

        assertFalse(body(proof).toString().contains(proof));
        assertFalse(body(proof).toString().contains(NEW));
    }

    @Test
    void replayAfterProofExpiryDoesNotRepeatHashingOrRevokeFreshLogin() {
        var request = body(proof(VerificationPurpose.PASSWORD_CHANGE));
        String key = key();

        change(request, key);

        var fresh = login.login(new EmailLoginRequest(EMAIL, NEW), IP);
        clock.advance(601);

        change(request, key.toUpperCase(Locale.ROOT));

        assertEquals(1, rows());
        verify(passwords).encode(NEW);
        protectedStatus(currentToken, 200);
        protectedStatus(fresh.accessToken(), 200);
    }

    @Test
    void sameKeyDifferentBodyConflicts() {
        var request = body(proof(VerificationPurpose.PASSWORD_CHANGE));
        String key = key();

        change(request, key);

        assertCode(ErrorCode.IDEMPOTENCY_CONFLICT, () -> change(
                new PasswordChangeRequest(
                        request.verificationToken(), "OtherPassword789!"), key));

        assertCode(ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> change(body(crypto.generateToken()), key));
    }

    @Test
    void keyCannotBeReusedFromAnotherSessionOfSameUser() {
        var request = body(proof(VerificationPurpose.PASSWORD_CHANGE));
        String key = key();

        change(request, key);
        String freshSession = session(userId);

        assertCode(ErrorCode.IDEMPOTENCY_CONFLICT, () -> service.change(
                request, key, userId, freshSession, 0, IP));

        assertNull(revoked(freshSession));
    }

    @Test
    void oldReplayCannotOverwriteLaterPasswordChange() {
        var first = body(proof(VerificationPurpose.PASSWORD_CHANGE));
        String key = key();

        change(first, key);
        clock.advance(60);

        var second = new PasswordChangeRequest(
                proof(VerificationPurpose.PASSWORD_CHANGE), "ThirdPassword789!");

        change(second, key());
        String latest = hash();

        change(first, key);

        assertEquals(latest, hash());
        assertEquals(2, rows());
    }

    @Test
    void consumedProofCannotBeUsedWithNewKey() {
        var request = body(proof(VerificationPurpose.PASSWORD_CHANGE));

        change(request, key());

        assertCode(ErrorCode.INVALID_VERIFICATION_TOKEN,
                () -> change(request, key()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "consumed", "invalidated", "future",
            "unverified", "sourceInvalidated"})
    void unusableProofIsRejectedBeforeHashing(String state) {
        String proof = proof(VerificationPurpose.PASSWORD_CHANGE);

        switch (state) {
            case "expired" -> clock.advance(600);
            case "consumed" -> jdbc.update(
                    "update auth_proofs set consumed_at = ?", utc());
            case "invalidated" -> jdbc.update(
                    "update auth_proofs set invalidated_at = ?", utc());
            case "future" -> jdbc.update(
                    "update auth_proofs set issued_at = ?",
                    utc(clock.instant().plusSeconds(1)));
            case "unverified" -> jdbc.update(
                    "update email_verifications set verified_at = null");
            case "sourceInvalidated" -> jdbc.update(
                    "update email_verifications set invalidated_at = ?", utc());
            default -> fail("Unknown state");
        }

        assertCode(ErrorCode.INVALID_VERIFICATION_TOKEN,
                () -> change(body(proof), key()));

        verify(passwords, never()).encode(anyString());
        unchanged();
    }

    @Test
    void resetPurposeProofIsRejected() {
        String proof = proof(VerificationPurpose.PASSWORD_RESET);

        assertCode(ErrorCode.INVALID_VERIFICATION_TOKEN,
                () -> change(body(proof), key()));

        verify(passwords, never()).encode(anyString());
        unchanged();
    }

    @Test
    void anotherUsersProofCannotChangeCurrentUsersPassword() {
        long other = member("other-member", "other@example.invalid");

        String proof = issueAndConfirm("other@example.invalid",
                VerificationPurpose.PASSWORD_CHANGE, other);

        assertCode(ErrorCode.INVALID_VERIFICATION_TOKEN,
                () -> change(body(proof), key()));

        unchanged();
    }

    @Test
    void socialOnlyAccountGetsExplicitConflict() {
        jdbc.update("""
                update auth_identities set provider = 'KAKAO',
                    provider_subject = 'kakao-subject', email = null, password_hash = null
                where user_id = ?
                """, userId);

        assertCode(ErrorCode.PASSWORD_AUTH_UNAVAILABLE,
                () -> change(body(crypto.generateToken()), key()));

        verify(passwords, never()).encode(anyString());
        assertEquals(0, rows());
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "versionChanged", "revoked", "expired", "missing"})
    void staleSessionCannotChangePassword(String state) {
        String proof = proof(VerificationPurpose.PASSWORD_CHANGE);

        switch (state) {
            case "inactive" -> jdbc.update(
                    "update users set status = 'DELETING'");
            case "versionChanged" -> jdbc.update(
                    "update users set auth_version = 1");
            case "revoked" -> jdbc.update(
                    "update auth_sessions set revoked_at = ?", utc());
            case "expired" -> clock.advance(3600);
            case "missing" -> {
                jdbc.update("delete from user_devices");
                jdbc.update("delete from auth_sessions");
            }
            default -> fail("Unknown state");
        }

        assertCode(ErrorCode.UNAUTHENTICATED,
                () -> change(body(proof), key()));

        assertEquals(originalHash, hash());
        assertEquals(0, rows());
    }

    @ParameterizedTest
    @ValueSource(strings = {"logout", "sessionExpiry", "proofExpiry", "resend"})
    void changeRechecksStateAfterPasswordHashing(String state) {
        String proof = proof(VerificationPurpose.PASSWORD_CHANGE);

        doAnswer(call -> {
            switch (state) {
                case "logout" -> jdbc.update(
                        "update auth_sessions set revoked_at = ?", utc());
                case "sessionExpiry" -> clock.advance(3600);
                case "proofExpiry" -> clock.advance(600);
                case "resend" -> {
                    clock.advance(60);
                    issuance.issue(new IssueEmailVerificationRequest(
                                    EMAIL, VerificationPurpose.PASSWORD_CHANGE),
                            key(), IP, userId);
                }
                default -> fail("Unknown state");
            }

            return call.callRealMethod();
        }).when(passwords).encode(NEW);

        var expected = state.equals("logout") || state.equals("sessionExpiry")
                ? ErrorCode.UNAUTHENTICATED
                : ErrorCode.INVALID_VERIFICATION_TOKEN;

        assertCode(expected, () -> change(body(proof), key()));

        assertEquals(originalHash, hash());
        assertEquals(0, rows());
    }

    @Test
    void sessionExpiringWhileProofLockIsObtainedIsRejected() {
        String proof = proof(VerificationPurpose.PASSWORD_CHANGE);

        doAnswer(call -> {
            var result = call.callRealMethod();
            clock.advance(3600);
            return result;
        }).when(repository).lockProof(any(), anyString(), any());

        assertCode(ErrorCode.UNAUTHENTICATED,
                () -> change(body(proof), key()));

        assertEquals(originalHash, hash());
        assertEquals(0, rows());
    }

    @ParameterizedTest
    @ValueSource(strings = {"database", "transactionStart", "transactionTimeout"})
    void failureRollsBackPasswordProofSessionAndDeviceChanges(String failure) {
        String otherSession = session(userId);
        device(userId, otherSession);
        String proof = proof(VerificationPurpose.PASSWORD_CHANGE);

        RuntimeException error = switch (failure) {
            case "transactionStart" ->
                    new CannotCreateTransactionException("simulated");
            case "transactionTimeout" ->
                    new TransactionTimedOutException("simulated");
            default ->
                    new DataAccessResourceFailureException("simulated");
        };

        doThrow(error).when(repository).complete(any(), anyLong(), any());

        assertCode(ErrorCode.SERVICE_UNAVAILABLE,
                () -> change(body(proof), key()));

        unchanged();
        assertNull(revoked(otherSession));
        assertEquals(2, deviceCount(userId));
        assertNull(proofTime(proof, "consumed_at"));

        assertEquals(0, jdbc.queryForObject("""
                select count(*) from email_verifications
                where invalidated_at is not null
                """, Integer.class));

        reset(repository);
        change(body(proof), key());

        assertNotNull(revoked(otherSession));
    }

    @Test
    void verifiedOldPasswordCannotCreateSessionAfterChange() {
        var credential = logins.findCredential(EMAIL).orElseThrow();

        change(body(proof(VerificationPurpose.PASSWORD_CHANGE)), key());

        assertCode(ErrorCode.INVALID_CREDENTIALS,
                () -> sessions.create(credential, EMAIL));
    }

    @Test
    void changeDoesNotAffectAnotherUsersSessionsOrDevices() {
        long other = member("other-member", "other@example.invalid");
        String otherSession = session(other);
        device(other, otherSession);

        change(body(proof(VerificationPurpose.PASSWORD_CHANGE)), key());

        assertNull(revoked(otherSession));
        assertEquals(1, deviceCount(other));
    }

    @Test
    void revokedCurrentSessionCannotReplaySuccessfulChange() {
        var request = body(proof(VerificationPurpose.PASSWORD_CHANGE));
        String key = key();

        change(request, key);
        jdbc.update("update auth_sessions set revoked_at = ?", utc());

        assertCode(ErrorCode.UNAUTHENTICATED,
                () -> change(request, key));
    }

    @Test
    void expiredRequestKeyRemainsConflictAfterMaintenance() {
        var request = body(proof(VerificationPurpose.PASSWORD_CHANGE));
        String key = key();

        change(request, key);
        clock.advance(1);

        jdbc.update("""
                update api_idempotency_requests set expires_at = ?
                where operation = ?
                """, utc(), PasswordChangeRepository.OPERATION);

        new VerificationMaintenance(jdbc, clock).clean();

        assertCode(ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> change(request, key));

        assertEquals(1, rows());
    }

    @Test
    void corruptSuccessMetadataIsNotReplayed() {
        var request = body(proof(VerificationPurpose.PASSWORD_CHANGE));
        String key = key();

        change(request, key);

        jdbc.update("""
                update api_idempotency_requests set response_http_status = 200
                where operation = ?
                """, PasswordChangeRepository.OPERATION);

        assertCode(ErrorCode.SERVICE_UNAVAILABLE,
                () -> change(request, key));
    }

    @Test
    void maximumAuthVersionCanStillChangePasswordWithoutInvalidatingCurrentToken() {
        jdbc.update("update users set auth_version = 2147483647");
        String proof = proof(VerificationPurpose.PASSWORD_CHANGE);

        service.change(body(proof), key(), userId, sessionId, Integer.MAX_VALUE, IP);

        assertEquals(Integer.MAX_VALUE, version());
        protectedStatus(token(userId, sessionId, Integer.MAX_VALUE), 200);
    }

    @Test
    void concurrentSameKeyRequestsPerformOneChange() throws Exception {
        var request = body(proof(VerificationPurpose.PASSWORD_CHANGE));
        String key = key();

        var results = parallel(() -> {
            change(request, key);
            return true;
        });

        assertEquals(8, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, rows());
        assertNull(revoked(sessionId));
    }

    @Test
    void concurrentDifferentKeysCannotConsumeSameProofTwice() throws Exception {
        var request = body(proof(VerificationPurpose.PASSWORD_CHANGE));

        var results = parallel(() -> {
            try {
                change(request, key());
                return true;

            } catch (BusinessException ex) {
                assertEquals(ErrorCode.INVALID_VERIFICATION_TOKEN,
                        ex.getErrorCode());
                return false;
            }
        });

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, rows());
    }

    @Test
    void resetAndChangeCannotBothConsumeOlderProofs() throws Exception {
        var changeBody = body(proof(VerificationPurpose.PASSWORD_CHANGE));
        var resetBody = new PasswordResetRequest(
                proof(VerificationPurpose.PASSWORD_RESET), "ResetPassword789!");

        var start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var changing = pool.submit(() -> {
                start.await();

                try {
                    change(changeBody, key());
                    return true;

                } catch (BusinessException ex) {
                    assertTrue(List.of(
                            ErrorCode.UNAUTHENTICATED,
                            ErrorCode.INVALID_VERIFICATION_TOKEN
                    ).contains(ex.getErrorCode()));

                    return false;
                }
            });

            var resetting = pool.submit(() -> {
                start.await();

                try {
                    resetService.reset(resetBody, key(), IP);
                    return true;

                } catch (BusinessException ex) {
                    assertEquals(ErrorCode.INVALID_VERIFICATION_TOKEN,
                            ex.getErrorCode());

                    return false;
                }
            });

            start.countDown();

            boolean changed = changing.get(30, TimeUnit.SECONDS);
            boolean reset = resetting.get(30, TimeUnit.SECONDS);

            assertNotEquals(changed, reset);
            assertTrue(passwords.matches(
                    changed ? NEW : "ResetPassword789!", hash()));
        }
    }

    @Test
    void confirmationCanFinishBeforeChangeWithoutForeignKeyDeadlock()
            throws Exception {
        var request = body(proof(VerificationPurpose.PASSWORD_CHANGE));

        var issued = issuance.issue(new IssueEmailVerificationRequest(
                EMAIL, VerificationPurpose.PASSWORD_RESET), key(), IP, null);

        String code = mailer.messages.getLast().code();
        var creating = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var locking = new CountDownLatch(1);

        doAnswer(call -> {
            creating.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return call.callRealMethod();
        }).when(confirmations).createProof(any(), anyString(), any());

        doAnswer(call -> {
            locking.countDown();
            return call.callRealMethod();
        }).when(repository).lockVerifications(any());

        try (var pool = Executors.newFixedThreadPool(2)) {
            var confirming = pool.submit(() -> confirmation.verify(
                    issued.verificationId(), code, key(), null));

            assertTrue(creating.await(5, TimeUnit.SECONDS));

            var changing = pool.submit(() -> change(request, key()));

            assertTrue(locking.await(5, TimeUnit.SECONDS));
            assertFalse(changing.isDone());

            release.countDown();

            String resetProof = confirming.get(
                    15, TimeUnit.SECONDS).verificationToken();

            changing.get(15, TimeUnit.SECONDS);

            assertNotNull(proofTime(resetProof, "invalidated_at"));

        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"short1", "passwordonly", "12345678",
            "Has Space123", "Has\nLine123", "Has\u00a0Space123"})
    void malformedPasswordsAreRejectedBeforeHashing(String invalid)
            throws Exception {
        mvc.perform(patch(PATH)
                        .header("Authorization", "Bearer " + currentToken)
                        .header("Idempotency-Key", key())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(
                                new PasswordChangeRequest(
                                        crypto.generateToken(), invalid))))
                .andExpect(status().isBadRequest());

        verify(passwords, never()).encode(anyString());
        assertEquals(0, rows());
    }

    @Test
    void missingAuthenticationHeadersAndUnknownFieldsAreRejected()
            throws Exception {
        String json = mapper.writeValueAsString(body(crypto.generateToken()));

        mvc.perform(patch(PATH)
                        .header("Idempotency-Key", key())
                        .contentType("application/json")
                        .content(json))
                .andExpect(status().isUnauthorized());

        mvc.perform(patch(PATH)
                        .header("Authorization", "Bearer " + currentToken)
                        .contentType("application/json")
                        .content(json))
                .andExpect(status().isBadRequest());

        mvc.perform(patch(PATH)
                        .header("Authorization", "Bearer " + currentToken)
                        .header("Idempotency-Key", "bad-key")
                        .contentType("application/json")
                        .content(json))
                .andExpect(status().isBadRequest());

        for (String bad : List.of(
                "{}",
                json.replace(NEW, "a1".repeat(33)),
                json.replace("verificationToken", "registrationToken"),
                json.substring(0, json.length() - 1) + ",\"userId\":999}"
        )) {
            mvc.perform(patch(PATH)
                            .header("Authorization", "Bearer " + currentToken)
                            .header("Idempotency-Key", key())
                            .contentType("application/json")
                            .content(bad))
                    .andExpect(status().isBadRequest());
        }

        verify(passwords, never()).encode(anyString());
    }

    @Test
    void invalidProofsAreRateLimitedBeforeExpensiveHashing() {
        for (int i = 0; i < 10; i++) {
            assertCode(ErrorCode.INVALID_VERIFICATION_TOKEN,
                    () -> change(body(crypto.generateToken()), key()));
        }

        assertCode(ErrorCode.RATE_LIMITED,
                () -> change(body(crypto.generateToken()), key()));

        verify(passwords, never()).encode(anyString());
    }

    private String proof(VerificationPurpose purpose) {
        return issueAndConfirm(EMAIL, purpose,
                purpose == VerificationPurpose.PASSWORD_CHANGE ? userId : null);
    }

    private String issueAndConfirm(
            String email, VerificationPurpose purpose, Long actor) {
        var issued = issuance.issue(
                new IssueEmailVerificationRequest(email, purpose),
                key(), IP, actor);

        return confirmation.verify(
                issued.verificationId(), mailer.messages.getLast().code(),
                key(), actor).verificationToken();
    }

    private PasswordChangeRequest body(String proof) {
        return new PasswordChangeRequest(proof, NEW);
    }

    private void change(PasswordChangeRequest body, String key) {
        service.change(body, key, userId, sessionId, 0, IP);
    }

    private long member(String nickname, String email) {
        jdbc.update("insert into users(nickname) values (?)", nickname);

        long id = jdbc.queryForObject(
                "select id from users where nickname = ?",
                Long.class, nickname);

        jdbc.update("""
                insert into auth_identities(
                    user_id, provider, provider_subject, email, password_hash)
                values (?, 'EMAIL', ?, ?, ?)
                """, id, email, email, originalHash);

        return id;
    }

    private String session(long owner) {
        String id = key();

        logins.createSession(
                id, owner, clock.instant(), clock.instant().plusSeconds(3600));

        return id;
    }

    private String token(long owner, String sid, int version) {
        return tokens.issue(owner, sid, version,
                clock.instant(), clock.instant().plusSeconds(3600));
    }

    private void device(long owner, String sid) {
        jdbc.update("""
                insert into user_devices(installation_id, user_id, session_id, platform)
                values (?, ?, ?, 'IOS')
                """, key(), owner, sid);
    }

    private int deviceCount(long owner) {
        return jdbc.queryForObject(
                "select count(*) from user_devices where user_id = ?",
                Integer.class, owner);
    }

    private LocalDateTime revoked(String sid) {
        return jdbc.queryForObject(
                "select revoked_at from auth_sessions where id = ?",
                LocalDateTime.class, sid);
    }

    private LocalDateTime proofTime(String proof, String column) {
        assertTrue(List.of("consumed_at", "invalidated_at").contains(column));

        return jdbc.queryForObject(
                "select " + column + " from auth_proofs where token_hash = ?",
                LocalDateTime.class, crypto.tokenHash(proof));
    }

    private String hash() {
        return jdbc.queryForObject("""
                select password_hash from auth_identities
                where user_id = ? and provider = 'EMAIL'
                """, String.class, userId);
    }

    private int version() {
        return jdbc.queryForObject(
                "select auth_version from users where id = ?",
                Integer.class, userId);
    }

    private int rows() {
        return jdbc.queryForObject("""
                select count(*) from api_idempotency_requests where operation = ?
                """, Integer.class, PasswordChangeRepository.OPERATION);
    }

    private void unchanged() {
        assertEquals(originalHash, hash());
        assertEquals(0, version());
        assertNull(revoked(sessionId));
        assertEquals(0, rows());
    }

    private void protectedStatus(String token, int expected) {
        assertDoesNotThrow(() -> mvc.perform(get("/v1/test/current-user")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().is(expected)));
    }

    private void assertCode(
            ErrorCode expected,
            org.junit.jupiter.api.function.Executable work) {
        assertEquals(expected,
                assertThrows(BusinessException.class, work).getErrorCode());
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private LocalDateTime utc() {
        return utc(clock.instant());
    }

    private LocalDateTime utc(Instant time) {
        return LocalDateTime.ofInstant(time, ZoneOffset.UTC);
    }

    private <T> List<T> parallel(
            java.util.concurrent.Callable<T> work) throws Exception {
        var start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return work.call();
                    }))
                    .toList();

            start.countDown();

            var results = new java.util.ArrayList<T>();

            for (var job : jobs) {
                results.add(job.get(30, TimeUnit.SECONDS));
            }

            return results;
        }
    }
}