package com.dadot.server.domain.auth;

import com.dadot.server.domain.auth.dto.request.KakaoLoginRequest;
import com.dadot.server.domain.auth.dto.request.SocialSignupRequest;
import com.dadot.server.domain.auth.dto.response.SocialSignupResponse;
import com.dadot.server.domain.auth.repository.AuthRequestGuardRepository;
import com.dadot.server.domain.auth.repository.SocialSignupRequestStore;
import com.dadot.server.domain.auth.service.KakaoLoginService;
import com.dadot.server.domain.auth.service.SocialSignupService;
import com.dadot.server.domain.auth.service.VerificationMaintenance;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.AccessTokenIssuer;
import com.dadot.server.global.security.SessionJwtAuthenticationConverter;
import com.dadot.server.global.security.UserPrincipal;
import com.dadot.server.infrastructure.kakao.KakaoClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmailVerificationIntegrationTest.TestConfig.class)
class SocialSignupIntegrationTest {

    private static final String PATH =
            "/v1/auth/social/signup";

    private static final String KAKAO_TOKEN =
            "TEST_SOCIAL_SIGNUP_KAKAO_TOKEN";

    private static final String SUBJECT = "12345";
    private static final String IP = "192.0.2.20";

    @Autowired
    SocialSignupService signup;

    @Autowired
    KakaoLoginService login;

    @Autowired
    VerificationTokenCrypto crypto;

    @Autowired
    EmailVerificationIntegrationTest.MutableClock clock;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JwtDecoder decoder;

    @Autowired
    SessionJwtAuthenticationConverter authentication;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper mapper;

    @Autowired
    PlatformTransactionManager transactionManager;

    @MockitoBean
    KakaoClient kakao;

    @MockitoSpyBean
    SocialSignupRequestStore requests;

    @MockitoSpyBean
    AccessTokenIssuer tokens;

    @MockitoSpyBean
    AuthRequestGuardRepository guards;

    private long termsId;

