package com.dadot.server.domain.auth;

import com.dadot.server.domain.auth.dto.request.KakaoLoginRequest;
import com.dadot.server.domain.auth.dto.response.SocialLoginResponse;
import com.dadot.server.domain.auth.repository.SocialLoginRequestStore;
import com.dadot.server.domain.auth.service.KakaoLoginService;
import com.dadot.server.domain.auth.service.SocialLoginIpLimiter;
import com.dadot.server.domain.auth.service.SocialLoginTransaction;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.AccessTokenIssuer;
import com.dadot.server.global.security.SessionJwtAuthenticationConverter;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmailVerificationIntegrationTest.TestConfig.class)
class KakaoLoginIntegrationTest {

    private static final String PATH =
            "/v1/auth/social/kakao";

    private static final String TOKEN =
            "TEST_KAKAO_ACCESS_TOKEN";

    private static final String SUBJECT = "12345";
    private static final String IP = "192.0.2.1";

    @Autowired
    KakaoLoginService service;

    @Autowired
    SocialLoginTransaction transaction;

    @Autowired
    SocialLoginIpLimiter limiter;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EmailVerificationIntegrationTest.MutableClock clock;

    @Autowired
    VerificationTokenCrypto crypto;

    @Autowired
    JwtDecoder decoder;

    @Autowired
    SessionJwtAuthenticationConverter authentication;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper mapper;

    @MockitoBean
    KakaoClient kakao;

    @MockitoSpyBean
    SocialLoginRequestStore requests;

