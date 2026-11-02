package com.dadot.server.domain.auth;

import com.dadot.server.domain.auth.dto.request.EmailLoginRequest;
import com.dadot.server.domain.auth.dto.request.IssueEmailVerificationRequest;
import com.dadot.server.domain.auth.dto.request.PasswordResetRequest;
import com.dadot.server.domain.auth.entity.VerificationPurpose;
import com.dadot.server.domain.auth.repository.EmailLoginRepository;
import com.dadot.server.domain.auth.repository.PasswordResetRepository;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
@Import({
        EmailVerificationIntegrationTest.TestConfig.class,
        EmailSignupIntegrationTest.TestRoutes.class
})
class PasswordResetIntegrationTest {

    static final String PATH = "/v1/auth/password/reset";
    static final String EMAIL = "reset@example.invalid";
    static final String OLD = "OldPassword123!";
    static final String NEW = "NewPassword456!";
    static final String IP = "192.0.2.16";

    @Autowired PasswordResetService service;
    @Autowired EmailVerificationService issuance;
    @Autowired EmailVerificationConfirmationService confirmation;
    @Autowired EmailLoginService login;
    @Autowired EmailLoginSessionService sessions;
    @Autowired EmailLoginRepository logins;
    @Autowired VerificationTokenCrypto crypto;
    @Autowired AccessTokenIssuer tokens;
    @Autowired JwtDecoder decoder;
    @Autowired EmailVerificationIntegrationTest.MutableClock clock;
    @Autowired EmailVerificationIntegrationTest.FakeMailer mailer;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;

    @MockitoSpyBean PasswordResetRepository repository;
    @MockitoSpyBean PasswordEncoder passwords;
    @MockitoSpyBean VerificationConfirmationRepository confirmations;

    long userId;
    String originalHash;
    String oldToken;
    String sessionId;

    @BeforeEach
    void setup() {
        assertEquals(
                "dadot_test_db",
                jdbc.queryForObject("select database()", String.class)
        );

        reset(repository, passwords, confirmations);

        for (String table : List.of(
                "user_devices",
                "api_idempotency_requests",
                "auth_request_guards",
                "auth_proofs",
                "email_verifications",
                "auth_sessions",
                "auth_identities",
                "user_agreements",
                "user_onboarding",
                "users"
        )) {
            jdbc.update("delete from " + table);
        }

        clock.set(Instant.parse("2026-10-24T10:00:00Z"));
        mailer.clear();

        originalHash = passwords.encode(OLD);

        jdbc.update(
                "insert into users(nickname) values ('reset-member')"
        );

        userId = jdbc.queryForObject(
                "select id from users",
                Long.class
        );

        jdbc.update(
                """
                insert into auth_identities(
                    user_id,
                    provider,
                    provider_subject,
                    email,
                    password_hash
                )
                values (?, 'EMAIL', ?, ?, ?)
                """,
                userId,
                EMAIL,
                EMAIL,
                originalHash
        );

        sessionId = UUID.randomUUID().toString();

        logins.createSession(
                sessionId,
                userId,
                clock.instant(),
                clock.instant().plusSeconds(3600)
        );

        oldToken = tokens.issue(
                userId,
                sessionId,
                0,
                clock.instant(),
                clock.instant().plusSeconds(3600)
        );

        jdbc.update(
                """
                insert into user_devices(
                    installation_id,
                    user_id,
                    session_id,
                    platform
                )
                values (?, ?, ?, 'IOS')
                """,
                key(),
                userId,
                sessionId
        );

        clearInvocations(passwords);
    }

    @AfterEach
    void cleanup() {
        reset(repository, passwords, confirmations);
    }

    @Test
    void http204ChangesHashConsumesProofAndInvalidatesEverySession()
            throws Exception {
        String proof = proof(VerificationPurpose.PASSWORD_RESET);
        String second = key();

        logins.createSession(
                second,
                userId,
                clock.instant(),
                clock.instant().plusSeconds(3600)
        );

        var body = request(proof);

        mvc.perform(
                        post(PATH)
                                .contentType("application/json")
                                .header("Idempotency-Key", key())
                                .content(mapper.writeValueAsString(body))
                )
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().doesNotExist("Content-Type"))
                .andExpect(
                        header().string("Cache-Control", "no-store")
                );