    @BeforeEach
    void setUp() {
        assertEquals(
                "dadot_test_db",
                jdbc.queryForObject(
                        "select database()",
                        String.class
                )
        );

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
                "users",
                "service_terms"
        )) {
            jdbc.update("delete from " + table);
        }

        clock.set(
                Instant.parse(
                        "2026-10-24T00:00:00.123456Z"
                )
        );

        reset(kakao, requests, tokens, guards);

        when(kakao.verify(KAKAO_TOKEN))
                .thenAnswer(invocation ->
                        new KakaoClient.Verified(
                                SUBJECT,
                                clock.instant().plusSeconds(3600)
                        )
                );

        termsId = publish(
                "v1",
                clock.instant().minusSeconds(3600),
                true
        );
    }

    @AfterEach
    void tearDown() {
        reset(kakao, requests, tokens, guards);

        jdbc.update("delete from user_devices");
        jdbc.update("delete from user_agreements");
        jdbc.update("delete from service_terms");
    }

    @Test
    void kakaoProofCreatesMemberConsentIdentityOnboardingAndUsableSession() {
        String proof = kakaoProof();

        var result = submit(
                request(proof, termsId),
                key()
        );

        for (String table : List.of(
                "users",
                "auth_identities",
                "user_agreements",
                "user_onboarding",
                "auth_sessions"
        )) {
            assertEquals(1, count(table), table);
        }

        assertEquals("Bearer", result.tokenType());
        assertFalse(result.onboardingCompleted());

        assertEquals(
                crypto.tokenHash(proof),
                jdbc.queryForObject(
                        "select token_hash from auth_proofs",
                        String.class
                )
        );

        var identity = jdbc.queryForMap(
                "select * from auth_identities"
        );

        assertEquals(
                "KAKAO",
                identity.get("provider")
        );
        assertEquals(
                SUBJECT,
                identity.get("provider_subject")
        );
        assertNull(identity.get("email"));
        assertNull(identity.get("password_hash"));

        assertEquals(
                termsId,
                jdbc.queryForObject(
                        "select terms_id from user_agreements",
                        Long.class
                )
        );

        assertEquals(
                utc(clock.instant()),
                jdbc.queryForObject(
                        "select agreed_at from user_agreements",
                        LocalDateTime.class
                )
        );

        assertNotNull(
                jdbc.queryForObject(
                        "select consumed_at from auth_proofs",
                        LocalDateTime.class
                )
        );

        var jwt = decoder.decode(
                result.accessToken()
        );

        assertEquals(
                3600,
                result.expiresAt().getEpochSecond()
                        - jwt.getIssuedAt().getEpochSecond()
        );

        assertEquals(
                new UserPrincipal(result.userId()),
                authentication.convert(jwt).getPrincipal()
        );

        var row = jdbc.queryForMap(
                """
                select *
                from api_idempotency_requests
                where operation = ?
                """,
                SocialSignupRequestStore.OPERATION
        );

        assertNull(row.get("response_payload"));

        assertFalse(
                row.get("encrypted_response")
                        .toString()
                        .contains(result.accessToken())
        );

        assertFalse(
                row.get("encrypted_response")
                        .toString()
                        .contains(proof)
        );

        assertTrue(
                jdbc.queryForObject(
                        "select nickname from users",
                        String.class
                ).startsWith("다닷")
        );
    }

    @Test
    void httpSignupReturns201NoStoreAndTokenCanLogout()
            throws Exception {

        String proof = kakaoProof();

        String body = mvc.perform(
                        post(PATH)
                                .header(
                                        "Idempotency-Key",
                                        key()
                                )
                                .contentType("application/json")
                                .content(
                                        mapper.writeValueAsString(
                                                request(proof, termsId)
                                        )
                                )
                )
                .andExpect(status().isCreated())
                .andExpect(
                        header().string(
                                "Cache-Control",
                                "no-store"
                        )
                )
                .andExpect(
                        jsonPath("$.success").value(true)
                )
                .andExpect(
                        jsonPath("$.data.onboardingCompleted")
                                .value(false)
                )
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = mapper.readTree(body)
                .get("data")
                .get("accessToken")
                .asString();

        mvc.perform(
                        post("/v1/auth/logout")
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                )
                )
                .andExpect(status().isNoContent());

        mvc.perform(
                        post("/v1/auth/logout")
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                )
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nextKakaoLoginReturnsExistingUserInsteadOfAnotherProof() {
        var created = submit(
                request(kakaoProof(), termsId),
                key()
        );

        var result = login.login(
                new KakaoLoginRequest(KAKAO_TOKEN),
                key(),
                IP
        );

        assertEquals(
                created.userId(),
                result.userId()
        );

        assertEquals(
                "LOGIN_COMPLETED",
                result.status().name()
        );

        assertEquals(1, count("users"));
        assertEquals(1, count("auth_proofs"));
        assertEquals(2, count("auth_sessions"));
    }

    @Test
    void sameRequestReplaysAfterProofWasConsumedAndExpired() {
        var request = request(
                kakaoProof(),
                termsId
        );

        String key = key();
        var first = submit(request, key);

        clock.advance(601);

        assertEquals(
                first,
                submit(request, key.toUpperCase())
        );

        assertEquals(1, count("users"));
        assertEquals(1, count("auth_sessions"));
        assertEquals(1, signupRequests());
    }

    @Test
    void consumedProofWithAnotherKeyIsRejected() {
        var request = request(
                kakaoProof(),
                termsId
        );

        submit(request, key());

        assertCode(
                ErrorCode.INVALID_REGISTRATION_TOKEN,
                () -> submit(request, key())
        );

        assertEquals(1, count("users"));
    }

    @Test
    void successInvalidatesOtherProofsAndAuth05CannotReplayThem() {
        String auth05Key = key();

        var firstProof = login.login(
                new KakaoLoginRequest(KAKAO_TOKEN),
                auth05Key,
                IP
        );

        String secondProof = kakaoProof();

        submit(
                request(
                        firstProof.registrationToken(),
                        termsId
                ),
                key()
        );

        assertNotNull(
                jdbc.queryForObject(
                        """
                        select invalidated_at
                        from auth_proofs
                        where token_hash = ?
                        """,
                        LocalDateTime.class,
                        crypto.tokenHash(secondProof)
                )
        );

        assertCode(
                ErrorCode.INVALID_REGISTRATION_TOKEN,
                () -> submit(
                        request(secondProof, termsId),
                        key()
                )
        );

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> login.login(
                        new KakaoLoginRequest(KAKAO_TOKEN),
                        auth05Key,
                        IP
                )
        );
    }

    @Test
    void unknownProofIsRejected() {
        assertCode(
                ErrorCode.INVALID_REGISTRATION_TOKEN,
                () -> submit(
                        request(
                                crypto.generateToken(),
                                termsId
                        ),
                        key()
                )
        );

        assertEquals(0, count("users"));
        assertEquals(0, signupRequests());
    }

    @Test
    void expiredInvalidatedConsumedAndFutureProofsAreRejected() {
        for (String sql : List.of(
                "update auth_proofs set expires_at = issued_at + interval 1 second",
                "update auth_proofs set invalidated_at = issued_at",
                "update auth_proofs set consumed_at = issued_at",
                "update auth_proofs set issued_at = issued_at + interval 1 second"
        )) {
            String proof = kakaoProof();

            jdbc.update(
                    sql + " where token_hash = ?",
                    crypto.tokenHash(proof)
            );

            if (sql.contains("expires_at =")) {
                clock.advance(1);
            }

            assertCode(
                    ErrorCode.INVALID_REGISTRATION_TOKEN,
                    () -> submit(
                            request(proof, termsId),
                            key()
                    )
            );
        }

        assertEquals(0, count("users"));
    }

    @Test
    void emailVerificationProofCannotBeUsedForSocialSignup() {
        String proof = crypto.generateToken();
        String verificationId =
                UUID.randomUUID().toString();

        jdbc.update(
                """
                insert into email_verifications(
                    id,
                    email,
                    purpose,
                    code_digest,
                    sent_at,
                    expires_at,
                    verified_at
                )
                values (
                    ?,
                    'email@example.invalid',
                    'SIGN_UP',
                    ?, ?, ?, ?
                )
                """,
                verificationId,
                "a".repeat(64),
                utc(clock.instant()),
                utc(clock.instant().plusSeconds(600)),
                utc(clock.instant())
        );

        jdbc.update(
                """
                insert into auth_proofs(
                    id,
                    verification_id,
                    provider,
                    purpose,
                    subject_email,
                    token_hash,
                    issued_at,
                    expires_at
                )
                values (
                    ?, ?,
                    'EMAIL',
                    'SIGN_UP',
                    'email@example.invalid',
                    ?, ?, ?
                )
                """,
                UUID.randomUUID().toString(),
                verificationId,
                crypto.tokenHash(proof),
                utc(clock.instant()),
                utc(clock.instant().plusSeconds(600))
        );

        assertCode(
                ErrorCode.INVALID_REGISTRATION_TOKEN,
                () -> submit(
                        request(proof, termsId),
                        key()
                )
        );

        assertEquals(0, count("users"));
    }

    @Test
    void trustedAppleProofUsesSameSignupWithoutClientSuppliedIdentity() {
        String proof = socialProof(
                "APPLE",
                "apple-user",
                "verified@example.invalid"
        );

        var result = submit(
                request(proof, termsId),
                key()
        );

        assertEquals(
                "APPLE",
                jdbc.queryForObject(
                        "select provider from auth_identities",
                        String.class
                )
        );

        assertEquals(
                "apple-user",
                jdbc.queryForObject(
                        "select provider_subject from auth_identities",
                        String.class
                )
        );

        assertEquals(
                "verified@example.invalid",
                jdbc.queryForObject(
                        "select email from auth_identities",
                        String.class
                )
        );

        assertNotNull(
                authentication.convert(
                        decoder.decode(result.accessToken())
                )
        );
    }

    @Test
    void sameSubjectOnAnotherProviderDoesNotBlockKakaoSignup() {
        String apple = socialProof(
                "APPLE",
                SUBJECT,
                null
        );

        submit(request(apple, termsId), key());
        submit(request(kakaoProof(), termsId), key());

        assertEquals(2, count("users"));
        assertEquals(2, count("auth_identities"));
    }

    @Test
    void existingIdentityIsConflictAndProofIsNotConsumed() {
        String proof = kakaoProof();

        jdbc.update(
                """
                insert into users(nickname)
                values ('기존회원')
                """
        );

        long id = jdbc.queryForObject(
                "select max(id) from users",
                Long.class
        );

        jdbc.update(
                """
                insert into auth_identities(
                    user_id,
                    provider,
                    provider_subject
                )
                values (?, 'KAKAO', ?)
                """,
                id,
                SUBJECT
        );

        assertCode(
                ErrorCode.SOCIAL_ACCOUNT_ALREADY_REGISTERED,
                () -> submit(
                        request(proof, termsId),
                        key()
                )
        );

        assertEquals(1, count("users"));

        assertNull(
                jdbc.queryForObject(
                        "select consumed_at from auth_proofs",
                        LocalDateTime.class
                )
        );
    }

    @Test
    void sameEmailDoesNotMergeDifferentProviderAccounts() {
        String first = socialProof(
                "APPLE",
                "apple-user",
                "same@example.invalid"
        );

        var apple = submit(
                request(first, termsId),
                key()
        );

        String second = socialProof(
                "KAKAO",
                SUBJECT,
                "same@example.invalid"
        );

        var kakaoResult = submit(
                request(second, termsId),
                key()
        );

        assertNotEquals(
                apple.userId(),
                kakaoResult.userId()
        );

        assertEquals(2, count("users"));
    }

    @Test
    void emptyConsentDoesNotConsumeProofAndFailureStaysBoundToBody() {
        String proof = kakaoProof();
        String key = key();

        var empty = request(proof);

        assertCode(
                ErrorCode.TERMS_NOT_ACCEPTED,
                () -> submit(empty, key)
        );

        assertEquals(0, count("users"));

        assertNull(
                jdbc.queryForObject(
                        "select consumed_at from auth_proofs",
                        LocalDateTime.class
                )
        );

        assertCode(
                ErrorCode.TERMS_NOT_ACCEPTED,
                () -> submit(empty, key)
        );

        assertCode(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> submit(
                        request(proof, termsId),
                        key
                )
        );

        assertNotNull(
                submit(
                        request(proof, termsId),
                        key()
                ).accessToken()
        );
    }

    @Test
    void staleUnknownOrExtraTermsAreRejectedWithoutSignup() {
        long newer = publish(
                "v2",
                clock.instant(),
                true
        );

        String proof = kakaoProof();

        for (List<Long> ids : List.of(
                List.of(termsId),
                List.of(Long.MAX_VALUE),
                List.of(termsId, newer)
        )) {
            assertCode(
                    ErrorCode.TERMS_CHANGED,
                    () -> submit(
                            new SocialSignupRequest(
                                    proof,
                                    ids
                            ),
                            key()
                    )
            );
        }

        assertEquals(0, count("users"));

        assertNotNull(
                submit(
                        request(proof, newer),
                        key()
                ).accessToken()
        );
    }

    @Test
    void noPublishedTermsReturns503WithoutConsumingProof() {
        jdbc.update("delete from service_terms");

        String proof = kakaoProof();

        assertCode(
                ErrorCode.SIGNUP_TERMS_UNAVAILABLE,
                () -> submit(
                        request(proof, termsId),
                        key()
                )
        );

        assertEquals(0, count("users"));

        assertNull(
                jdbc.queryForObject(
                        "select consumed_at from auth_proofs",
                        LocalDateTime.class
                )
        );
    }

    @Test
    void futureAndDraftTermsAreNotRequired() {
        publish(
                "future",
                clock.instant().plusSeconds(3600),
                true
        );

        publish(
                "draft",
                clock.instant().minusSeconds(60),
                false
        );

        assertNotNull(
                submit(
                        request(kakaoProof(), termsId),
                        key()
                ).accessToken()
        );
    }

    @Test
    void proofIsRecheckedAfterWaitingForTermsValidation()
            throws Exception {

        String proof = kakaoProof();

        String termsLock =
                "db99e235c84abf94e7c3d11b65b25a90606d982cb8dce3ac9eee3e920c18d969";

        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (var executor =
                     Executors.newFixedThreadPool(2)) {

            var blocker = executor.submit(() ->
                    new TransactionTemplate(transactionManager)
                            .executeWithoutResult(status -> {
                                guards.lock(
                                        termsLock,
                                        clock.instant().plusSeconds(86400)
                                );

                                held.countDown();

                                try {
                                    if (!release.await(
                                            10,
                                            TimeUnit.SECONDS
                                    )) {
                                        throw new AssertionError(
                                                "terms lock timeout"
                                        );
                                    }
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(ex);
                                }
                            })
            );

            try {
                assertTrue(
                        held.await(10, TimeUnit.SECONDS)
                );

                doAnswer(invocation -> {
                    waiting.countDown();
                    return invocation.callRealMethod();
                }).when(guards).lock(
                        eq(termsLock),
                        any(Instant.class)
                );

                var attempt = executor.submit(() -> {
                    try {
                        submit(
                                request(proof, termsId),
                                key()
                        );

                        return (ErrorCode) null;

                    } catch (BusinessException ex) {
                        return ex.getErrorCode();
                    }
                });

                assertTrue(
                        waiting.await(10, TimeUnit.SECONDS)
                );

                clock.advance(601);
                release.countDown();

                assertEquals(
                        ErrorCode.INVALID_REGISTRATION_TOKEN,
                        attempt.get(15, TimeUnit.SECONDS)
                );

                blocker.get(15, TimeUnit.SECONDS);

            } finally {
                release.countDown();
            }
        }

        assertEquals(0, count("users"));
        assertEquals(0, signupRequests());
    }

    @Test
    void changedProofOrTermsWithSameKeyIsConflict() {
        String proof = kakaoProof();

        var request = request(proof, termsId);
        String key = key();

        submit(request, key);

        assertCode(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> submit(
                        request(
                                crypto.generateToken(),
                                termsId
                        ),
                        key
                )
        );

        assertCode(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> submit(
                        request(proof),
                        key
                )
        );
    }

    @Test
    void logoutPreventsSignupReplayAndDoesNotCreateReplacementSession() {
        var request = request(
                kakaoProof(),
                termsId
        );

        String key = key();
        submit(request, key);

        jdbc.update(
                "update auth_sessions set revoked_at = ?",
                utc(clock.instant())
        );

        assertCode(
                ErrorCode.UNAUTHENTICATED,
                () -> submit(request, key)
        );

        assertEquals(1, count("auth_sessions"));
    }

    @Test
    void exactSessionExpiryPreventsReplay() {
        var request = request(
                kakaoProof(),
                termsId
        );

        String key = key();
        var result = submit(request, key);

        clock.set(result.expiresAt());

        assertCode(
                ErrorCode.UNAUTHENTICATED,
                () -> submit(request, key)
        );
    }

    @Test
    void deletingMemberChangedAuthVersionOrRemovedIdentityPreventsReplay() {
        var request = request(
                kakaoProof(),
                termsId
        );

        String key = key();
        submit(request, key);

        jdbc.update(
                "update users set status = 'DELETING'"
        );

        assertCode(
                ErrorCode.UNAUTHENTICATED,
                () -> submit(request, key)
        );

        jdbc.update(
                """
                update users
                set status = 'ACTIVE',
                    auth_version = 1
                """
        );

        assertCode(
                ErrorCode.UNAUTHENTICATED,
                () -> submit(request, key)
        );

        jdbc.update("update users set auth_version = 0");
        jdbc.update("delete from auth_identities");

        assertCode(
                ErrorCode.UNAUTHENTICATED,
                () -> submit(request, key)
        );
    }

    @Test
    void expiredIdempotencyResponseIsPurgedButKeyStillConflicts() {
        var request = request(
                kakaoProof(),
                termsId
        );

        String key = key();
        submit(request, key);

        clock.advance(86401);

        new VerificationMaintenance(
                jdbc,
                clock
        ).clean();

        assertNull(
                jdbc.queryForObject(
                        """
                        select encrypted_response
                        from api_idempotency_requests
                        where operation = ?
                        """,
                        String.class,
                        SocialSignupRequestStore.OPERATION
                )
        );

        assertCode(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> submit(request, key)
        );

        assertEquals(1, count("users"));
    }

    @Test
    void corruptEncryptedResponseFailsClosed() {
        var request = request(
                kakaoProof(),
                termsId
        );

        String key = key();
        submit(request, key);

        jdbc.update(
                """
                update api_idempotency_requests
                set encrypted_response = 'broken'
                where operation = ?
                """,
                SocialSignupRequestStore.OPERATION
        );

        assertCode(
                ErrorCode.SERVICE_UNAVAILABLE,
                () -> submit(request, key)
        );
    }

    @Test
    void jwtIssuanceFailureRollsBackMemberConsentProofAndSession() {
        String proof = kakaoProof();

        doThrow(
                new JwtEncodingException("test only")
        ).when(tokens).issue(
                anyLong(),
                anyString(),
                anyInt(),
                any(Instant.class),
                any(Instant.class)
        );

        assertThrows(
                JwtEncodingException.class,
                () -> submit(
                        request(proof, termsId),
                        key()
                )
        );

        assertNoMemberOrConsumedProof();
    }

    @Test
    void resultSaveFailureRollsBackInvalidationOfOtherProofsToo() {
        String proof = kakaoProof();
        kakaoProof();

        doThrow(
                new DataAccessResourceFailureException(
                        "test only"
                )
        ).when(requests).complete(
                any(),
                any(),
                any()
        );

        assertCode(
                ErrorCode.SERVICE_UNAVAILABLE,
                () -> submit(
                        request(proof, termsId),
                        key()
                )
        );

        assertNoMemberOrConsumedProof();

        assertEquals(
                0,
                jdbc.queryForObject(
                        """
                        select count(*)
                        from auth_proofs
                        where invalidated_at is not null
                        """,
                        Integer.class
                )
        );
    }

    @Test
    void simultaneousSameKeyCreatesOneUserAndSession()
            throws Exception {

        var request = request(
                kakaoProof(),
                termsId
        );

        String key = key();

        var results = parallel(
                () -> submit(request, key)
        );

        assertEquals(
                1,
                results.stream().distinct().count()
        );

        assertEquals(1, count("users"));
        assertEquals(1, count("auth_sessions"));
        assertEquals(1, signupRequests());
    }

    @Test
    void differentKeysCannotConsumeSameProofTwice()
            throws Exception {

        var request = request(
                kakaoProof(),
                termsId
        );

        var results = parallel(() -> {
            try {
                submit(request, key());
                return "CREATED";

            } catch (BusinessException ex) {
                return ex.getErrorCode().name();
            }
        });

        assertEquals(
                1,
                results.stream()
                        .filter("CREATED"::equals)
                        .count()
        );

        assertEquals(
                7,
                results.stream()
                        .filter(
                                "INVALID_REGISTRATION_TOKEN"::equals
                        )
                        .count()
        );

        assertEquals(1, count("users"));
    }

    @Test
    void concurrentDifferentProofsForSameIdentityCreateOneAccount()
            throws Exception {

        var first = request(
                kakaoProof(),
                termsId
        );

        var second = request(
                kakaoProof(),
                termsId
        );

        try (var executor =
                     Executors.newFixedThreadPool(2)) {

            CountDownLatch start =
                    new CountDownLatch(1);

            List<Future<Boolean>> results =
                    new ArrayList<>();

            for (var request : List.of(first, second)) {
                results.add(
                        executor.submit(() -> {
                            start.await();

                            try {
                                submit(request, key());
                                return true;

                            } catch (BusinessException ex) {
                                assertEquals(
                                        ErrorCode.INVALID_REGISTRATION_TOKEN,
                                        ex.getErrorCode()
                                );

                                return false;
                            }
                        })
                );
            }

            start.countDown();

            int created = 0;

            for (var result : results) {
                if (result.get(
                        20,
                        TimeUnit.SECONDS
                )) {
                    created++;
                }
            }

            assertEquals(1, created);
        }

        assertEquals(1, count("users"));
        assertEquals(1, count("auth_identities"));
    }

    @Test
    void rejectsMalformedInputsDuplicateTermsAndClientSuppliedIdentity()
            throws Exception {

        String proof = kakaoProof();

        mvc.perform(
                        post(PATH)
                                .contentType("application/json")
                                .content(
                                        mapper.writeValueAsString(
                                                request(proof, termsId)
                                        )
                                )
                )
                .andExpect(status().isBadRequest());

        mvc.perform(
                        post(PATH)
                                .header(
                                        "Idempotency-Key",
                                        "bad"
                                )
                                .contentType("application/json")
                                .content(
                                        mapper.writeValueAsString(
                                                request(proof, termsId)
                                        )
                                )
                )
                .andExpect(status().isBadRequest());

        List<String> bodies = new ArrayList<>(
                List.of(
                        "{}",
                        "null",
                        "[]",
                        "{",
                        """
                        {
                          "registrationToken": "123456",
                          "acceptedTermsIds": [1]
                        }
                        """,
                        mapper.writeValueAsString(
                                Map.of(
                                        "registrationToken",
                                        proof,
                                        "acceptedTermsIds",
                                        List.of(termsId, termsId)
                                )
                        ),
                        mapper.writeValueAsString(
                                Map.of(
                                        "registrationToken",
                                        proof,
                                        "acceptedTermsIds",
                                        List.of(0)
                                )
                        ),
                        "{\"registrationToken\":\""
                                + proof
                                + "\",\"acceptedTermsIds\":[null]}"
                )
        );

        for (String field : List.of(
                "provider",
                "subject",
                "email",
                "password",
                "accessToken",
                "identityToken",
                "authorizationCode"
        )) {
            bodies.add(
                    mapper.writeValueAsString(
                            Map.of(
                                    "registrationToken",
                                    proof,
                                    "acceptedTermsIds",
                                    List.of(termsId),
                                    field,
                                    "forged"
                            )
                    )
            );
        }

        for (String body : bodies) {
            mvc.perform(
                            post(PATH)
                                    .header(
                                            "Idempotency-Key",
                                            key()
                                    )
                                    .contentType("application/json")
                                    .content(body)
                    )
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            jsonPath("$.error.code")
                                    .value("INVALID_INPUT")
                    );
        }

        assertEquals(0, count("users"));
    }

    @Test
    void dtoStringsDoNotExposeRegistrationTokenOrJwt() {
        String proof = kakaoProof();

        var request = request(proof, termsId);
        var response = submit(request, key());

        assertFalse(
                request.toString().contains(proof)
        );

        assertFalse(
                response.toString()
                        .contains(response.accessToken())
        );
    }

    private SocialSignupResponse submit(
            SocialSignupRequest request,
            String key
    ) {
        return signup.signup(
                request,
                key,
                IP
        );
    }

    private String kakaoProof() {
        return login.login(
                new KakaoLoginRequest(KAKAO_TOKEN),
                key(),
                IP
        ).registrationToken();
    }

    private SocialSignupRequest request(
            String proof,
            Long... ids
    ) {
        return new SocialSignupRequest(
                proof,
                List.of(ids)
        );
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(
                value,
                ZoneOffset.UTC
        );
    }

    private int count(String table) {
        return jdbc.queryForObject(
                "select count(*) from " + table,
                Integer.class
        );
    }

    private int signupRequests() {
        return jdbc.queryForObject(
                """
                select count(*)
                from api_idempotency_requests
                where operation = ?
                """,
                Integer.class,
                SocialSignupRequestStore.OPERATION
        );
    }

    private void assertCode(
            ErrorCode code,
            Runnable action
    ) {
        assertEquals(
                code,
                assertThrows(
                        BusinessException.class,
                        action::run
                ).getErrorCode()
        );
    }

    private void assertNoMemberOrConsumedProof() {
        for (String table : List.of(
                "users",
                "auth_identities",
                "auth_sessions",
                "user_agreements",
                "user_onboarding"
        )) {
            assertEquals(0, count(table), table);
        }

        assertEquals(
                0,
                jdbc.queryForObject(
                        """
                        select count(*)
                        from auth_proofs
                        where consumed_at is not null
                        """,
                        Integer.class
                )
        );

        assertEquals(0, signupRequests());
    }

    private long publish(
            String version,
            Instant effective,
            boolean published
    ) {
        jdbc.update(
                """
                insert into service_terms(
                    version,
                    title,
                    content_url,
                    content_hash,
                    published_at,
                    effective_at
                )
                values (
                    ?,
                    '테스트 약관',
                    'https://example.invalid/terms',
                    ?, ?, ?
                )
                """,
                version,
                "a".repeat(64),
                published
                        ? utc(effective.minusSeconds(1))
                        : null,
                utc(effective)
        );

        return jdbc.queryForObject(
                """
                select id
                from service_terms
                where version = ?
                """,
                Long.class,
                version
        );
    }

    private String socialProof(
            String provider,
            String subject,
            String email
    ) {
        String proof = crypto.generateToken();

        jdbc.update(
                """
                insert into auth_proofs(
                    id,
                    provider,
                    provider_subject,
                    subject_email,
                    purpose,
                    token_hash,
                    issued_at,
                    expires_at
                )
                values (
                    ?, ?, ?, ?,
                    'SIGN_UP',
                    ?, ?, ?
                )
                """,
                UUID.randomUUID().toString(),
                provider,
                subject,
                email,
                crypto.tokenHash(proof),
                utc(clock.instant()),
                utc(clock.instant().plusSeconds(600))
        );

        return proof;
    }

    private <T> List<T> parallel(
            Callable<T> action
    ) throws Exception {

        try (var executor =
                     Executors.newFixedThreadPool(8)) {

            CountDownLatch start =
                    new CountDownLatch(1);

            List<Future<T>> futures =
                    new ArrayList<>();

            for (int i = 0; i < 8; i++) {
                futures.add(
                        executor.submit(() -> {
                            start.await();
                            return action.call();
                        })
                );
            }

            start.countDown();

            List<T> results = new ArrayList<>();

            for (var future : futures) {
                results.add(
                        future.get(
                                30,
                                TimeUnit.SECONDS
                        )
                );
            }

            return results;
        }
    }
}