    @MockitoSpyBean
    AccessTokenIssuer tokens;

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
                "users"
        )) {
            jdbc.update("delete from " + table);
        }

        clock.set(
                Instant.parse(
                        "2026-10-24T00:00:00.123456Z"
                )
        );

        reset(kakao, requests, tokens);

        when(kakao.verify(TOKEN))
                .thenAnswer(invocation -> {
                    assertFalse(
                            TransactionSynchronizationManager
                                    .isActualTransactionActive(),
                            "카카오 호출 중 DB 트랜잭션을 열면 안 됩니다."
                    );

                    return new KakaoClient.Verified(
                            SUBJECT,
                            clock.instant().plusSeconds(3600)
                    );
                });
    }

    @AfterEach
    void tearDown() {
        reset(kakao, requests, tokens);
    }

    @Test
    void newUserGetsOnlyHashedTenMinuteProofAndNullableResponseFields()
            throws Exception {

        String key = key();

        String body = mvc.perform(
                        post(PATH)
                                .header("Idempotency-Key", key)
                                .contentType("application/json")
                                .content(body())
                )
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                "Cache-Control",
                                "no-store"
                        )
                )
                .andExpect(
                        jsonPath("$.data.status")
                                .value("SIGN_UP_REQUIRED")
                )
                .andReturn()
                .getResponse()
                .getContentAsString();

        var data = mapper.readTree(body).get("data");

        for (String field : List.of(
                "userId",
                "accessToken",
                "tokenType",
                "expiresAt",
                "onboardingCompleted"
        )) {
            assertTrue(data.has(field));
            assertTrue(data.get(field).isNull());
        }

        String proof = data.get("registrationToken")
                .asString();

        assertTrue(
                proof.matches("[A-Za-z0-9_-]{43}")
        );

        assertEquals(0, count("users"));
        assertEquals(0, count("auth_identities"));
        assertEquals(0, count("auth_sessions"));
        assertEquals(1, count("auth_proofs"));

        assertEquals(
                crypto.tokenHash(proof),
                jdbc.queryForObject(
                        "select token_hash from auth_proofs",
                        String.class
                )
        );

        assertNull(
                jdbc.queryForObject(
                        "select subject_email from auth_proofs",
                        String.class
                )
        );

        assertEquals(
                LocalDateTime.ofInstant(
                        clock.instant().plusSeconds(600),
                        ZoneOffset.UTC
                ),
                jdbc.queryForObject(
                        "select expires_at from auth_proofs",
                        LocalDateTime.class
                )
        );

        String encrypted = jdbc.queryForObject(
                """
                select encrypted_response
                from api_idempotency_requests
                """,
                String.class
        );

        assertFalse(encrypted.contains(proof));
        assertFalse(encrypted.contains(TOKEN));
    }

    @Test
    void existingUserGetsOneHourSessionWithCurrentAuthVersionAndOnboarding() {
        long user = createUser(true, "KAKAO");

        jdbc.update(
                "update users set auth_version = 3 where id = ?",
                user
        );

        var response = login(key());

        assertEquals(
                SocialLoginResponse.Status.LOGIN_COMPLETED,
                response.status()
        );
        assertEquals(user, response.userId());
        assertEquals(
                Boolean.TRUE,
                response.onboardingCompleted()
        );

        assertNull(response.registrationToken());
        assertNull(response.registrationExpiresAt());

        var jwt = decoder.decode(
                response.accessToken()
        );

        assertEquals(
                3,
                ((Number) jwt.getClaim("av")).intValue()
        );

        assertEquals(
                3600,
                response.expiresAt().getEpochSecond()
                        - jwt.getIssuedAt().getEpochSecond()
        );

        assertNotNull(authentication.convert(jwt));

        assertEquals(1, count("auth_sessions"));
        assertEquals(0, count("auth_proofs"));
    }

    @Test
    void missingOnboardingRowDoesNotPreventLogin() {
        long user = createUser(false, "KAKAO");

        jdbc.update(
                "delete from user_onboarding where user_id = ?",
                user
        );

        assertEquals(
                Boolean.FALSE,
                login(key()).onboardingCompleted()
        );
    }

    @Test
    void repeatedKeyRecoversSameProofWithoutCallingKakaoAgain() {
        String key = key();
        var first = login(key);

        assertEquals(
                first,
                login(key.toUpperCase())
        );

        assertEquals(1, count("auth_proofs"));
        assertEquals(
                1,
                count("api_idempotency_requests")
        );

        verify(kakao, times(1)).verify(TOKEN);
    }

    @Test
    void repeatedKeyRecoversSameSession() {
        createUser(false, "KAKAO");

        String key = key();
        var first = login(key);

        assertEquals(first, login(key));
        assertEquals(1, count("auth_sessions"));

        verify(kakao, times(1)).verify(TOKEN);
    }

    @Test
    void differentKeysCreateIndependentLoginSessions() {
        createUser(false, "KAKAO");

        var first = login(key());
        var second = login(key());

        assertNotEquals(
                first.accessToken(),
                second.accessToken()
        );

        assertNotNull(
                authentication.convert(
                        decoder.decode(first.accessToken())
                )
        );

        assertEquals(2, count("auth_sessions"));
    }

    @Test
    void anEmailIdentityIsNeverAutomaticallyLinked() {
        createUser(false, "EMAIL");

        assertEquals(
                SocialLoginResponse.Status.SIGN_UP_REQUIRED,
                login(key()).status()
        );

        assertEquals(1, count("users"));
        assertEquals(1, count("auth_identities"));
        assertEquals(0, count("auth_sessions"));
    }

    @Test
    void deletingMemberCannotLogin() {
        long user = createUser(false, "KAKAO");

        jdbc.update(
                "update users set status = 'DELETING' where id = ?",
                user
        );

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> login(key())
        );

        assertEquals(0, count("auth_sessions"));
        assertEquals(
                0,
                count("api_idempotency_requests")
        );
    }

    @Test
    void revokedSessionIsNotRecoveredOrReplaced() {
        createUser(false, "KAKAO");

        String key = key();
        login(key);

        jdbc.update(
                "update auth_sessions set revoked_at = ?",
                utc()
        );

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> login(key)
        );

        assertEquals(1, count("auth_sessions"));
    }

    @Test
    void changedAuthVersionInvalidatesCachedLogin() {
        long user = createUser(false, "KAKAO");

        String key = key();
        login(key);

        jdbc.update(
                "update users set auth_version = 1 where id = ?",
                user
        );

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> login(key)
        );
    }

    @Test
    void deletingIdentityInvalidatesCachedLogin() {
        createUser(false, "KAKAO");

        String key = key();
        login(key);

        jdbc.update("delete from auth_identities");

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> login(key)
        );
    }

    @Test
    void exactSessionExpiryRejectsCachedLogin() {
        createUser(false, "KAKAO");

        String key = key();
        var first = login(key);

        clock.set(first.expiresAt());

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> login(key)
        );

        assertEquals(1, count("auth_sessions"));
    }

    @Test
    void expiredProofCannotBeReplayed() {
        String key = key();
        var first = login(key);

        clock.set(first.registrationExpiresAt());

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> login(key)
        );
    }

    @Test
    void consumedOrInvalidatedOrDeletedProofCannotBeRecovered() {
        for (String change : List.of(
                "update auth_proofs set consumed_at = ?",
                "update auth_proofs set invalidated_at = ?",
                "delete from auth_proofs where issued_at <= ?"
        )) {
            String key = key();
            login(key);

            jdbc.update(change, utc());

            assertCode(
                    ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                    () -> login(key)
            );
        }
    }

    @Test
    void completedSignupCannotReplayOldRegistrationProof() {
        String key = key();
        login(key);

        createUser(false, "KAKAO");

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> login(key)
        );

        assertEquals(0, count("auth_sessions"));
    }

    @Test
    void expiredIdempotencyKeyStaysConflictEvenAfterResponseWasPurged() {
        String key = key();
        login(key);

        clock.advance(86400);

        jdbc.update(
                """
                update api_idempotency_requests
                set encrypted_response = null,
                    response_key_version = null
                """
        );

        assertCode(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> login(key)
        );

        assertEquals(1, count("auth_proofs"));

        verify(kakao, times(1)).verify(TOKEN);
    }

    @Test
    void bodyHmacMismatchIsConflict() {
        String key = key();
        login(key);

        jdbc.update(
                """
                update api_idempotency_requests
                set body_hmac = ?
                """,
                "0".repeat(64)
        );

        assertCode(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> login(key)
        );
    }

    @Test
    void corruptEncryptedResultReturnsServiceUnavailable() {
        String key = key();
        login(key);

        jdbc.update(
                """
                update api_idempotency_requests
                set encrypted_response = 'broken'
                """
        );

        assertCode(
                ErrorCode.SERVICE_UNAVAILABLE,
                () -> login(key)
        );
    }

    @Test
    void upstreamCredentialFailurePersistsAttemptButCreatesNothing() {
        when(kakao.verify(TOKEN))
                .thenThrow(
                        new BusinessException(
                                ErrorCode.INVALID_SOCIAL_CREDENTIAL
                        )
                );

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> login(key())
        );

        assertEquals(0, count("auth_proofs"));
        assertEquals(
                0,
                count("api_idempotency_requests")
        );

        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                        select max(request_count)
                        from auth_request_guards
                        """,
                        Integer.class
                )
        );
    }

    @Test
    void jwtFailureRollsBackSessionAndIdempotencyResult() {
        createUser(false, "KAKAO");

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
                () -> login(key())
        );

        assertEquals(0, count("auth_sessions"));
        assertEquals(
                0,
                count("api_idempotency_requests")
        );
    }

    @Test
    void resultStorageFailureRollsBackProofAndSession() {
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
                () -> login(key())
        );

        assertEquals(0, count("auth_proofs"));

        createUser(false, "KAKAO");

        assertCode(
                ErrorCode.SERVICE_UNAVAILABLE,
                () -> login(key())
        );

        assertEquals(0, count("auth_sessions"));
    }

    @Test
    void concurrentSameKeyCreatesOneResult()
            throws Exception {

        String key = key();

        try (var executor =
                     Executors.newFixedThreadPool(8)) {

            CountDownLatch start =
                    new CountDownLatch(1);

            List<Future<SocialLoginResponse>> futures =
                    new ArrayList<>();

            for (int i = 0; i < 8; i++) {
                futures.add(
                        executor.submit(() -> {
                            start.await();
                            return login(key);
                        })
                );
            }

            start.countDown();

            var results =
                    new HashSet<SocialLoginResponse>();

            for (var future : futures) {
                results.add(
                        future.get(
                                20,
                                TimeUnit.SECONDS
                        )
                );
            }

            assertEquals(1, results.size());
            assertEquals(1, count("auth_proofs"));
            assertEquals(
                    1,
                    count("api_idempotency_requests")
            );
        }
    }

    @Test
    void minuteLimitIncludesInvalidProviderTokensAndIgnoresForwardedHeader()
            throws Exception {

        for (int i = 0; i < 20; i++) {
            limiter.consume("127.0.0.1");
        }

        mvc.perform(
                        post(PATH)
                                .header(
                                        "Idempotency-Key",
                                        key()
                                )
                                .header(
                                        "X-Forwarded-For",
                                        "198.51.100.8"
                                )
                                .contentType(
                                        "application/json"
                                )
                                .content(body())
                )
                .andExpect(
                        status().isTooManyRequests()
                )
                .andExpect(
                        jsonPath("$.error.code")
                                .value("RATE_LIMITED")
                );

        verifyNoInteractions(kakao);
    }

    @Test
    void hourlyLimitSurvivesMinuteChangesAndAnotherIpIsIndependent() {
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 20; j++) {
                limiter.consume(IP);
            }

            clock.advance(61);
        }

        assertCode(
                ErrorCode.RATE_LIMITED,
                () -> limiter.consume(IP)
        );

        assertDoesNotThrow(
                () -> limiter.consume("192.0.2.2")
        );
    }

    @Test
    void expiredVerifiedCredentialIsRejectedBeforeDatabaseWrites() {
        var verified = new KakaoClient.Verified(
                SUBJECT,
                clock.instant()
        );

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> transaction.complete(
                        requests.key(TOKEN, key()),
                        verified
                )
        );

        assertEquals(0, count("auth_proofs"));
    }

    @Test
    void missingMalformedOrMutuallyExclusiveInputsAreBadRequest()
            throws Exception {

        mvc.perform(
                        post(PATH)
                                .contentType(
                                        "application/json"
                                )
                                .content(body())
                )
                .andExpect(status().isBadRequest());

        mvc.perform(
                        post(PATH)
                                .header(
                                        "Idempotency-Key",
                                        "bad"
                                )
                                .contentType(
                                        "application/json"
                                )
                                .content(body())
                )
                .andExpect(status().isBadRequest());

        for (String json : List.of(
                "{}",
                "null",
                "[]",
                "{",
                "{\"accessToken\":\" \"}",
                "{\"accessToken\":\"a\\r\\nb\"}",
                mapper.writeValueAsString(
                        Map.of(
                                "accessToken",
                                "a".repeat(2049)
                        )
                ),
                mapper.writeValueAsString(
                        Map.of(
                                "accessToken",
                                TOKEN,
                                "identityToken",
                                "forbidden"
                        )
                ),
                mapper.writeValueAsString(
                        Map.of(
                                "accessToken",
                                TOKEN,
                                "authorizationCode",
                                "forbidden"
                        )
                )
        )) {
            mvc.perform(
                            post(PATH)
                                    .header(
                                            "Idempotency-Key",
                                            key()
                                    )
                                    .contentType(
                                            "application/json"
                                    )
                                    .content(json)
                    )
                    .andExpect(
                            status().isBadRequest()
                    )
                    .andExpect(
                            jsonPath("$.error.code")
                                    .value("INVALID_INPUT")
                    );
        }

        verifyNoInteractions(kakao);

        assertEquals(
                0,
                count("auth_request_guards")
        );
    }

    @Test
    void registrationProofCannotBeUsedAsBearerToken()
            throws Exception {

        var proof = login(key());

        mvc.perform(
                        post("/v1/auth/logout")
                                .header(
                                        "Authorization",
                                        "Bearer "
                                                + proof.registrationToken()
                                )
                )
                .andExpect(
                        status().isUnauthorized()
                );
    }

    @Test
    void redactsCredentialsAndResponseTokens() {
        var response = login(key());

        assertFalse(
                new KakaoLoginRequest(TOKEN)
                        .toString()
                        .contains(TOKEN)
        );

        assertFalse(
                response.toString()
                        .contains(
                                response.registrationToken()
                        )
        );
    }

    private SocialLoginResponse login(String key) {
        return service.login(
                new KakaoLoginRequest(TOKEN),
                key,
                IP
        );
    }

    private String body() {
        return "{\"accessToken\":\""
                + TOKEN
                + "\"}";
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private LocalDateTime utc() {
        return LocalDateTime.ofInstant(
                clock.instant(),
                ZoneOffset.UTC
        );
    }

    private int count(String table) {
        return jdbc.queryForObject(
                "select count(*) from " + table,
                Integer.class
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

    private long createUser(
            boolean onboarded,
            String provider
    ) {
        jdbc.update(
                """
                insert into users(nickname)
                values ('테스트')
                """
        );

        long id = jdbc.queryForObject(
                "select max(id) from users",
                Long.class
        );

        if ("KAKAO".equals(provider)) {
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

        } else {
            jdbc.update(
                    """
                    insert into auth_identities(
                        user_id,
                        provider,
                        provider_subject,
                        email,
                        password_hash
                    )
                    values (
                        ?,
                        'EMAIL',
                        'hello@example.invalid',
                        'hello@example.invalid',
                        'test-only-hash'
                    )
                    """,
                    id
            );
        }

        jdbc.update(
                """
                insert into user_onboarding(
                    user_id,
                    completed_at
                )
                values (?, ?)
                """,
                id,
                onboarded ? utc() : null
        );

        return id;
    }
}