package com.dadot.server.domain.auth;

import com.dadot.server.domain.auth.dto.request.AppleLoginRequest;
import com.dadot.server.domain.auth.dto.request.SocialSignupRequest;
import com.dadot.server.domain.auth.dto.response.SocialLoginResponse;
import com.dadot.server.domain.auth.repository.SocialLoginRequestStore;
import com.dadot.server.domain.auth.service.AppleLoginService;
import com.dadot.server.domain.auth.service.SocialSignupService;
import com.dadot.server.domain.auth.service.VerificationMaintenance;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.infrastructure.apple.AppleClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmailVerificationIntegrationTest.TestConfig.class)
class AppleLoginIntegrationTest {

    static final String PATH = "/v1/auth/social/apple";
    static final String IP = "192.0.2.14";
    static final String SUBJECT = "001.apple.user";

    @Autowired
    AppleLoginService service;

    @Autowired
    SocialSignupService signup;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper mapper;

    @Autowired
    VerificationTokenCrypto crypto;

    @Autowired
    EmailVerificationIntegrationTest.MutableClock clock;

    @MockitoBean
    AppleClient apple;

    @MockitoSpyBean
    SocialLoginRequestStore requests;

    @BeforeEach
    void setup() {
        assertEquals(
                "dadot_test_db",
                jdbc.queryForObject(
                        "select database()",
                        String.class
                )
        );

        for (String table : List.of(
                "apple_login_challenges",
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
                Instant.parse("2026-10-24T00:00:00.123456Z")
        );

        reset(apple, requests);

        when(
                apple.verifyIdentity(
                        anyString(),
                        anyString()
                )
        ).thenAnswer(call -> {
            assertFalse(
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            );
            return verified();
        });

        when(
                apple.exchange(
                        anyString(),
                        anyString(),
                        any()
                )
        ).thenAnswer(call -> {
            assertFalse(
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            );
            return verified();
        });
    }

    @AfterEach
    void resetMocks() {
        reset(apple, requests);
    }

    @Test
    void challengeEndpointIsPublicNoStoreAndOnlyStoresHash()
            throws Exception {
        String body = mvc.perform(
                        post(PATH + "/challenges")
                )
                .andExpect(status().isCreated())
                .andExpect(
                        header().string(
                                "Cache-Control",
                                "no-store"
                        )
                )
                .andReturn()
                .getResponse()
                .getContentAsString();

        var data = mapper.readTree(body).get("data");
        String nonce = data.get("nonce").asString();

        assertEquals(43, nonce.length());

        assertEquals(
                crypto.tokenHash(nonce),
                jdbc.queryForObject(
                        "select nonce_hash from apple_login_challenges",
                        String.class
                )
        );

        assertEquals(
                clock.instant().plusSeconds(300),
                Instant.parse(
                        data.get("expiresAt").asString()
                )
        );
    }

    @Test
    void newAppleUserGetsHashedProofAndVerifiedEmailWithoutAccount() {
        var body = request();
        var result = service.login(body, key(), IP);

        assertEquals(
                SocialLoginResponse.Status.SIGN_UP_REQUIRED,
                result.status()
        );
        assertEquals(0, count("users"));
        assertEquals(0, count("auth_sessions"));

        assertEquals(
                "APPLE",
                jdbc.queryForObject(
                        "select provider from auth_proofs",
                        String.class
                )
        );
        assertEquals(
                SUBJECT,
                jdbc.queryForObject(
                        "select provider_subject from auth_proofs",
                        String.class
                )
        );
        assertEquals(
                "apple@privaterelay.appleid.com",
                jdbc.queryForObject(
                        "select subject_email from auth_proofs",
                        String.class
                )
        );
        assertEquals(
                crypto.tokenHash(result.registrationToken()),
                jdbc.queryForObject(
                        "select token_hash from auth_proofs",
                        String.class
                )
        );
        assertEquals(
                clock.instant().plusSeconds(600),
                result.registrationExpiresAt()
        );

        assertNotNull(
                jdbc.queryForObject(
                        "select consumed_at from apple_login_challenges",
                        LocalDateTime.class
                )
        );

        assertFalse(
                jdbc.queryForObject(
                        "select encrypted_response from api_idempotency_requests",
                        String.class
                ).contains(result.registrationToken())
        );
    }

    @Test
    void storedResultIsRestoredWithoutExchangingOneTimeCodeAgain() {
        var body = request();
        String key = key();

        var first = service.login(body, key, IP);

        assertEquals(
                first,
                service.login(body, key, IP)
        );

        verify(apple, times(1))
                .verifyIdentity(anyString(), anyString());

        verify(apple, times(1))
                .exchange(anyString(), anyString(), any());

        assertEquals(1, count("auth_proofs"));
    }

    @Test
    void changedBodyWithSameTokenAndKeyIsConflict() {
        var body = request();
        String key = key();

        service.login(body, key, IP);

        assertCode(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> service.login(
                        new AppleLoginRequest(
                                body.identityToken(),
                                "CHANGED_CODE",
                                body.nonce()
                        ),
                        key,
                        IP
                )
        );
    }

    @Test
    void newKeyCannotReuseConsumedNonce() {
        var body = request();

        service.login(body, key(), IP);

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> service.login(body, key(), IP)
        );

        verify(apple, times(1))
                .exchange(anyString(), anyString(), any());
    }

