package com.dadot.server.domain.member;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dadot.server.domain.auth.service.AppleLoginChallengeService;
import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.domain.member.model.Member;
import com.dadot.server.domain.member.repository.AccountDeletionRepository;
import com.dadot.server.domain.member.repository.LegacySocialResponseCleaner;
import com.dadot.server.global.security.AccessTokenIssuer;
import com.dadot.server.global.security.SessionAuthenticationToken;
import com.dadot.server.global.security.UserPrincipal;
import com.dadot.server.infrastructure.apple.AppleClient;
import com.dadot.server.infrastructure.kakao.KakaoClient;
import com.dadot.server.infrastructure.kakao.KakaoAccountClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.dadot.server.domain.member.repository.MemberRepository.utc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.account-deletion.worker-enabled=false",
        "app.account-deletion.email-attempts-per-ten-minutes=3",
        "app.account-deletion.email-attempts-per-hour=4"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountDeletionReviewIntegrationTest {
    private static final String PASSWORD = "Member123!";

    private static final String EMAIL_BODY =
            "{\"confirm\":true,\"password\":\"Member123!\"}";

    private static final String WRONG_BODY =
            "{\"confirm\":true,\"password\":\"Wrong123!\"}";

    private static final String PRIVATE = "private-marker";

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired AccessTokenIssuer tokens;
    @Autowired VerificationTokenCrypto crypto;
    @Autowired VerificationCrypto hmac;

    @MockitoSpyBean Clock clock;
    @MockitoSpyBean PasswordEncoder passwords;
    @MockitoSpyBean PlatformTransactionManager manager;
    @MockitoSpyBean AccountDeletionRepository deletions;
    @MockitoSpyBean LegacySocialResponseCleaner cleaner;
    @MockitoSpyBean AppleLoginChallengeService challenges;

    @MockitoBean AppleClient apple;
    @MockitoBean KakaoClient kakao;
    @MockitoBean KakaoAccountClient kakaoAccounts;

    private final List<Long> users = new ArrayList<>();
    private final List<Long> responses = new ArrayList<>();

    private final Logger logger =
            (Logger) LoggerFactory.getLogger(
                    LegacySocialResponseCleaner.class
            );

    private final ListAppender<ILoggingEvent> logs =
            new ListAppender<>();

    private long user;
    private String token;
    private String session;
    private String encoded;
    private boolean safe;
    private AppleLoginChallengeService challengeTarget;

    @BeforeEach
    void setup() {
        safe = false;

        assertEquals(
                "dadot_test_db",
                jdbc.queryForObject("select database()", String.class)
        );

        safe = true;

        challengeTarget =
                AopTestUtils.getUltimateTargetObject(challenges);

        reset(
                clock,
                passwords,
                manager,
                deletions,
                cleaner,
                challengeTarget,
                apple,
                kakao,
                kakaoAccounts
        );

        doReturn(
                Instant.parse("2026-11-01T00:00:00Z")
        ).when(clock).instant();

        users.clear();
        responses.clear();

        encoded = passwords.encode(PASSWORD);
        user = account();
        token = login(user);

        when(kakao.verify(anyString())).thenAnswer(call -> {
            assertFalse(
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            );

            return new KakaoClient.Verified(
                    "12345",
                    clock.instant().plusSeconds(300)
            );
        });

        when(
                apple.verifyIdentity(anyString(), anyString())
        ).thenAnswer(call ->
                new AppleClient.Verified(
                        "apple-user",
                        clock.instant().plusSeconds(300),
                        null
                )
        );

        when(
                apple.prepareDeletion(
                        anyString(),
                        anyString(),
                        any()
                )
        ).thenAnswer(call -> {
            assertFalse(
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            );

            return new AppleClient.PreparedDeletion(
                    call.getArgument(2),
                    "refresh-secret"
            );
        });

        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void cleanup() {
        if (!safe) {
            return;
        }

        reset(
                passwords,
                manager,
                deletions,
                cleaner,
                challengeTarget,
                apple,
                kakao,
                kakaoAccounts
        );

        for (long response : responses) {
            jdbc.update(
                    "delete from api_idempotency_requests where id = ?",
                    response
            );
        }

        for (long id : users) {
            jdbc.update(
                    "delete from account_deletions where user_id = ?",
                    id
            );

            for (String table : List.of(
                    "user_devices",
                    "api_idempotency_requests",
                    "auth_sessions",
                    "auth_identities"
            )) {
                jdbc.update(
                        "delete from " + table + " where user_id = ?",
                        id
                );
            }

            jdbc.update(
                    "delete from users where id = ?",
                    id
            );
        }

        jdbc.update("delete from account_deletions");
        jdbc.update("delete from auth_request_guards");
        jdbc.update("delete from apple_login_challenges");

        logger.detachAppender(logs);
        logs.stop();
        logs.list.clear();

        reset(clock);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "base64",
            "tag",
            "json",
            "empty",
            "null",
            "array",
            "missing",
            "number",
            "blank",
            "long",
            "trailing"
    })
    void invalidLegacyResponseDoesNotBlockDeletionOrRetainItsCiphertext(
            String damage
    ) throws Exception {
        social("KAKAO", "12345");

        long broken = legacy(
                "KAKAO",
                "12345",
                PRIVATE
        );

        String plain = switch (damage) {
            case "json" -> "{" + PRIVATE;
            case "empty" -> "";
            case "null" -> "null";
            case "array" -> "[]";
            case "missing" -> "{\"response\":{}}";
            case "number" -> "{\"subject\":12345}";
            case "blank" -> "{\"subject\":\" \"}";
            case "long" -> mapper.writeValueAsString(
                    Map.of("subject", "x".repeat(256))
            );
            case "trailing" -> "{\"subject\":\"12345\"} {}";
            default -> "{\"subject\":\"12345\"}";
        };

        String encrypted = crypto.encrypt(
                plain,
                context(broken)
        );

        if (damage.equals("base64")) {
            encrypted = PRIVATE + "!";
        }

        if (damage.equals("tag")) {
            byte[] bytes = Base64.getDecoder().decode(encrypted);
            bytes[bytes.length - 1] ^= 1;
            encrypted = Base64.getEncoder().encodeToString(bytes);
        }

        jdbc.update(
                """
                update api_idempotency_requests
                set encrypted_response = ? where id = ?
                """,
                encrypted,
                broken
        );

        long own = legacy("KAKAO", "12345", "own");
        long other = legacy("KAKAO", "67890", "other");
        long otherProvider = legacy(
                "APPLE",
                "apple-other",
                "other-provider"
        );

        jdbc.update(
                """
                update api_idempotency_requests
                set response_key_version = 'v9' where id = ?
                """,
                otherProvider
        );

        remove(
                token,
                key(),
                kakaoBody(),
                "192.0.2.10"
        ).andExpect(status().isNoContent());

        assertCleared(broken);
        assertCleared(own);

        assertNull(
                jdbc.queryForObject(
                        """
                        select subject_hash
                        from api_idempotency_requests
                        where id = ?
                        """,
                        String.class,
                        broken
                )
        );

        assertNotNull(
                jdbc.queryForObject(
                        """
                        select subject_hash
                        from api_idempotency_requests
                        where id = ?
                        """,
                        String.class,
                        own
                )
        );

        assertNotNull(cipher(other));
        assertNotNull(cipher(otherProvider));

        assertEquals(
                0,
                count(
                        "select count(*) from users where id = ?",
                        user
                )
        );

        assertFalse(logs.list.isEmpty());

        for (ILoggingEvent event : logs.list) {
            assertNull(event.getThrowableProxy());

            assertFalse(
                    event.getFormattedMessage().contains(PRIVATE)
            );

            assertFalse(
                    event.getFormattedMessage().contains("12345")
            );

            for (Object argument : event.getArgumentArray()) {
                assertFalse(argument instanceof Throwable);
            }
        }
    }

    @Test
    void unsupportedKeyVersionIsNotSilentlyDiscarded()
            throws Exception {
        social("KAKAO", "12345");

        long row = legacy("KAKAO", "12345", "own");

        jdbc.update(
                """
                update api_idempotency_requests
                set response_key_version = 'v9' where id = ?
                """,
                row
        );

        String original = cipher(row);
        String request = key();

        remove(
                token,
                request,
                kakaoBody(),
                "192.0.2.11"
        ).andExpect(status().isServiceUnavailable());

        assertEquals(original, cipher(row));

        assertEquals(
                "DELETING",
                jdbc.queryForObject(
                        "select status from users where id = ?",
                        String.class,
                        user
                )
        );

        jdbc.update(
                """
                update api_idempotency_requests
                set response_key_version = 'v1' where id = ?
                """,
                row
        );

        retryNow();

        remove(
                token,
                request,
                kakaoBody(),
                "192.0.2.11"
        ).andExpect(status().isNoContent());

        verify(kakaoAccounts, times(1)).unlink("12345");
    }

    @Test
    void expiredLegacyResponseCanBeClearedWithoutItsOldKey()
            throws Exception {
        social("KAKAO", "12345");

        long row = legacy("KAKAO", "12345", "expired");

        jdbc.update(
                """
                update api_idempotency_requests
                set expires_at = ?, response_key_version = 'v9'
                where id = ?
                """,
                utc(clock.instant()),
                row
        );

        remove(
                token,
                key(),
                kakaoBody(),
                "192.0.2.12"
        ).andExpect(status().isNoContent());

        assertCleared(row);
    }

    @Test
    void cleanupStorageFailureRollsBackAndCanBeRetried()
            throws Exception {
        social("KAKAO", "12345");

        long row = legacy("KAKAO", "12345", "own");
        String original = cipher(row);

        doAnswer(call -> {
            call.callRealMethod();
            throw new TransientDataAccessResourceException(PRIVATE);
        }).when(cleaner).clean(
                any(),
                anyString(),
                anyString()
        );

        String request = key();

        remove(
                token,
                request,
                kakaoBody(),
                "192.0.2.13"
        ).andExpect(status().isServiceUnavailable());

        assertEquals(original, cipher(row));

        assertEquals(
                1,
                count(
                        "select count(*) from users where id = ?",
                        user
                )
        );

        doCallRealMethod().when(cleaner).clean(
                any(),
                anyString(),
                anyString()
        );

        retryNow();

        remove(
                token,
                request,
                kakaoBody(),
                "192.0.2.13"
        ).andExpect(status().isNoContent());

        assertCleared(row);
    }

    @Test
    void emailLimitSurvivesPasswordFailureAndIpKeyAndSessionChanges()
            throws Exception {
        for (int i = 0; i < 3; i++) {
            remove(
                    token,
                    key(),
                    WRONG_BODY,
                    "192.0.2." + (20 + i)
            ).andExpect(status().isUnauthorized());
        }

        String anotherSession = login(user);

        remove(
                anotherSession,
                key(),
                EMAIL_BODY,
                "192.0.2.30"
        )
                .andExpect(status().isTooManyRequests())
                .andExpect(
                        jsonPath("$.error.code").value("RATE_LIMITED")
                );

        verify(passwords, times(3)).matches(
                anyString(),
                anyString()
        );

        assertEquals(3, attempts(600));

        assertEquals(
                "ACTIVE",
                jdbc.queryForObject(
                        "select status from users where id = ?",
                        String.class,
                        user
                )
        );
    }

    @Test
    void parallelPasswordGuessesCannotExceedAccountBudget()
            throws Exception {
        doAnswer(call -> {
            assertFalse(
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            );

            return call.callRealMethod();
        }).when(passwords).matches(
                anyString(),
                anyString()
        );

        try (var executor = Executors.newFixedThreadPool(6)) {
            var start = new CountDownLatch(1);

            var futures =
                    new ArrayList<
                            java.util.concurrent.Future<Integer>
                            >();

            for (int i = 0; i < 6; i++) {
                String ip = "192.0.2." + (40 + i);

                futures.add(
                        executor.submit(() -> {
                            start.await();

                            return remove(
                                    token,
                                    key(),
                                    WRONG_BODY,
                                    ip
                            ).andReturn().getResponse().getStatus();
                        })
                );
            }

            start.countDown();

            var statuses = new ArrayList<Integer>();

            for (var future : futures) {
                statuses.add(
                        future.get(20, TimeUnit.SECONDS)
                );
            }

            assertEquals(
                    3,
                    statuses.stream()
                            .filter(code -> code == 401)
                            .count()
            );

            assertEquals(
                    3,
                    statuses.stream()
                            .filter(code -> code == 429)
                            .count()
            );
        }

        verify(passwords, times(3)).matches(
                anyString(),
                anyString()
        );

        assertEquals(3, attempts(600));
    }

    @Test
    void anotherAccountHasItsOwnBudget() throws Exception {
        for (int i = 0; i < 3; i++) {
            remove(
                    token,
                    key(),
                    WRONG_BODY,
                    "192.0.2." + (50 + i)
            ).andExpect(status().isUnauthorized());
        }

        long other = account();

        remove(
                login(other),
                key(),
                EMAIL_BODY,
                "192.0.2.55"
        ).andExpect(status().isNoContent());

        assertEquals(
                1,
                count(
                        "select count(*) from users where id = ?",
                        user
                )
        );
    }

    @Test
    void tenMinuteBoundaryDoesNotResetHourlyBudget()
            throws Exception {
        for (int i = 0; i < 3; i++) {
            remove(
                    token,
                    key(),
                    WRONG_BODY,
                    "192.0.2." + (60 + i)
            ).andExpect(status().isUnauthorized());
        }

        Instant later = clock.instant().plusSeconds(601);
        doReturn(later).when(clock).instant();

        remove(
                token,
                key(),
                WRONG_BODY,
                "192.0.2.65"
        ).andExpect(status().isUnauthorized());

        remove(
                token,
                key(),
                WRONG_BODY,
                "192.0.2.66"
        ).andExpect(status().isTooManyRequests());

        Instant nextHour =
                Instant.parse("2026-11-01T01:00:01Z");

        doReturn(nextHour).when(clock).instant();

        remove(
                login(user),
                key(),
                WRONG_BODY,
                "192.0.2.67"
        ).andExpect(status().isUnauthorized());
    }

    @Test
    void completedDeletionReplayDoesNotConsumeReauthenticationBudget()
            throws Exception {
        String request = key();

        remove(
                token,
                request,
                EMAIL_BODY,
                "192.0.2.70"
        ).andExpect(status().isNoContent());

        remove(
                token,
                request,
                EMAIL_BODY,
                "192.0.2.71"
        ).andExpect(status().isNoContent());

        assertEquals(1, attempts(600));

        verify(passwords, times(1)).matches(
                anyString(),
                anyString()
        );
    }

    @Test
    void appleAcceptanceFailureRequiresNewAuthenticationInsteadOfRepeating503()
            throws Exception {
        social("APPLE", "apple-user");

        String nonce = challenges.issue().nonce();
        String request = key();
        String body = appleBody(nonce);

        failAcceptance();

        remove(
                token,
                request,
                body,
                "192.0.2.80"
        ).andExpect(status().isServiceUnavailable());

        assertEquals(
                1,
                count(
                        """
                        select count(*) from apple_login_challenges
                        where failed_at is not null
                        """
                )
        );

        assertEquals(
                "ACTIVE",
                jdbc.queryForObject(
                        "select status from users where id = ?",
                        String.class,
                        user
                )
        );

        remove(
                token,
                request,
                body,
                "192.0.2.81"
        )
                .andExpect(status().isUnauthorized())
                .andExpect(
                        jsonPath("$.error.code")
                                .value("REAUTHENTICATION_FAILED")
                );

        verify(apple, times(1)).prepareDeletion(
                anyString(),
                anyString(),
                any()
        );

        doCallRealMethod().when(deletions).start(
                anyString(),
                anyLong(),
                any(),
                any(),
                any(),
                any()
        );

        remove(
                token,
                key(),
                appleBody(challenges.issue().nonce()),
                "192.0.2.82"
        ).andExpect(status().isNoContent());
    }

    @Test
    void inFlightAppleRequestStillReturnsRetryable503AndDoesNotMarkOwnersNonceFailed()
            throws Exception {
        social("APPLE", "apple-user");

        String body = appleBody(
                challenges.issue().nonce()
        );

        String request = key();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        when(
                apple.prepareDeletion(
                        anyString(),
                        anyString(),
                        any()
                )
        ).thenAnswer(call -> {
            entered.countDown();

            assertTrue(
                    release.await(10, TimeUnit.SECONDS)
            );

            return new AppleClient.PreparedDeletion(
                    call.getArgument(2),
                    "refresh-secret"
            );
        });

        try (var executor = Executors.newSingleThreadExecutor()) {
            var owner = executor.submit(() ->
                    remove(
                            token,
                            request,
                            body,
                            "192.0.2.83"
                    ).andReturn().getResponse().getStatus()
            );

            try {
                assertTrue(
                        entered.await(5, TimeUnit.SECONDS)
                );

                remove(
                        token,
                        request,
                        body,
                        "192.0.2.84"
                ).andExpect(status().isServiceUnavailable());

                verify(
                        challengeTarget,
                        never()
                ).fail(
                        anyString(),
                        anyString()
                );
            } finally {
                release.countDown();
            }

            assertEquals(
                    204,
                    owner.get(20, TimeUnit.SECONDS)
            );
        }

        remove(
                token,
                request,
                body,
                "192.0.2.85"
        ).andExpect(status().isNoContent());

        verify(apple, times(1)).prepareDeletion(
                anyString(),
                anyString(),
                any()
        );
    }

    @Test
    void failureMarkerStorageFailureHasBoundedWait()
            throws Exception {
        social("APPLE", "apple-user");

        String body = appleBody(
                challenges.issue().nonce()
        );

        String request = key();

        failAcceptance();

        doThrow(
                new TransientDataAccessResourceException(PRIVATE)
        ).when(challengeTarget).fail(
                anyString(),
                anyString()
        );

        remove(
                token,
                request,
                body,
                "192.0.2.86"
        ).andExpect(status().isServiceUnavailable());

        remove(
                token,
                request,
                body,
                "192.0.2.87"
        ).andExpect(status().isServiceUnavailable());

        Instant later = clock.instant().plusSeconds(61);
        doReturn(later).when(clock).instant();

        remove(
                token,
                request,
                body,
                "192.0.2.88"
        )
                .andExpect(status().isUnauthorized())
                .andExpect(
                        jsonPath("$.error.code")
                                .value("REAUTHENTICATION_FAILED")
                );

        verify(apple, times(1)).prepareDeletion(
                anyString(),
                anyString(),
                any()
        );
    }

    @ParameterizedTest
    @ValueSource(longs = {59, 60, 61})
    void abandonedAppleClaimHasSixtySecondBoundary(
            long elapsed
    ) throws Exception {
        social("APPLE", "apple-user");

        String nonce = challenges.issue().nonce();
        String body = appleBody(nonce);
        String request = key();

        var actor = new SessionAuthenticationToken(
                new UserPrincipal(user),
                session,
                0,
                List.of(
                        new SimpleGrantedAuthority("ROLE_USER")
                )
        );

        var fingerprint = deletions.fingerprint(
                actor,
                request,
                Member.Delete.parse(
                        mapper.readTree(body)
                )
        );

        String hash = crypto.tokenHash(
                "member-delete:"
                        + fingerprint.actor()
                        + ":"
                        + fingerprint.key()
                        + ":"
                        + fingerprint.body()
        );

        assertTrue(
                challenges.consumeForDeletion(
                        nonce,
                        hash
                ).owned()
        );

        Instant later =
                clock.instant().plusSeconds(elapsed);

        doReturn(later).when(clock).instant();

        remove(
                token,
                request,
                body,
                "192.0.2.89"
        ).andExpect(
                status().is(elapsed < 60 ? 503 : 401)
        );

        verify(apple, never()).prepareDeletion(
                anyString(),
                anyString(),
                any()
        );
    }

    @Test
    void appleProofExpiryBeforeCommitRollsBackAcceptance()
            throws Exception {
        social("APPLE", "apple-user");

        String body = appleBody(
                challenges.issue().nonce()
        );

        doAnswer(call -> {
            call.callRealMethod();

            Instant later =
                    clock.instant().plusSeconds(61);

            doReturn(later).when(clock).instant();

            return null;
        }).when(deletions).start(
                anyString(),
                anyLong(),
                any(),
                any(),
                any(),
                any()
        );

        remove(
                token,
                key(),
                body,
                "192.0.2.90"
        ).andExpect(status().isUnauthorized());

        assertEquals(
                0,
                count("select count(*) from account_deletions")
        );

        assertEquals(
                "ACTIVE",
                jdbc.queryForObject(
                        "select status from users where id = ?",
                        String.class,
                        user
                )
        );

        assertEquals(
                0,
                count(
                        """
                        select count(*) from auth_sessions
                        where user_id = ?
                            and revoked_at is not null
                        """,
                        user
                )
        );
    }

    @Test
    void committedAcceptanceWithLostCommitResponseCanReplayWithoutExchangingAgain()
            throws Exception {
        social("APPLE", "apple-user");

        String body = appleBody(
                challenges.issue().nonce()
        );

        String request = key();
        var committed = new AtomicBoolean();

        doAnswer(call -> {
            call.callRealMethod();
            committed.set(true);
            return null;
        }).when(deletions).start(
                anyString(),
                anyLong(),
                any(),
                any(),
                any(),
                any()
        );

        doAnswer(call -> {
            call.callRealMethod();

            if (committed.getAndSet(false)) {
                throw new TransactionSystemException(PRIVATE);
            }

            return null;
        }).when(manager).commit(any());

        remove(
                token,
                request,
                body,
                "192.0.2.91"
        ).andExpect(status().isServiceUnavailable());

        assertEquals(
                1,
                count(
                        """
                        select count(*) from account_deletions
                        where status = 'PROCESSING'
                        """
                )
        );

        verify(
                challengeTarget,
                never()
        ).fail(
                anyString(),
                anyString()
        );

        remove(
                token,
                request,
                body,
                "192.0.2.92"
        ).andExpect(status().isNoContent());

        verify(apple, times(1)).prepareDeletion(
                anyString(),
                anyString(),
                any()
        );
    }

    private void failAcceptance() {
        doAnswer(call -> {
            call.callRealMethod();
            throw new TransientDataAccessResourceException(PRIVATE);
        }).when(deletions).start(
                anyString(),
                anyLong(),
                any(),
                any(),
                any(),
                any()
        );
    }

    private long account() {
        long id = insert(
                "insert into users(nickname) values ('리뷰보완')"
        );

        users.add(id);

        String email = key() + "@example.invalid";

        jdbc.update(
                """
                insert into auth_identities(
                    user_id, provider, provider_subject,
                    email, password_hash
                )
                values (?, 'EMAIL', ?, ?, ?)
                """,
                id,
                email,
                email,
                encoded
        );

        return id;
    }

    private String login(long id) {
        session = key();

        Instant now = clock.instant()
                .truncatedTo(ChronoUnit.SECONDS);

        jdbc.update(
                """
                insert into auth_sessions(
                    id, user_id, created_at, expires_at
                )
                values (?, ?, ?, ?)
                """,
                session,
                id,
                utc(now),
                utc(now.plusSeconds(3600))
        );

        return tokens.issue(
                id,
                session,
                0,
                now,
                now.plusSeconds(3600)
        );
    }

    private void social(
            String provider,
            String subject
    ) {
        jdbc.update(
                """
                update auth_identities
                set provider = ?,
                    provider_subject = ?,
                    email = null,
                    password_hash = null
                where user_id = ?
                """,
                provider,
                subject,
                user
        );
    }

    private long legacy(
            String provider,
            String subject,
            String payload
    ) {
        String scope = crypto.tokenHash(key());
        String request = crypto.tokenHash(key());

        String operation = "POST /v1/auth/social/"
                + provider.toLowerCase(java.util.Locale.ROOT);

        String encrypted = crypto.encrypt(
                mapper.writeValueAsString(
                        Map.of(
                                "subject", subject,
                                "response", Map.of()
                        )
                ),
                scope + ":" + operation + ":" + request
        );

        long id = insert(
                """
                insert into api_idempotency_requests(
                    scope_hash, operation, key_hash, body_hmac,
                    status, response_payload,
                    encrypted_response, response_key_version,
                    created_at, expires_at
                )
                values (?, ?, ?, ?, 'SUCCEEDED', ?, ?, 'v1', ?, ?)
                """,
                scope,
                operation,
                request,
                crypto.tokenHash(key()),
                mapper.writeValueAsString(
                        Map.of("marker", payload)
                ),
                encrypted,
                utc(clock.instant().minusSeconds(60)),
                utc(clock.instant().plusSeconds(3600))
        );

        responses.add(id);

        return id;
    }

    private String context(long id) {
        return jdbc.queryForObject(
                """
                select concat(
                    scope_hash, ':', operation, ':', key_hash
                )
                from api_idempotency_requests where id = ?
                """,
                String.class,
                id
        );
    }

    private String cipher(long id) {
        return jdbc.queryForObject(
                """
                select encrypted_response
                from api_idempotency_requests where id = ?
                """,
                String.class,
                id
        );
    }

    private void assertCleared(long id) {
        assertEquals(
                0,
                count(
                        """
                        select count(*) from api_idempotency_requests
                        where id = ? and (
                            encrypted_response is not null
                            or response_payload is not null
                            or response_key_version is not null
                        )
                        """,
                        id
                )
        );
    }

    private void retryNow() {
        jdbc.update(
                """
                update account_deletions set available_at = ?
                where user_id = ?
                """,
                utc(clock.instant()),
                user
        );
    }

    private long attempts(int seconds) {
        String hash = hmac.hmac(
                seconds == 600
                        ? "member-delete-email-ten-minutes"
                        : "member-delete-email-hour",
                Long.toString(user),
                Long.toString(
                        Math.floorDiv(
                                clock.instant().getEpochSecond(),
                                seconds
                        )
                )
        );

        return count(
                """
                select request_count from auth_request_guards
                where guard_hash = ?
                """,
                hash
        );
    }

    private String appleBody(String nonce) {
        return mapper.writeValueAsString(
                Map.of(
                        "confirm", true,
                        "socialCredential", Map.of(
                                "identityToken", "a.b.c",
                                "authorizationCode", "code",
                                "nonce", nonce
                        )
                )
        );
    }

    private String kakaoBody() {
        return """
                {
                  "confirm": true,
                  "socialCredential": {
                    "accessToken": "fresh-token"
                  }
                }
                """;
    }

    private ResultActions remove(
            String credential,
            String request,
            String body,
            String ip
    ) throws Exception {
        return mvc.perform(
                delete("/v1/users/me")
                        .header(
                                "Authorization",
                                "Bearer " + credential
                        )
                        .header("Idempotency-Key", request)
                        .contentType("application/json")
                        .content(body)
                        .with(http -> {
                            http.setRemoteAddr(ip);
                            return http;
                        })
        );
    }

    private long count(
            String sql,
            Object... args
    ) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        sql,
                        Long.class,
                        args
                )
        );
    }

    private long insert(
            String sql,
            Object... args
    ) {
        var keys = new GeneratedKeyHolder();

        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    sql,
                    Statement.RETURN_GENERATED_KEYS
            );

            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }

            return statement;
        }, keys);

        return Objects.requireNonNull(
                keys.getKey()
        ).longValue();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}