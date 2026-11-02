package com.dadot.server.domain.auth;

import com.dadot.server.domain.auth.dto.request.IssueEmailVerificationRequest;
import com.dadot.server.domain.auth.dto.response.IssueEmailVerificationResponse;
import com.dadot.server.domain.auth.entity.VerificationPurpose;
import com.dadot.server.domain.auth.service.*;
import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.global.exception.*;
import com.dadot.server.global.security.UserPrincipal;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmailVerificationIntegrationTest.TestConfig.class)
class EmailVerificationIntegrationTest {

    @Autowired EmailVerificationService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @Autowired FakeMailer mailer;
    @Autowired VerificationCrypto crypto;
    @Autowired MockMvc mvc;

    private static final String PATH =
            "/v1/auth/email-verifications";
    private static final String EMAIL =
            "hello@example.invalid";
    private static final String IP = "192.0.2.1";

    @BeforeEach
    void reset() {
        String database = jdbc.queryForObject(
                "select database()",
                String.class
        );

        assertTrue(
                Set.of(
                        "dadot_test_db",
                        "dadot_auth01_review"
                ).contains(database),
                "반드시 전용 테스트 DB를 사용해야 합니다."
        );

        for (String table : List.of(
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

        clock.set(Instant.parse("2026-10-22T10:00:00Z"));
        mailer.clear();
    }

    @Test
    void storesDigestWithPurposeAndTenMinuteExpiry() {
        var response = issue(
                EMAIL, VerificationPurpose.SIGN_UP, key()
        );

        assertEquals(
                clock.instant().plusSeconds(600),
                response.expiresAt()
        );
        assertEquals(
                clock.instant().plusSeconds(60),
                response.resendAvailableAt()
        );
        assertEquals(1, mailer.messages.size());

        var sent = mailer.messages.getFirst();
        assertTrue(sent.code().matches("[0-9]{6}"));

        String digest = jdbc.queryForObject(
                "select code_digest from email_verifications where id = ?",
                String.class,
                response.verificationId()
        );

        assertEquals(
                crypto.codeDigest(
                        response.verificationId(),
                        EMAIL,
                        "SIGN_UP",
                        sent.code()
                ),
                digest
        );

        assertNotEquals(sent.code(), digest);

        assertEquals(
                LocalDateTime.of(2026, 10, 22, 10, 0),
                jdbc.queryForObject(
                        "select sent_at from email_verifications where id = ?",
                        LocalDateTime.class,
                        response.verificationId()
                )
        );
    }

    @Test
    void sameKeyReplaysWithoutSendingOrInsertingAgain() {
        String key = key();
        var first = issue(
                EMAIL, VerificationPurpose.SIGN_UP, key
        );

        assertEquals(
                first,
                issue(EMAIL, VerificationPurpose.SIGN_UP, key)
        );
        assertEquals(1, mailer.messages.size());
        assertEquals(1, count("email_verifications"));
        assertEquals(1, count("api_idempotency_requests"));
    }

    @Test
    void differentBodySameKeyConflicts() {
        String key = key();
        issue(EMAIL, VerificationPurpose.SIGN_UP, key);

        assertCode(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> issue(
                        "other@example.invalid",
                        VerificationPurpose.SIGN_UP,
                        key
                )
        );

        assertCode(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> issue(
                        EMAIL,
                        VerificationPurpose.PASSWORD_RESET,
                        key
                )
        );

        assertEquals(1, mailer.messages.size());
    }

    @Test
    void expiredKeyCannotCreateAnotherCode() {
        String key = key();
        issue(EMAIL, VerificationPurpose.SIGN_UP, key);
        clock.advance(86400);

        assertCode(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> issue(
                        EMAIL, VerificationPurpose.SIGN_UP, key
                )
        );

        assertEquals(1, count("email_verifications"));
    }

    @Test
    void deletedVerificationIsNotRecreatedByReplay() {
        String key = key();
        issue(EMAIL, VerificationPurpose.SIGN_UP, key);

        jdbc.update("delete from email_verifications");

        assertCode(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                () -> issue(
                        EMAIL, VerificationPurpose.SIGN_UP, key
                )
        );
    }

    @Test
    void cooldownRejects59SecondsAndAllows60Seconds() {
        var first = issue(
                EMAIL, VerificationPurpose.SIGN_UP, key()
        );

        clock.advance(59);

        assertCode(
                ErrorCode.RATE_LIMITED,
                () -> issue(
                        EMAIL, VerificationPurpose.SIGN_UP, key()
                )
        );

        clock.advance(1);
        issue(EMAIL, VerificationPurpose.SIGN_UP, key());

        assertNotNull(
                jdbc.queryForObject(
                        "select invalidated_at from email_verifications where id = ?",
                        LocalDateTime.class,
                        first.verificationId()
                )
        );

        assertEquals(2, count("email_verifications"));
    }

    @Test
    void hourlyQuotaIncludesInvalidatedCodesAndExpiresAtBoundary() {
        for (int i = 0; i < 5; i++) {
            issue(EMAIL, VerificationPurpose.SIGN_UP, key());
            clock.advance(60);
        }

        assertCode(
                ErrorCode.RATE_LIMITED,
                () -> issue(
                        EMAIL, VerificationPurpose.SIGN_UP, key()
                )
        );

        clock.advance(3300);
        issue(EMAIL, VerificationPurpose.SIGN_UP, key());

        assertEquals(6, count("email_verifications"));
    }

    @Test
    void differentPurposesHaveSeparateEmailQuotas() {
        issue(EMAIL, VerificationPurpose.SIGN_UP, key());
        issue(EMAIL, VerificationPurpose.PASSWORD_RESET, key());

        assertEquals(2, mailer.messages.size());
    }

    @Test
    void smtpFailureRollsBackNewCodeAndKeepsOldCodeUsable() {
        var first = issue(
                EMAIL, VerificationPurpose.SIGN_UP, key()
        );

        clock.advance(60);
        mailer.failNext.set(true);

        String retryKey = key();

        assertCode(
                ErrorCode.SERVICE_UNAVAILABLE,
                () -> issue(
                        EMAIL,
                        VerificationPurpose.SIGN_UP,
                        retryKey
                )
        );

        assertEquals(1, count("email_verifications"));
        assertEquals(1, count("api_idempotency_requests"));

        assertNull(
                jdbc.queryForObject(
                        "select invalidated_at from email_verifications where id = ?",
                        LocalDateTime.class,
                        first.verificationId()
                )
        );

        issue(EMAIL, VerificationPurpose.SIGN_UP, retryKey);

        assertEquals(2, count("email_verifications"));
    }

    @Test
    void signupRejectsExistingEmail() {
        createIdentity(EMAIL, "ACTIVE");

        assertCode(
                ErrorCode.EMAIL_ALREADY_REGISTERED,
                () -> issue(
                        EMAIL, VerificationPurpose.SIGN_UP, key()
                )
        );

        assertEquals(0, mailer.messages.size());
    }

    @Test
    void signupDoesNotReuseDeletingAccountEmail() {
        createIdentity(EMAIL, "DELETING");

        assertCode(
                ErrorCode.EMAIL_ALREADY_REGISTERED,
                () -> issue(
                        EMAIL, VerificationPurpose.SIGN_UP, key()
                )
        );
    }

    @Test
    void resetMissingAccountStillIssuesSameShapeAndSendsNeutralMail() {
        var response = issue(
                EMAIL, VerificationPurpose.PASSWORD_RESET, key()
        );

        assertNotNull(response.verificationId());
        assertEquals(1, mailer.messages.size());

        assertNull(
                jdbc.queryForObject(
                        "select user_id from email_verifications where id = ?",
                        Long.class,
                        response.verificationId()
                )
        );
    }

    @Test
    void resetExistingAccountBindsOriginalUser() {
        long id = createIdentity(EMAIL, "ACTIVE");

        var response = issue(
                EMAIL, VerificationPurpose.PASSWORD_RESET, key()
        );

        assertEquals(
                id,
                jdbc.queryForObject(
                        "select user_id from email_verifications where id = ?",
                        Long.class,
                        response.verificationId()
                )
        );
    }

    @Test
    void passwordChangeRequiresAuthenticationAndSameAccount() {
        long id = createIdentity(EMAIL, "ACTIVE");

        assertCode(
                ErrorCode.UNAUTHENTICATED,
                () -> issue(
                        EMAIL,
                        VerificationPurpose.PASSWORD_CHANGE,
                        key()
                )
        );

        assertCode(
                ErrorCode.FORBIDDEN,
                () -> service.issue(
                        request(
                                EMAIL,
                                VerificationPurpose.PASSWORD_CHANGE
                        ),
                        key(),
                        IP,
                        id + 1
                )
        );

        assertNotNull(
                service.issue(
                        request(
                                EMAIL,
                                VerificationPurpose.PASSWORD_CHANGE
                        ),
                        key(),
                        IP,
                        id
                )
        );
    }

    @Test
    void minuteIpLimitAppliesEvenAfterBusinessFailure() {
        createIdentity(EMAIL, "ACTIVE");

        for (int i = 0; i < 10; i++) {
            assertCode(
                    ErrorCode.EMAIL_ALREADY_REGISTERED,
                    () -> issue(
                            EMAIL,
                            VerificationPurpose.SIGN_UP,
                            key()
                    )
            );
        }

        assertCode(
                ErrorCode.RATE_LIMITED,
                () -> issue(
                        "another@example.invalid",
                        VerificationPurpose.SIGN_UP,
                        key()
                )
        );

        clock.advance(60);

        issue(
                "another@example.invalid",
                VerificationPurpose.SIGN_UP,
                key()
        );
    }

    @Test
    void hourlyIpLimitAppliesAcrossMinuteWindows() {
        createIdentity(EMAIL, "ACTIVE");

        for (int minute = 0; minute < 5; minute++) {
            for (int i = 0; i < 10; i++) {
                assertCode(
                        ErrorCode.EMAIL_ALREADY_REGISTERED,
                        () -> issue(
                                EMAIL,
                                VerificationPurpose.SIGN_UP,
                                key()
                        )
                );
            }

            clock.advance(60);
        }

        assertCode(
                ErrorCode.RATE_LIMITED,
                () -> issue(
                        "another@example.invalid",
                        VerificationPurpose.SIGN_UP,
                        key()
                )
        );
    }

    @Test
    void sameKeyConcurrentRequestsSendOnce() throws Exception {
        String key = key();

        var results = parallel(
                () -> issue(
                        EMAIL, VerificationPurpose.SIGN_UP, key
                )
        );

        assertEquals(8, results.size());
        assertEquals(1, new HashSet<>(results).size());
        assertEquals(1, mailer.messages.size());
        assertEquals(1, count("email_verifications"));
    }

    @Test
    void differentKeysConcurrentRequestsRespectCooldown()
            throws Exception {

        var results = parallel(() -> {
            try {
                return issue(
                        EMAIL,
                        VerificationPurpose.SIGN_UP,
                        key()
                ).verificationId();

            } catch (BusinessException ex) {
                return ex.getErrorCode().name();
            }
        });

        assertEquals(
                7,
                results.stream()
                        .filter("RATE_LIMITED"::equals)
                        .count()
        );

        assertEquals(1, mailer.messages.size());
    }

    @Test
    void httpSuccessUsesExistingEnvelopeAndUtcDates()
            throws Exception {

        mvc.perform(
                        post(PATH)
                                .contentType("application/json")
                                .header("Idempotency-Key", key())
                                .content("""
                                {
                                  "email": "  HELLO@example.invalid  ",
                                  "purpose": "SIGN_UP"
                                }
                                """)
                )
                .andExpect(status().isCreated())
                .andExpect(
                        header().string(
                                "Cache-Control",
                                "no-store"
                        )
                )
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(
                        jsonPath("$.data.expiresAt")
                                .value("2026-10-22T10:10:00Z")
                )
                .andExpect(
                        jsonPath("$.data.resendAvailableAt")
                                .value("2026-10-22T10:01:00Z")
                )
                .andExpect(
                        jsonPath("$.data.code").doesNotExist()
                );

        assertEquals(
                EMAIL,
                mailer.messages.getFirst().email()
        );
    }

    @Test
    void httpRejectsMissingKeyInvalidUuidAndBadBody()
            throws Exception {

        String validBody = """
                {
                  "email": "hello@example.invalid",
                  "purpose": "SIGN_UP"
                }
                """;

        mvc.perform(
                        post(PATH)
                                .contentType("application/json")
                                .content(validBody)
                )
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath("$.error.code")
                                .value("INVALID_INPUT")
                );

        for (String invalidKey : List.of(
                "not-a-uuid",
                "1-1-1-1-1"
        )) {
            mvc.perform(
                            post(PATH)
                                    .contentType("application/json")
                                    .header(
                                            "Idempotency-Key",
                                            invalidKey
                                    )
                                    .content(validBody)
                    )
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            jsonPath("$.error.code")
                                    .value("INVALID_INPUT")
                    );
        }

        for (String body : List.of(
                "{}",
                """
                {"email":"bad","purpose":"SIGN_UP"}
                """,
                """
                {"email":"hello@example.invalid","purpose":"OTHER"}
                """
        )) {
            mvc.perform(
                            post(PATH)
                                    .contentType("application/json")
                                    .header("Idempotency-Key", key())
                                    .content(body)
                    )
                    .andExpect(status().isBadRequest())
                    .andExpect(
                            jsonPath("$.error.code")
                                    .value("INVALID_INPUT")
                    );
        }
    }