        assertNotEquals(originalHash, hash());
        assertTrue(passwords.matches(NEW, hash()));
        assertFalse(passwords.matches(OLD, hash()));

        assertNotNull(
                jdbc.queryForObject(
                        "select consumed_at from auth_proofs",
                        LocalDateTime.class
                )
        );

        assertEquals(1, version());

        assertEquals(
                0,
                jdbc.queryForObject(
                        """
                        select count(*)
                        from auth_sessions
                        where revoked_at is null
                        """,
                        Integer.class
                )
        );

        assertEquals(0, count("user_devices"));

        mvc.perform(
                        get("/v1/test/current-user")
                                .header(
                                        "Authorization",
                                        "Bearer " + oldToken
                                )
                )
                .andExpect(status().isUnauthorized());

        assertCode(
                ErrorCode.INVALID_CREDENTIALS,
                () -> login.login(
                        new EmailLoginRequest(EMAIL, OLD),
                        IP
                )
        );

        var result = login.login(
                new EmailLoginRequest(EMAIL, NEW),
                IP
        );

        assertEquals(
                1,
                ((Number) decoder.decode(
                        result.accessToken()
                ).getClaim("av")).intValue()
        );

        mvc.perform(
                        get("/v1/test/current-user")
                                .header(
                                        "Authorization",
                                        "Bearer " + result.accessToken()
                                )
                )
                .andExpect(status().isOk());
    }

    @Test
    void newHashIsComputedOutsideTransactionAndIsNotStoredAsPlaintext() {
        String proof = proof(VerificationPurpose.PASSWORD_RESET);

        doAnswer(call -> {
            assertFalse(
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            );
            return call.callRealMethod();
        }).when(passwords).encode(NEW);

        service.reset(request(proof), key(), IP);

        verify(passwords, times(1)).encode(NEW);

        var row = jdbc.queryForMap(
                """
                select *
                from api_idempotency_requests
                where operation = ?
                """,
                PATH_OPERATION()
        );

        assertEquals("SUCCEEDED", row.get("status"));

        assertEquals(
                204,
                ((Number) row.get("response_http_status")).intValue()
        );

        assertNull(row.get("response_payload"));
        assertNull(row.get("encrypted_response"));
        assertNull(row.get("response_key_version"));

        assertFalse(row.toString().contains(NEW));
        assertFalse(row.toString().contains(proof));
        assertFalse(request(proof).toString().contains(NEW));
        assertFalse(request(proof).toString().contains(proof));
    }

    @Test
    void sameKeyAfterProofExpiryDoesNotHashAgainOrRevokeNewLogin() {
        var body = request(
                proof(VerificationPurpose.PASSWORD_RESET)
        );
        String key = key();

        service.reset(body, key, IP);

        clock.advance(601);

        var loggedIn = login.login(
                new EmailLoginRequest(EMAIL, NEW),
                IP
        );

        String savedHash = hash();

        service.reset(
                body,
                key.toUpperCase(java.util.Locale.ROOT),
                IP
        );

        assertEquals(savedHash, hash());
        assertEquals(1, version());
        assertEquals(1, resetRows());

        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                        select count(*)
                        from auth_sessions
                        where revoked_at is null
                        """,
                        Integer.class
                )
        );

        verify(passwords, times(1)).encode(NEW);

        assertNotNull(
                decoder.decode(loggedIn.accessToken())
        );
    }

    @Test
    void changedPasswordOrProofWithSameKeyIsConflict() {
        var body = request(
                proof(VerificationPurpose.PASSWORD_RESET)
        );
        String key = key();

        service.reset(body, key, IP);

        assertCode(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> service.reset(
                        new PasswordResetRequest(
                                body.verificationToken(),
                                "Changed789!"
                        ),
                        key,
                        IP
                )
        );

        assertCode(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> service.reset(
                        request(crypto.generateToken()),
                        key,
                        IP
                )
        );

        assertTrue(passwords.matches(NEW, hash()));
        assertEquals(1, version());
    }

    @Test
    void replayOfEarlierResetNeverOverwritesLaterPassword() {
        var first = request(
                proof(VerificationPurpose.PASSWORD_RESET)
        );
        String firstKey = key();

        service.reset(first, firstKey, IP);

        clock.advance(60);

        var second = new PasswordResetRequest(
                proof(VerificationPurpose.PASSWORD_RESET),
                "Newest789!"
        );

        service.reset(second, key(), IP);
        service.reset(first, firstKey, IP);

        assertTrue(passwords.matches("Newest789!", hash()));
        assertEquals(2, version());
        assertEquals(2, resetRows());
    }

    @Test
    void consumedProofCannotBeUsedWithNewKey() {
        var body = request(
                proof(VerificationPurpose.PASSWORD_RESET)
        );

        service.reset(body, key(), IP);

        assertCode(
                ErrorCode.INVALID_VERIFICATION_TOKEN,
                () -> service.reset(body, key(), IP)
        );

        assertEquals(1, version());
    }

    @Test
    void unknownProofDoesNotComputeHash() {
        assertCode(
                ErrorCode.INVALID_VERIFICATION_TOKEN,
                () -> service.reset(
                        request(crypto.generateToken()),
                        key(),
                        IP
                )
        );

        verify(passwords, never()).encode(anyString());

        unchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "expired",
            "consumed",
            "invalidated",
            "future",
            "unverified",
            "sourceInvalidated"
    })
    void invalidProofOrSourceIsRejected(String state) {
        String proof = proof(VerificationPurpose.PASSWORD_RESET);

        switch (state) {
            case "expired" -> clock.advance(600);
            case "consumed" -> jdbc.update(
                    "update auth_proofs set consumed_at = issued_at"
            );
            case "invalidated" -> jdbc.update(
                    "update auth_proofs set invalidated_at = issued_at"
            );
            case "future" -> jdbc.update(
                    """
                    update auth_proofs
                    set issued_at = issued_at + interval 1 second
                    """
            );
            case "unverified" -> jdbc.update(
                    "update email_verifications set verified_at = null"
            );
            case "sourceInvalidated" -> jdbc.update(
                    """
                    update email_verifications
                    set invalidated_at = sent_at
                    """
            );
        }

        assertCode(
                ErrorCode.INVALID_VERIFICATION_TOKEN,
                () -> service.reset(request(proof), key(), IP)
        );

        verify(passwords, never()).encode(anyString());

        unchanged();
    }

    @Test
    void signupAndPasswordChangeProofsAreRejected() {
        var issued = issuance.issue(
                new IssueEmailVerificationRequest(
                        "new@example.invalid",
                        VerificationPurpose.SIGN_UP
                ),
                key(),
                "192.0.2.20",
                null
        );

        String signupProof = confirmation.verify(
                issued.verificationId(),
                mailer.messages.getLast().code(),
                key(),
                null
        ).verificationToken();

        String changeProof =
                proof(VerificationPurpose.PASSWORD_CHANGE);

        for (String proof : List.of(signupProof, changeProof)) {
            assertCode(
                    ErrorCode.INVALID_VERIFICATION_TOKEN,
                    () -> service.reset(request(proof), key(), IP)
            );
        }

        verify(passwords, never()).encode(anyString());

        unchanged();
    }

    @Test
    void socialSignupProofCannotResetEmailPassword() {
        String proof = crypto.generateToken();

        jdbc.update(
                """
                insert into auth_proofs(
                    id,
                    purpose,
                    provider,
                    provider_subject,
                    token_hash,
                    issued_at,
                    expires_at
                )
                values (
                    ?,
                    'SIGN_UP',
                    'APPLE',
                    'apple-user',
                    ?,
                    ?,
                    ?
                )
                """,
                key(),
                crypto.tokenHash(proof),
                utc(),
                utc(clock.instant().plusSeconds(600))
        );

        assertCode(
                ErrorCode.INVALID_VERIFICATION_TOKEN,
                () -> service.reset(request(proof), key(), IP)
        );

        unchanged();
    }

    @Test
    void unknownEmailCannotGetResetProofOrCreateAccount() {
        var issued = issuance.issue(
                new IssueEmailVerificationRequest(
                        "unknown@example.invalid",
                        VerificationPurpose.PASSWORD_RESET
                ),
                key(),
                IP,
                null
        );

        assertCode(
                ErrorCode.INVALID_VERIFICATION_CODE,
                () -> confirmation.verify(
                        issued.verificationId(),
                        mailer.messages.getLast().code(),
                        key(),
                        null
                )
        );

        assertEquals(1, count("users"));
        assertEquals(0, count("auth_proofs"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "account",
            "identity",
            "email"
    })
    void changedAccountOrIdentityCannotUseOldProof(
            String change
    ) {
        String proof = proof(VerificationPurpose.PASSWORD_RESET);

        switch (change) {
            case "account" -> jdbc.update(
                    "update users set status = 'DELETING'"
            );
            case "identity" -> jdbc.update(
                    "delete from auth_identities"
            );
            case "email" -> jdbc.update(
                    """
                    update auth_identities
                    set email = 'other@example.invalid',
                        provider_subject = 'other@example.invalid'
                    """
            );
        }

        assertCode(
                ErrorCode.INVALID_VERIFICATION_TOKEN,
                () -> service.reset(request(proof), key(), IP)
        );

        assertEquals(0, version());
        assertEquals(0, resetRows());
    }

    @Test
    void successfulResetInvalidatesOtherResetAndChangeProofs() {
        String reset = proof(VerificationPurpose.PASSWORD_RESET);
        String change = proof(VerificationPurpose.PASSWORD_CHANGE);

        service.reset(request(reset), key(), IP);

        assertNotNull(
                jdbc.queryForObject(
                        """
                        select invalidated_at
                        from auth_proofs
                        where token_hash = ?
                        """,
                        LocalDateTime.class,
                        crypto.tokenHash(change)
                )
        );

        assertEquals(
                0,
                jdbc.queryForObject(
                        """
                        select count(*)
                        from email_verifications
                        where invalidated_at is null
                        """,
                        Integer.class
                )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "database",
            "transactionStart",
            "transactionTimeout"
    })
    void databaseFailureRollsBackPasswordProofSessionsDevicesAndRequest(
            String failure
    ) {
        String proof = proof(VerificationPurpose.PASSWORD_RESET);

        RuntimeException error = switch (failure) {
            case "transactionStart" ->
                    new org.springframework.transaction.CannotCreateTransactionException(
                            "simulated"
                    );
            case "transactionTimeout" ->
                    new org.springframework.transaction.TransactionTimedOutException(
                            "simulated"
                    );
            default ->
                    new DataAccessResourceFailureException(
                            "simulated"
                    );
        };

        doThrow(error)
                .when(repository)
                .complete(any(), anyLong(), any());

        assertCode(
                ErrorCode.SERVICE_UNAVAILABLE,
                () -> service.reset(request(proof), key(), IP)
        );

        unchanged();

        assertNull(
                jdbc.queryForObject(
                        "select consumed_at from auth_proofs",
                        LocalDateTime.class
                )
        );
        assertNull(
                jdbc.queryForObject(
                        "select invalidated_at from email_verifications",
                        LocalDateTime.class
                )
        );

        assertEquals(1, count("user_devices"));

        reset(repository);

        service.reset(request(proof), key(), IP);

        assertTrue(passwords.matches(NEW, hash()));
    }

    @Test
    void proofExpiringDuringHashCalculationIsRejected() {
        String proof = proof(VerificationPurpose.PASSWORD_RESET);

        doAnswer(call -> {
            clock.advance(600);
            return call.callRealMethod();
        }).when(passwords).encode(NEW);

        assertCode(
                ErrorCode.INVALID_VERIFICATION_TOKEN,
                () -> service.reset(request(proof), key(), IP)
        );

        unchanged();
    }

    @Test
    void resendDuringHashCalculationInvalidatesPreparedProof() {
        String proof = proof(VerificationPurpose.PASSWORD_RESET);

        doAnswer(call -> {
            clock.advance(60);

            issuance.issue(
                    new IssueEmailVerificationRequest(
                            EMAIL,
                            VerificationPurpose.PASSWORD_RESET
                    ),
                    key(),
                    IP,
                    null
            );

            return call.callRealMethod();
        }).when(passwords).encode(NEW);

        assertCode(
                ErrorCode.INVALID_VERIFICATION_TOKEN,
                () -> service.reset(request(proof), key(), IP)
        );

        unchanged();
    }

    @Test
    void loginVerifiedBeforeResetCannotCreateSessionAfterReset() {
        var credential = logins.findCredential(EMAIL)
                .orElseThrow();

        service.reset(
                request(proof(VerificationPurpose.PASSWORD_RESET)),
                key(),
                IP
        );

        assertCode(
                ErrorCode.INVALID_CREDENTIALS,
                () -> sessions.create(credential, EMAIL)
        );

        assertEquals(
                0,
                jdbc.queryForObject(
                        """
                        select count(*)
                        from auth_sessions
                        where revoked_at is null
                        """,
                        Integer.class
                )
        );
    }

    @Test
    void resetDoesNotAffectAnotherUser() {
        jdbc.update(
                "insert into users(nickname) values ('other-member')"
        );

        long otherId = jdbc.queryForObject(
                "select id from users where nickname = 'other-member'",
                Long.class
        );

        String otherSession = key();

        logins.createSession(
                otherSession,
                otherId,
                clock.instant(),
                clock.instant().plusSeconds(3600)
        );

        jdbc.update(
                """
                insert into user_devices(
                    installation_id,
                    user_id,
                    session_id,
                    platform
                )
                values (?, ?, ?, 'IOS')
                """,
                key(),
                otherId,
                otherSession
        );

        service.reset(
                request(proof(VerificationPurpose.PASSWORD_RESET)),
                key(),
                IP
        );

        assertEquals(
                0,
                jdbc.queryForObject(
                        "select auth_version from users where id = ?",
                        Integer.class,
                        otherId
                )
        );

        assertNull(
                jdbc.queryForObject(
                        "select revoked_at from auth_sessions where id = ?",
                        LocalDateTime.class,
                        otherSession
                )
        );

        assertEquals(
                1,
                jdbc.queryForObject(
                        "select count(*) from user_devices where user_id = ?",
                        Integer.class,
                        otherId
                )
        );
    }

    @Test
    void completedRequestIsNotReplayedForDeletingAccount() {
        var body = request(
                proof(VerificationPurpose.PASSWORD_RESET)
        );
        String key = key();

        service.reset(body, key, IP);

        jdbc.update("update users set status = 'DELETING'");

        assertCode(
                ErrorCode.INVALID_VERIFICATION_TOKEN,
                () -> service.reset(body, key, IP)
        );
    }

    @Test
    void expiredIdempotencyKeyRemainsConflictAfterMaintenance() {
        var body = request(
                proof(VerificationPurpose.PASSWORD_RESET)
        );
        String key = key();

        service.reset(body, key, IP);

        clock.advance(86_400);

        new VerificationMaintenance(jdbc, clock).clean();

        assertEquals(1, resetRows());

        assertCode(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> service.reset(body, key, IP)
        );
    }

    @Test
    void corruptedSuccessMetadataIsNotTreatedAsSuccess() {
        var body = request(
                proof(VerificationPurpose.PASSWORD_RESET)
        );
        String key = key();

        service.reset(body, key, IP);

        jdbc.update(
                """
                update api_idempotency_requests
                set response_http_status = 200
                where operation = ?
                """,
                PATH_OPERATION()
        );

        assertCode(
                ErrorCode.SERVICE_UNAVAILABLE,
                () -> service.reset(body, key, IP)
        );
    }

    @Test
    void exhaustedAuthVersionDoesNotPartiallyChangeAccount() {
        String proof = proof(VerificationPurpose.PASSWORD_RESET);

        jdbc.update(
                "update users set auth_version = 2147483647"
        );

        assertCode(
                ErrorCode.SERVICE_UNAVAILABLE,
                () -> service.reset(request(proof), key(), IP)
        );

        assertEquals(originalHash, hash());

        assertNull(
                jdbc.queryForObject(
                        "select consumed_at from auth_proofs",
                        LocalDateTime.class
                )
        );

        assertEquals(0, resetRows());
    }

    @Test
    void concurrentSameKeyRequestsChangePasswordOnce()
            throws Exception {
        var body = request(
                proof(VerificationPurpose.PASSWORD_RESET)
        );
        String key = key();

        var results = parallel(() -> {
            service.reset(body, key, IP);
            return true;
        });

        assertEquals(
                8,
                results.stream()
                        .filter(Boolean::booleanValue)
                        .count()
        );

        assertEquals(1, version());
        assertEquals(1, resetRows());
    }

    @Test
    void concurrentDifferentKeysCannotReuseOneProof()
            throws Exception {
        var body = request(
                proof(VerificationPurpose.PASSWORD_RESET)
        );

        var results = parallel(() -> {
            try {
                service.reset(body, key(), IP);
                return true;

            } catch (BusinessException ex) {
                assertEquals(
                        ErrorCode.INVALID_VERIFICATION_TOKEN,
                        ex.getErrorCode()
                );
                return false;
            }
        });

        assertEquals(
                1,
                results.stream()
                        .filter(Boolean::booleanValue)
                        .count()
        );

        assertEquals(1, version());
        assertEquals(1, resetRows());
    }

    @Test
    void concurrentConfirmationFinishesBeforeResetInvalidatesItsProof()
            throws Exception {
        var body = request(
                proof(VerificationPurpose.PASSWORD_RESET)
        );

        var issued = issuance.issue(
                new IssueEmailVerificationRequest(
                        EMAIL,
                        VerificationPurpose.PASSWORD_CHANGE
                ),
                key(),
                IP,
                userId
        );

        String code = mailer.messages.getLast().code();

        var creating = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var resetLocking = new CountDownLatch(1);

        doAnswer(call -> {
            resetLocking.countDown();
            return call.callRealMethod();
        }).when(repository).lockVerifications(any());

        doAnswer(call -> {
            creating.countDown();

            assertTrue(
                    release.await(10, TimeUnit.SECONDS)
            );

            return call.callRealMethod();
        }).when(confirmations)
                .createProof(any(), anyString(), any());

        try (var pool = Executors.newFixedThreadPool(2)) {
            var confirming = pool.submit(
                    () -> confirmation.verify(
                            issued.verificationId(),
                            code,
                            key(),
                            userId
                    )
            );

            assertTrue(
                    creating.await(5, TimeUnit.SECONDS)
            );

            var resetting = pool.submit(
                    () -> service.reset(body, key(), IP)
            );

            assertTrue(
                    resetLocking.await(5, TimeUnit.SECONDS)
            );
            assertFalse(resetting.isDone());

            release.countDown();

            var changeProof = confirming.get(
                    15,
                    TimeUnit.SECONDS
            ).verificationToken();

            resetting.get(15, TimeUnit.SECONDS);

            assertNotNull(
                    jdbc.queryForObject(
                            """
                            select invalidated_at
                            from auth_proofs
                            where token_hash = ?
                            """,
                            LocalDateTime.class,
                            crypto.tokenHash(changeProof)
                    )
            );

        } finally {
            release.countDown();
        }

        assertTrue(passwords.matches(NEW, hash()));
    }

    @Test
    void ipLimitAppliesToFailedProofsWithoutBlockingOtherIps() {
        for (int i = 0; i < 10; i++) {
            assertCode(
                    ErrorCode.INVALID_VERIFICATION_TOKEN,
                    () -> service.reset(
                            request(crypto.generateToken()),
                            key(),
                            IP
                    )
            );
        }

        assertCode(
                ErrorCode.RATE_LIMITED,
                () -> service.reset(
                        request(crypto.generateToken()),
                        key(),
                        IP
                )
        );

        assertCode(
                ErrorCode.INVALID_VERIFICATION_TOKEN,
                () -> service.reset(
                        request(crypto.generateToken()),
                        key(),
                        "192.0.2.17"
                )
        );

        verify(passwords, never()).encode(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "short1",
            "passwordonly",
            "12345678",
            "Has Space123",
            "Has\nLine123",
            "Has\u00a0Space123"
    })
    void invalidPasswordsAreRejectedBeforeServiceWork(
            String invalid
    ) throws Exception {
        var body = new PasswordResetRequest(
                crypto.generateToken(),
                invalid
        );

        mvc.perform(
                        post(PATH)
                                .header("Idempotency-Key", key())
                                .contentType("application/json")
                                .content(mapper.writeValueAsString(body))
                )
                .andExpect(status().isBadRequest());

        assertEquals(0, count("auth_request_guards"));

        verify(passwords, never()).encode(anyString());
    }

    @Test
    void missingMalformedHeadersTokenAndUnknownFieldsAreRejected()
            throws Exception {
        String json = mapper.writeValueAsString(
                request(crypto.generateToken())
        );

        mvc.perform(
                        post(PATH)
                                .contentType("application/json")
                                .content(json)
                )
                .andExpect(status().isBadRequest());

        mvc.perform(
                        post(PATH)
                                .header(
                                        "Idempotency-Key",
                                        "not-uuid"
                                )
                                .contentType("application/json")
                                .content(json)
                )
                .andExpect(status().isBadRequest());

        for (String bad : List.of(
                "{}",
                json.replace(NEW, "a1".repeat(33)),
                json.replace(
                        "\"verificationToken\"",
                        "\"registrationToken\""
                ),
                json.substring(0, json.length() - 1)
                        + ",\"email\":\"fake@example.invalid\"}"
        )) {
            mvc.perform(
                            post(PATH)
                                    .header(
                                            "Idempotency-Key",
                                            key()
                                    )
                                    .contentType("application/json")
                                    .content(bad)
                    )
                    .andExpect(status().isBadRequest());
        }

        verify(passwords, never()).encode(anyString());

        assertEquals(0, resetRows());
    }

    private String proof(VerificationPurpose purpose) {
        Long actor =
                purpose == VerificationPurpose.PASSWORD_CHANGE
                        ? userId
                        : null;

        var issued = issuance.issue(
                new IssueEmailVerificationRequest(
                        EMAIL,
                        purpose
                ),
                key(),
                IP,
                actor
        );

        return confirmation.verify(
                issued.verificationId(),
                mailer.messages.getLast().code(),
                key(),
                actor
        ).verificationToken();
    }

    private PasswordResetRequest request(String token) {
        return new PasswordResetRequest(token, NEW);
    }

    private String hash() {
        return jdbc.queryForObject(
                """
                select password_hash
                from auth_identities
                where user_id = ?
                """,
                String.class,
                userId
        );
    }

    private int version() {
        return jdbc.queryForObject(
                "select auth_version from users where id = ?",
                Integer.class,
                userId
        );
    }

    private int count(String table) {
        return jdbc.queryForObject(
                "select count(*) from " + table,
                Integer.class
        );
    }

    private int resetRows() {
        return jdbc.queryForObject(
                """
                select count(*)
                from api_idempotency_requests
                where operation = ?
                """,
                Integer.class,
                PATH_OPERATION()
        );
    }

    private String PATH_OPERATION() {
        return PasswordResetRepository.OPERATION;
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private LocalDateTime utc() {
        return utc(clock.instant());
    }

    private LocalDateTime utc(Instant time) {
        return LocalDateTime.ofInstant(
                time,
                ZoneOffset.UTC
        );
    }

    private void unchanged() {
        assertEquals(originalHash, hash());
        assertEquals(0, version());

        assertNull(
                jdbc.queryForObject(
                        "select revoked_at from auth_sessions where id = ?",
                        LocalDateTime.class,
                        sessionId
                )
        );

        assertEquals(0, resetRows());
    }

    private void assertCode(
            ErrorCode code,
            org.junit.jupiter.api.function.Executable work
    ) {
        assertEquals(
                code,
                assertThrows(
                        BusinessException.class,
                        work
                ).getErrorCode()
        );
    }

    private <T> List<T> parallel(
            java.util.concurrent.Callable<T> work
    ) throws Exception {
        var start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        return work.call();
                    }))
                    .toList();

            start.countDown();

            var result = new java.util.ArrayList<T>();

            for (var job : jobs) {
                result.add(
                        job.get(30, TimeUnit.SECONDS)
                );
            }

            return result;
        }
    }
}