    @Test
    void expiredAndUnknownChallengesDoNotExchangeCode() {
        var body = request();

        clock.advance(300);

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> service.login(body, key(), IP)
        );

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> service.login(
                        new AppleLoginRequest(
                                "header.payload.signature",
                                "CODE",
                                crypto.generateToken()
                        ),
                        key(),
                        IP
                )
        );

        verify(apple, never())
                .exchange(anyString(), anyString(), any());
    }

    @Test
    void badIdentityDoesNotConsumeChallenge() {
        var body = request();

        when(
                apple.verifyIdentity(
                        anyString(),
                        anyString()
                )
        ).thenThrow(
                new BusinessException(
                        ErrorCode.INVALID_SOCIAL_CREDENTIAL
                )
        );

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> service.login(body, key(), IP)
        );

        assertNull(
                jdbc.queryForObject(
                        "select consumed_at from apple_login_challenges",
                        LocalDateTime.class
                )
        );
    }

    @Test
    void failedExchangeCannotBeExecutedAgainWithAnotherKey() {
        var body = request();

        when(
                apple.exchange(
                        anyString(),
                        anyString(),
                        any()
                )
        ).thenThrow(
                new BusinessException(
                        ErrorCode.SERVICE_UNAVAILABLE
                )
        );

        assertCode(
                ErrorCode.SERVICE_UNAVAILABLE,
                () -> service.login(body, key(), IP)
        );
        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> service.login(body, key(), IP)
        );

        verify(apple, times(1))
                .exchange(anyString(), anyString(), any());

        assertEquals(0, count("auth_proofs"));
    }

    @Test
    void storageFailureRollsBackProofButDoesNotReuseExternalCode() {
        var body = request();

        doThrow(
                new DataAccessResourceFailureException(
                        "simulated"
                )
        ).when(requests)
                .complete(any(), any(), any());

        assertCode(
                ErrorCode.SERVICE_UNAVAILABLE,
                () -> service.login(body, key(), IP)
        );

        assertEquals(0, count("auth_proofs"));
        assertEquals(0, count("api_idempotency_requests"));

        reset(requests);

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> service.login(body, key(), IP)
        );

        verify(apple, times(1))
                .exchange(anyString(), anyString(), any());
    }

    @Test
    void expiredDuringExchangeCannotCreateProof() {
        var body = request();

        when(
                apple.exchange(
                        anyString(),
                        anyString(),
                        any()
                )
        ).thenAnswer(call -> {
            clock.advance(300);
            return verified();
        });

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> service.login(body, key(), IP)
        );

        assertEquals(0, count("auth_proofs"));
    }

    @Test
    void appleProofCompletesExistingTermsSignupAndLaterLoginThenLogout()
            throws Exception {
        jdbc.update(
                """
                insert into service_terms(
                    code,
                    version,
                    title,
                    content_url,
                    content_hash,
                    is_required,
                    effective_at,
                    published_at
                )
                values (
                    'SERVICE_TERMS',
                    'test-v1',
                    'Test',
                    'https://example.invalid/terms',
                    repeat('a', 64),
                    true,
                    ?,
                    ?
                )
                """,
                utc(),
                utc()
        );

        long terms = jdbc.queryForObject(
                "select id from service_terms",
                Long.class
        );

        String firstKey = key();
        var firstBody = request();

        var proof = service.login(
                firstBody,
                firstKey,
                IP
        );

        var account = signup.signup(
                new SocialSignupRequest(
                        proof.registrationToken(),
                        List.of(terms)
                ),
                key(),
                IP
        );

        assertEquals(
                "APPLE",
                jdbc.queryForObject(
                        "select provider from auth_identities",
                        String.class
                )
        );
        assertEquals(
                "apple@privaterelay.appleid.com",
                jdbc.queryForObject(
                        "select email from auth_identities",
                        String.class
                )
        );

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> service.login(
                        firstBody,
                        firstKey,
                        IP
                )
        );

        String loginKey = key();
        var loginBody = request();

        var login = service.login(
                loginBody,
                loginKey,
                IP
        );

        assertEquals(
                SocialLoginResponse.Status.LOGIN_COMPLETED,
                login.status()
        );
        assertEquals(account.userId(), login.userId());

        assertEquals(
                login,
                service.login(loginBody, loginKey, IP)
        );

        mvc.perform(
                        post("/v1/auth/logout")
                                .header(
                                        "Authorization",
                                        "Bearer " + login.accessToken()
                                )
                )
                .andExpect(status().isNoContent());

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> service.login(
                        loginBody,
                        loginKey,
                        IP
                )
        );
    }

    @Test
    void expiredProofIsNotRestored() {
        var body = request();
        String key = key();

        service.login(body, key, IP);

        clock.advance(600);

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> service.login(body, key, IP)
        );
    }

    @Test
    void maintenanceClearsAppleResponsesButRetainsExpiredKey() {
        var body = request();
        String key = key();

        service.login(body, key, IP);

        clock.advance(86_401);

        new VerificationMaintenance(jdbc, clock).clean();

        assertEquals(0, count("apple_login_challenges"));
        assertEquals(1, count("api_idempotency_requests"));

        assertNull(
                jdbc.queryForObject(
                        "select encrypted_response from api_idempotency_requests",
                        String.class
                )
        );

        assertCode(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> service.login(body, key, IP)
        );
    }

    @Test
    void concurrentDifferentKeysConsumeNonceAndExchangeOnlyOnce()
            throws Exception {
        var body = request();
        var start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(4)) {
            var jobs = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();

                        try {
                            service.login(body, key(), IP);
                            return true;

                        } catch (BusinessException ex) {
                            assertEquals(
                                    ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                                    ex.getErrorCode()
                            );
                            return false;
                        }
                    }))
                    .toList();

            start.countDown();

            int successes = 0;

            for (var job : jobs) {
                if (job.get(15, TimeUnit.SECONDS)) {
                    successes++;
                }
            }

            assertEquals(1, successes);
        }

        verify(apple, times(1))
                .exchange(anyString(), anyString(), any());

        assertEquals(1, count("auth_proofs"));
    }

    @Test
    void sameKeyWhileProcessingIsRetryableWithoutSecondExchange()
            throws Exception {
        var body = request();
        String key = key();

        var exchanging = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        when(
                apple.exchange(
                        anyString(),
                        anyString(),
                        any()
                )
        ).thenAnswer(call -> {
            exchanging.countDown();

            assertTrue(
                    release.await(10, TimeUnit.SECONDS)
            );

            return verified();
        });

        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(
                    () -> service.login(body, key, IP)
            );

            try {
                assertTrue(
                        exchanging.await(5, TimeUnit.SECONDS)
                );

                assertCode(
                        ErrorCode.SERVICE_UNAVAILABLE,
                        () -> service.login(body, key, IP)
                );

            } finally {
                release.countDown();
            }

            assertEquals(
                    first.get(10, TimeUnit.SECONDS),
                    service.login(body, key, IP)
            );
        }

        verify(apple, times(1))
                .exchange(anyString(), anyString(), any());
    }

    @Test
    void malformedRequestsAreRejectedBeforeExternalCalls()
            throws Exception {
        var body = request();
        String json = mapper.writeValueAsString(body);

        mvc.perform(
                        post(PATH)
                                .contentType("application/json")
                                .content(json)
                )
                .andExpect(status().isBadRequest());

        for (String bad : List.of(
                "{}",
                json.replace(body.nonce(), "bad"),
                json.replace(
                        "\"nonce\"",
                        "\"accessToken\""
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

        verify(apple, never())
                .verifyIdentity(anyString(), anyString());

        assertFalse(
                body.toString().contains(body.nonce())
        );
    }

    private AppleLoginRequest request() {
        return new AppleLoginRequest(
                "header.payload.signature",
                "APPLE_CODE",
                service.challenge(IP).nonce()
        );
    }

    private AppleClient.Verified verified() {
        return new AppleClient.Verified(
                SUBJECT,
                clock.instant().plusSeconds(600),
                "apple@privaterelay.appleid.com"
        );
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private int count(String table) {
        return jdbc.queryForObject(
                "select count(*) from " + table,
                Integer.class
        );
    }

    private LocalDateTime utc() {
        return LocalDateTime.ofInstant(
                clock.instant(),
                ZoneOffset.UTC
        );
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
}