    @Test
    void httpPasswordChangeUsesTrustedPrincipal()
            throws Exception {

        long id = createIdentity(EMAIL, "ACTIVE");

        String body = """
                {
                  "email": "hello@example.invalid",
                  "purpose": "PASSWORD_CHANGE"
                }
                """;

        mvc.perform(
                post(PATH)
                        .contentType("application/json")
                        .header("Idempotency-Key", key())
                        .content(body)
        ).andExpect(status().isUnauthorized());

        var auth =
                UsernamePasswordAuthenticationToken.authenticated(
                        new UserPrincipal(id),
                        null,
                        List.of()
                );

        mvc.perform(
                post(PATH)
                        .with(authentication(auth))
                        .contentType("application/json")
                        .header("Idempotency-Key", key())
                        .content(body)
        ).andExpect(status().isCreated());
    }

    @Test
    void cryptoBindsAllFieldsAndCannotConfuseConcatenation() {
        String digest = crypto.codeDigest(
                "id", EMAIL, "SIGN_UP", "020301"
        );

        assertNotEquals(
                digest,
                crypto.codeDigest(
                        "other", EMAIL, "SIGN_UP", "020301"
                )
        );

        assertNotEquals(
                digest,
                crypto.codeDigest(
                        "id", EMAIL, "PASSWORD_RESET", "020301"
                )
        );

        assertNotEquals(
                crypto.hmac("ab", "c"),
                crypto.hmac("a", "bc")
        );
    }

    private IssueEmailVerificationRequest request(
            String email,
            VerificationPurpose purpose
    ) {
        return new IssueEmailVerificationRequest(email, purpose);
    }

    private IssueEmailVerificationResponse issue(
            String email,
            VerificationPurpose purpose,
            String key
    ) {
        return service.issue(
                request(email, purpose),
                key,
                IP,
                null
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

    private void assertCode(
            ErrorCode expected,
            Runnable call
    ) {
        assertEquals(
                expected,
                assertThrows(
                        BusinessException.class,
                        call::run
                ).getErrorCode()
        );
    }

    private long createIdentity(
            String email,
            String status
    ) {
        jdbc.update(
                """
                insert into users(nickname, status)
                values ('테스트회원', ?)
                """,
                status
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
                    provider_subject,
                    email,
                    password_hash
                )
                values (?, 'EMAIL', ?, ?, 'synthetic-test-hash')
                """,
                id,
                email,
                email
        );

        return id;
    }

    private <T> List<T> parallel(Callable<T> task)
            throws Exception {

        try (var executor = Executors.newFixedThreadPool(8)) {
            CountDownLatch ready = new CountDownLatch(8);
            CountDownLatch start = new CountDownLatch(1);

            List<Future<T>> futures = new ArrayList<>();

            for (int i = 0; i < 8; i++) {
                futures.add(
                        executor.submit(() -> {
                            ready.countDown();

                            assertTrue(
                                    start.await(
                                            10,
                                            TimeUnit.SECONDS
                                    )
                            );

                            return task.call();
                        })
                );
            }

            assertTrue(
                    ready.await(10, TimeUnit.SECONDS)
            );

            start.countDown();

            List<T> values = new ArrayList<>();

            for (var future : futures) {
                values.add(
                        future.get(20, TimeUnit.SECONDS)
                );
            }

            return values;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        FakeMailer fakeMailer() {
            return new FakeMailer();
        }
    }

    static class MutableClock extends Clock {

        private final AtomicReference<Instant> now =
                new AtomicReference<>(
                        Instant.parse("2026-10-22T10:00:00Z")
                );

        void set(Instant instant) {
            now.set(instant);
        }

        void advance(long seconds) {
            now.updateAndGet(
                    value -> value.plusSeconds(seconds)
            );
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    static class FakeMailer implements VerificationMailer {

        final List<Sent> messages =
                new CopyOnWriteArrayList<>();

        final AtomicBoolean failNext =
                new AtomicBoolean();

        @Override
        public void send(
                String email,
                String code,
                VerificationPurpose purpose
        ) {
            if (failNext.getAndSet(false)) {
                throw new BusinessException(
                        ErrorCode.SERVICE_UNAVAILABLE
                );
            }

            messages.add(
                    new Sent(email, code, purpose)
            );
        }

        void clear() {
            messages.clear();
            failNext.set(false);
        }
    }

    record Sent(
            String email,
            String code,
            VerificationPurpose purpose
    ) {
    }
}