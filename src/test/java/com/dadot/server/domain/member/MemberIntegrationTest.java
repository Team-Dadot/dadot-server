package com.dadot.server.domain.member;

import com.dadot.server.domain.auth.service.AppleLoginChallengeService;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.member.repository.AccountDeletionRepository;
import com.dadot.server.domain.member.repository.MemberRepository;
import com.dadot.server.domain.member.service.AccountDeletionWorker;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.AccessTokenIssuer;
import com.dadot.server.infrastructure.apple.AppleClient;
import com.dadot.server.infrastructure.kakao.KakaoClient;
import com.dadot.server.infrastructure.kakao.KakaoAccountClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.dadot.server.domain.member.repository.MemberRepository.utc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.account-deletion.worker-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MemberIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired PasswordEncoder passwords;
    @Autowired AccessTokenIssuer tokens;
    @Autowired VerificationTokenCrypto crypto;
    @Autowired AppleLoginChallengeService challenges;
    @Autowired AccountDeletionWorker worker;
    @Autowired MemberRepository members;
    @Autowired PlatformTransactionManager manager;

    @Autowired
    com.dadot.server.domain.auth.repository.SocialLoginRequestStore socialRequests;

    @Autowired
    com.dadot.server.domain.auth.repository.VerificationRequestStore emailRequests;

    @Autowired
    com.dadot.server.domain.auth.repository.VerificationConfirmationRequestStore confirmations;

    @Autowired
    com.dadot.server.domain.interview.repository.InterviewEvaluationJobRepository evaluationJobs;

    @MockitoSpyBean AccountDeletionRepository deletions;
    @MockitoSpyBean Clock clock;
    @MockitoBean AppleClient apple;
    @MockitoBean KakaoClient kakao;
    @MockitoBean KakaoAccountClient kakaoAccounts;

    private final List<Long> users = new ArrayList<>();
    private final List<Long> companies = new ArrayList<>();
    private long user;
    private String token, session, email;
    private final String password = "Member123!";
    private String encoded;
    private boolean safe;

    @BeforeEach
    void setup() {
        safe = false;
        assertEquals(
                "dadot_test_db",
                jdbc.queryForObject("select database()", String.class)
        );
        safe = true;

        reset(clock, deletions, apple, kakao, kakaoAccounts);
        doReturn(
                Instant.parse("2026-11-01T00:00:00Z")
        ).when(clock).instant();

        users.clear();
        companies.clear();
        encoded = passwords.encode(password);

        user = account(
                "EMAIL",
                "member-" + key() + "@example.invalid"
        );

        email = jdbc.queryForObject(
                """
                select provider_subject from auth_identities
                where user_id = ?
                """,
                String.class,
                user
        );

        token = login(user);

        jdbc.update(
                "insert into user_onboarding(user_id) values (?)",
                user
        );
    }

    @AfterEach
    void cleanup() {
        if (!safe) return;

        reset(deletions, apple, kakao, kakaoAccounts);

        for (long id : users) {
            if (count(
                    "select count(*) from users where id = ?",
                    id
            ) == 0) {
                continue;
            }

            var identity = members.identity(id, false);

            new TransactionTemplate(manager).executeWithoutResult(tx -> {
                if (count(
                        """
                        select count(*) from account_deletions
                        where user_id = ?
                        """,
                        id
                ) == 0) {
                    deletions.start(
                            key(),
                            id,
                            new AccountDeletionRepository.Fingerprint(
                                    key(), key(), key()
                            ),
                            identity,
                            "test-refresh",
                            clock.instant()
                    );
                }

                jdbc.update(
                        """
                        update account_deletions
                        set provider_revoked = true,
                            available_at = '2000-01-01',
                            lease_owner = null,
                            lease_until = null
                        where user_id = ?
                        """,
                        id
                );
            });

            String idToDelete = jdbc.queryForObject(
                    "select id from account_deletions where user_id = ?",
                    String.class,
                    id
            );

            worker.process(idToDelete);

            assertEquals(
                    0,
                    count("select count(*) from users where id = ?", id)
            );
        }

        jdbc.update("delete from account_deletions");
        jdbc.update("delete from auth_request_guards");
        jdbc.update("delete from apple_login_challenges");

        for (long company : companies) {
            jdbc.update(
                    """
                    update quiz_questions set published_version_no = null
                    where company_id = ?
                    """,
                    company
            );
            jdbc.update(
                    """
                    delete o from quiz_options o
                    join quiz_question_versions v on v.id = o.quiz_version_id
                    where v.company_id = ?
                    """,
                    company
            );
            jdbc.update(
                    "delete from quiz_evidence where company_id = ?",
                    company
            );
            jdbc.update(
                    "delete from quiz_question_versions where company_id = ?",
                    company
            );
            jdbc.update(
                    "delete from quiz_questions where company_id = ?",
                    company
            );
            jdbc.update(
                    """
                    update interview_policies set active_version_no = null
                    where company_id = ?
                    """,
                    company
            );
            jdbc.update(
                    """
                    delete c from evaluation_criteria c
                    join interview_policy_versions v
                        on v.id = c.policy_version_id
                    join interview_policies p on p.id = v.policy_id
                    where p.company_id = ?
                    """,
                    company
            );
            jdbc.update(
                    """
                    delete v from interview_policy_versions v
                    join interview_policies p on p.id = v.policy_id
                    where p.company_id = ?
                    """,
                    company
            );
            jdbc.update(
                    "delete from interview_policies where company_id = ?",
                    company
            );
            jdbc.update(
                    "delete from disclosure_sections where company_id = ?",
                    company
            );
            jdbc.update(
                    "delete from disclosure_versions where company_id = ?",
                    company
            );
            jdbc.update(
                    "delete from disclosures where company_id = ?",
                    company
            );
            jdbc.update("delete from companies where id = ?", company);
        }

        reset(clock);
    }

    @Test
    void summaryReturnsActualIdentityCountsAndNotificationDefaults()
            throws Exception {
        material("EXPERIENCE");
        material("EXPERIENCE");
        material("COVER_LETTER");

        summary(token)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.userId").value(user))
                .andExpect(jsonPath("$.data.provider").value("EMAIL"))
                .andExpect(jsonPath("$.data.email").value(email))
                .andExpect(
                        jsonPath("$.data.onboardingCompleted").value(false)
                )
                .andExpect(jsonPath("$.data.experienceCount").value(2))
                .andExpect(jsonPath("$.data.coverLetterCount").value(1))
                .andExpect(
                        jsonPath("$.data.notificationSettings.enabled")
                                .value(false)
                )
                .andExpect(
                        jsonPath("$.data.notificationSettings.version")
                                .value(1)
                )
                .andExpect(
                        jsonPath("$.data.notificationSettings.currentDevice")
                                .value(org.hamcrest.Matchers.nullValue())
                )
                .andExpect(jsonPath("$.data.profileImageUrl").doesNotExist());
    }

    @Test
    void summaryReadsSavedSettingsAndOnlyCurrentSessionDevice()
            throws Exception {
        jdbc.update(
                """
                insert into notification_settings(
                    user_id, enabled, scheduled_time, version
                )
                values (?, true, '09:30', 4)
                """,
                user
        );

        String installation = key();

        jdbc.update(
                """
                insert into user_devices(
                    installation_id, user_id, session_id,
                    platform, permission,
                    push_token_ciphertext, push_token_digest
                )
                values (?, ?, ?, 'IOS', 'GRANTED', 'cipher', ?)
                """,
                installation,
                user,
                session,
                "a".repeat(64)
        );

        summary(token)
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.data.notificationSettings.scheduledTime"
                        ).value("09:30")
                )
                .andExpect(
                        jsonPath(
                                "$.data.notificationSettings.version"
                        ).value(4)
                )
                .andExpect(
                        jsonPath(
                                "$.data.notificationSettings.currentDevice.installationId"
                        ).value(installation)
                )
                .andExpect(
                        jsonPath(
                                "$.data.notificationSettings.currentDevice.registered"
                        ).value(true)
                );

        String second = login(user);

        summary(second)
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.data.notificationSettings.currentDevice"
                        ).value(org.hamcrest.Matchers.nullValue())
                );
    }

    @Test
    void socialEmailMayBeNullAndNicknameDoesNotChangeIdentity()
            throws Exception {
        jdbc.update(
                """
                update auth_identities
                set provider = 'APPLE',
                    provider_subject = 'apple-user',
                    email = null,
                    password_hash = null
                where user_id = ?
                """,
                user
        );

        summary(token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("APPLE"))
                .andExpect(
                        jsonPath("$.data.email")
                                .value(org.hamcrest.Matchers.nullValue())
                );

        nickname("{\"nickname\":\"새닉네임😀\"}")
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.nickname").value("새닉네임😀")
                );

        assertEquals(
                "apple-user",
                members.identity(user, false).subject()
        );
    }

    @Test
    void unicodeNicknameBoundaryCountsCodePoints() throws Exception {
        nickname(mapper.writeValueAsString(
                java.util.Map.of("nickname", "😀".repeat(20))
        )).andExpect(status().isOk());

        nickname(mapper.writeValueAsString(
                java.util.Map.of("nickname", "😀".repeat(21))
        )).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "null",
            "[]",
            "{\"nickname\":null}",
            "{\"nickname\":1}",
            "{\"nickname\":\"\"}",
            "{\"nickname\":\"  \"}",
            "{\"nickname\":\" 앞공백\"}",
            "{\"nickname\":\"뒤공백 \"}",
            "{\"nickname\":\"a\\nb\"}",
            "{\"nickname\":\"a\\u200bb\"}",
            "{\"nickname\":\"a\\ud800\"}",
            "{\"nickname\":\"a\",\"imageAction\":\"REMOVE\"}",
            "{\"nickname\":\"a\",\"nickname\":\"b\"}",
            "{\"nickname\":\"a\"} {}"
    })
    void rejectsInvalidNicknameAndImageFields(String body)
            throws Exception {
        nickname(body).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMultipartQueriesReadBodiesAndDuplicateKeys()
            throws Exception {
        mvc.perform(
                        patch("/v1/users/me/profile")
                                .header("Authorization", "Bearer " + token)
                                .contentType(
                                        "multipart/form-data;boundary=test"
                                )
                )
                .andExpect(status().isUnsupportedMediaType());

        mvc.perform(
                        get("/v1/users/me?extra=1")
                                .header("Authorization", "Bearer " + token)
                )
                .andExpect(status().isBadRequest());

        mvc.perform(
                        get("/v1/users/me")
                                .header("Authorization", "Bearer " + token)
                                .content("{}")
                )
                .andExpect(status().isBadRequest());

        mvc.perform(
                        delete("/v1/users/me")
                                .header("Authorization", "Bearer " + token)
                                .header("Idempotency-Key", key(), key())
                                .contentType("application/json")
                                .content(emailBody())
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void allThreeEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/v1/users/me"))
                .andExpect(status().isUnauthorized());

        mvc.perform(
                        patch("/v1/users/me/profile")
                                .contentType("application/json")
                                .content("{\"nickname\":\"변경\"}")
                )
                .andExpect(status().isUnauthorized());

        mvc.perform(
                        delete("/v1/users/me")
                                .contentType("application/json")
                                .content(emailBody())
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordDoesNotBlockAccountOrCreateDeletion()
            throws Exception {
        remove(
                token,
                key(),
                "{\"confirm\":true,\"password\":\"Wrong123!\"}"
        )
                .andExpect(status().isUnauthorized())
                .andExpect(
                        jsonPath("$.error.code")
                                .value("REAUTHENTICATION_FAILED")
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
                count("select count(*) from account_deletions")
        );

        summary(token).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "null",
            "{\"confirm\":false,\"password\":\"Member123!\"}",
            "{\"confirm\":\"true\",\"password\":\"Member123!\"}",
            "{\"confirm\":true}",
            "{\"confirm\":true,\"password\":\"Member123!\",\"socialCredential\":{\"accessToken\":\"x\"}}",
            "{\"confirm\":true,\"socialCredential\":{\"identityToken\":\"a.b.c\",\"authorizationCode\":\"code\"}}",
            "{\"confirm\":true,\"password\":\"Member123!\",\"unknown\":1}",
            "{\"confirm\":false,\"confirm\":true,\"password\":\"Member123!\"}"
    })
    void deletionStrictlyValidatesInput(String body) throws Exception {
        remove(token, key(), body)
                .andExpect(status().isBadRequest());

        assertEquals(
                1,
                count("select count(*) from users where id = ?", user)
        );
    }

    @Test
    void emailDeletionRemovesMaterialsProofsSessionsAndCanReplayOnlyDeletion()
            throws Exception {
        material("EXPERIENCE");
        material("COVER_LETTER");

        String second = login(user);
        login(user);

        String verification = key();

        jdbc.update(
                """
                insert into email_verifications(
                    id, email, purpose, code_digest, sent_at, expires_at
                )
                values (?, ?, 'SIGN_UP', ?, ?, ?)
                """,
                verification,
                email,
                "0".repeat(64),
                utc(clock.instant()),
                utc(clock.instant().plusSeconds(300))
        );

        jdbc.update(
                """
                insert into auth_proofs(
                    id, verification_id, purpose, provider,
                    subject_email, token_hash, issued_at, expires_at
                )
                values (?, ?, 'SIGN_UP', 'EMAIL', ?, ?, ?, ?)
                """,
                key(),
                verification,
                email,
                "1".repeat(64),
                utc(clock.instant()),
                utc(clock.instant().plusSeconds(300))
        );

        String request = key();

        remove(token, request, emailBody())
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(
                        header().string("Cache-Control", "no-store")
                );

        for (String table : List.of(
                "users",
                "auth_identities",
                "auth_sessions",
                "user_onboarding",
                "personal_materials",
                "material_versions"
        )) {
            assertEquals(
                    0,
                    count(
                            "select count(*) from "
                                    + table
                                    + " where "
                                    + (
                                    table.equals("users")
                                            ? "id"
                                            : "user_id"
                            )
                                    + " = ?",
                            user
                    )
            );
        }

        assertEquals(
                0,
                count(
                        """
                        select count(*) from auth_proofs
                        where subject_email = ?
                        """,
                        email
                )
        );

        assertEquals(
                0,
                count(
                        """
                        select count(*) from email_verifications
                        where email = ?
                        """,
                        email
                )
        );

        remove(token, request.toUpperCase(), emailBody())
                .andExpect(status().isNoContent());

        remove(
                token,
                request,
                "{\"confirm\":true,\"password\":\"Changed123!\"}"
        ).andExpect(status().isConflict());

        remove(token, key(), emailBody())
                .andExpect(status().isConflict());

        summary(token).andExpect(status().isForbidden());

        mvc.perform(
                        get("/v1/companies")
                                .header("Authorization", "Bearer " + token)
                )
                .andExpect(status().isForbidden());

        summary(second).andExpect(status().isUnauthorized());

        assertEquals(
                0,
                count(
                        """
                        select count(*) from account_deletions
                        where user_id is not null
                            or encrypted_credentials is not null
                            or provider is not null
                        """
                )
        );

        verifyNoInteractions(apple, kakao, kakaoAccounts);
    }

    @Test
    void pendingKakaoUnlinkPersistsEncryptedDataAndWorkerRetriesWithoutUserSession()
            throws Exception {
        toSocial("KAKAO", "12345");

        Instant validUntil = clock.instant().plusSeconds(300);

        when(kakao.verify("fresh-token")).thenReturn(
                new KakaoClient.Verified("12345", validUntil)
        );

        doAnswer(call -> {
            assertFalse(
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            );
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }).when(kakaoAccounts).unlink("12345");

        String request = key();
        String body = """
                {
                  "confirm": true,
                  "socialCredential": {
                    "accessToken": "fresh-token"
                  }
                }
                """;

        remove(token, request, body)
                .andExpect(status().isServiceUnavailable())
                .andExpect(
                        jsonPath("$.error.code")
                                .value("ACCOUNT_DELETION_PENDING")
                );

        assertEquals(
                "DELETING",
                jdbc.queryForObject(
                        "select status from users where id = ?",
                        String.class,
                        user
                )
        );

        summary(token).andExpect(status().isForbidden());

        String encrypted = jdbc.queryForObject(
                """
                select encrypted_credentials from account_deletions
                where user_id = ?
                """,
                String.class,
                user
        );

        assertFalse(encrypted.contains("12345"));
        assertFalse(encrypted.contains("fresh-token"));

        remove(token, request, body)
                .andExpect(status().isServiceUnavailable());

        verify(kakaoAccounts, times(1)).unlink("12345");

        doNothing().when(kakaoAccounts).unlink("12345");

        jdbc.update(
                """
                update account_deletions set available_at = ?
                where user_id = ?
                """,
                utc(clock.instant()),
                user
        );

        worker.process(
                jdbc.queryForObject(
                        "select id from account_deletions where user_id = ?",
                        String.class,
                        user
                )
        );

        remove(token, request, body)
                .andExpect(status().isNoContent());

        verify(kakao, times(1)).verify("fresh-token");
    }

    @Test
    void appleUsesFreshNonceExchangesOnceAndRevokesOutsideTransaction()
            throws Exception {
        toSocial("APPLE", "apple-member");

        String nonce = challenges.issue().nonce();

        var identity = new AppleClient.Verified(
                "apple-member",
                clock.instant().plusSeconds(300),
                null
        );

        when(
                apple.verifyIdentity("a.b.c", nonce)
        ).thenReturn(identity);

        when(
                apple.prepareDeletion("code", nonce, identity)
        ).thenAnswer(call -> {
            assertFalse(
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            );

            return new AppleClient.PreparedDeletion(
                    identity,
                    "refresh-secret"
            );
        });

        doAnswer(call -> {
            assertFalse(
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            );
            return null;
        }).when(apple).revoke("refresh-secret");

        String body = mapper.writeValueAsString(
                java.util.Map.of(
                        "confirm", true,
                        "socialCredential", java.util.Map.of(
                                "identityToken", "a.b.c",
                                "authorizationCode", "code",
                                "nonce", nonce
                        )
                )
        );

        String request = key();

        remove(token, request, body)
                .andExpect(status().isNoContent());

        remove(token, request, body)
                .andExpect(status().isNoContent());

        verify(apple, times(1)).prepareDeletion(
                "code", nonce, identity
        );

        verify(apple, times(1)).revoke("refresh-secret");
    }

    @Test
    void socialSubjectMismatchCannotDeleteAccount() throws Exception {
        toSocial("KAKAO", "12345");

        Instant validUntil = clock.instant().plusSeconds(300);

        when(kakao.verify(anyString())).thenReturn(
                new KakaoClient.Verified("99999", validUntil)
        );

        remove(
                token,
                key(),
                "{\"confirm\":true,\"socialCredential\":{\"accessToken\":\"other-token\"}}"
        )
                .andExpect(status().isUnauthorized())
                .andExpect(
                        jsonPath("$.error.code")
                                .value("REAUTHENTICATION_FAILED")
                );

        assertEquals(
                0,
                count("select count(*) from account_deletions")
        );
    }

    @Test
    void localFailureRollsBackDeletionButKeepsAccessBlockedAndRetryable()
            throws Exception {
        material("EXPERIENCE");

        doAnswer(call -> {
            call.callRealMethod();
            throw new TransientDataAccessResourceException("private-SQL");
        }).when(deletions).purge(any(), any(), any());

        String request = key();

        remove(token, request, emailBody())
                .andExpect(status().isServiceUnavailable());

        assertEquals(
                1,
                count(
                        """
                        select count(*) from personal_materials
                        where user_id = ?
                        """,
                        user
                )
        );

        assertEquals(
                "DELETING",
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
                        select count(*) from account_deletions
                        where status = 'SUCCEEDED'
                        """
                )
        );

        doCallRealMethod().when(deletions).purge(any(), any(), any());

        jdbc.update(
                """
                update account_deletions set available_at = ?
                where user_id = ?
                """,
                utc(clock.instant()),
                user
        );

        remove(token, request, emailBody())
                .andExpect(status().isNoContent());
    }

    @Test
    void concurrentIdenticalRequestsDeleteOnce() throws Exception {
        String request = key();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);

            var first = executor.submit(() -> {
                start.await();
                return remove(
                        token, request, emailBody()
                ).andReturn().getResponse().getStatus();
            });

            var second = executor.submit(() -> {
                start.await();
                return remove(
                        token, request, emailBody()
                ).andReturn().getResponse().getStatus();
            });

            start.countDown();

            assertTrue(
                    List.of(204, 503).contains(
                            first.get(20, TimeUnit.SECONDS)
                    )
            );

            assertTrue(
                    List.of(204, 503).contains(
                            second.get(20, TimeUnit.SECONDS)
                    )
            );
        }

        remove(token, request, emailBody())
                .andExpect(status().isNoContent());

        assertEquals(
                1,
                count(
                        """
                        select count(*) from account_deletions
                        where status = 'SUCCEEDED'
                        """
                )
        );
    }

    @Test
    void deletionDoesNotTouchAnotherMemberAndExpiredJwtCannotReplay()
            throws Exception {
        long other = account(
                "EMAIL",
                "other-" + key() + "@example.invalid"
        );

        String otherToken = login(other);
        String request = key();

        remove(token, request, emailBody())
                .andExpect(status().isNoContent());

        summary(otherToken).andExpect(status().isOk());

        doReturn(
                clock.instant().plusSeconds(3601)
        ).when(clock).instant();

        remove(token, request, emailBody())
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deletesCompleteLearningGraphAndPreservesCommonContent()
            throws Exception {
        String task = learningGraph();

        var stale =
                new com.dadot.server.domain.interview.model.InterviewEvaluation.Claim(
                        task, "old-worker", 1, user, 1, 1
                );

        remove(token, key(), emailBody())
                .andExpect(status().isNoContent());

        for (String table : List.of(
                "interview_evaluations",
                "interview_questions",
                "interview_attempts",
                "interview_submissions",
                "interview_daily_questions",
                "interview_question_keys",
                "interview_drafts",
                "ai_context_materials",
                "ai_contexts",
                "ai_tasks",
                "quiz_answers",
                "quiz_set_items",
                "quiz_sets",
                "user_companies"
        )) {
            assertEquals(
                    0,
                    count(
                            "select count(*) from "
                                    + table
                                    + " where user_id = ?",
                            user
                    ),
                    table
            );
        }

        assertEquals(
                1,
                count(
                        """
                        select count(*) from quiz_questions
                        where company_id = ?
                        """,
                        companies.getFirst()
                )
        );

        assertEquals(
                1,
                count(
                        """
                        select count(*) from disclosures
                        where company_id = ?
                        """,
                        companies.getFirst()
                )
        );

        assertEquals(
                2,
                count(
                        """
                        select count(*) from interview_policies
                        where company_id = ?
                        """,
                        companies.getFirst()
                )
        );

        new TransactionTemplate(manager).executeWithoutResult(tx ->
                evaluationJobs.fail(
                        stale,
                        new com.dadot.server.domain.interview.model.InterviewGeneration.Failure(
                                "AI_CONNECTION_ERROR",
                                true,
                                0,
                                null
                        ),
                        clock.instant()
                )
        );

        assertEquals(
                0,
                count(
                        "select count(*) from ai_tasks where user_id = ?",
                        user
                )
        );
    }

    @Test
    void cancelledTasksCannotBeCompletedWhileAccountDeletionIsPending()
            throws Exception {
        String task = learningGraph();

        doThrow(
                new TransientDataAccessResourceException("private SQL")
        ).when(deletions).purge(any(), any(), any());

        remove(token, key(), emailBody())
                .andExpect(status().isServiceUnavailable());

        assertEquals(
                "CANCELLED",
                jdbc.queryForObject(
                        "select status from ai_tasks where id = ?",
                        String.class,
                        task
                )
        );

        assertEquals(
                0,
                count(
                        """
                        select count(*) from ai_tasks
                        where user_id = ? and lease_owner is not null
                        """,
                        user
                )
        );

        assertEquals(
                0,
                count(
                        """
                        select count(*) from ai_contexts
                        where user_id = ? and invalidated_at is null
                        """,
                        user
                )
        );
    }

    @Test
    void expiredDeletionLeaseCannotCommitAndCanBeReclaimed()
            throws Exception {
        var identity = members.identity(user, false);
        String id = key();

        new TransactionTemplate(manager).executeWithoutResult(tx ->
                deletions.start(
                        id,
                        user,
                        new AccountDeletionRepository.Fingerprint(
                                "a".repeat(64),
                                "b".repeat(64),
                                "c".repeat(64)
                        ),
                        identity,
                        null,
                        clock.instant()
                )
        );

        var old = new TransactionTemplate(manager).execute(tx ->
                deletions.claim(
                        id, key(), clock.instant()
                ).orElseThrow()
        );

        assertNotNull(old);

        Instant future = clock.instant().plusSeconds(121);

        doReturn(future).when(clock).instant();

        assertThrows(
                RuntimeException.class,
                () -> new TransactionTemplate(manager)
                        .executeWithoutResult(tx ->
                                deletions.purge(
                                        old, identity, clock.instant()
                                )
                        )
        );

        assertEquals(
                1,
                count("select count(*) from users where id = ?", user)
        );

        worker.process(id);

        assertEquals(
                0,
                count("select count(*) from users where id = ?", user)
        );
    }

    @Test
    void removesCurrentAndLegacyPreSignupSocialResponsesWithoutTouchingAnotherSubject()
            throws Exception {
        toSocial("KAKAO", "12345");

        Instant validUntil = clock.instant().plusSeconds(300);

        when(kakao.verify("fresh-token")).thenReturn(
                new KakaoClient.Verified("12345", validUntil)
        );

        int index = 0;

        for (String subject : List.of("12345", "12345", "67890")) {
            var request = socialRequests.key(key(), key());

            socialRequests.complete(
                    request,
                    new com.dadot.server.domain.auth.repository.SocialLoginRequestStore.Result(
                            subject,
                            com.dadot.server.domain.auth.dto.response.SocialLoginResponse.signup(
                                    "registration-secret",
                                    validUntil
                            )
                    ),
                    clock.instant()
            );

            if (index++ == 0) {
                jdbc.update(
                        """
                        update api_idempotency_requests
                        set subject_hash = null where key_hash = ?
                        """,
                        request.keyHash()
                );
            }
        }

        remove(
                token,
                key(),
                "{\"confirm\":true,\"socialCredential\":{\"accessToken\":\"fresh-token\"}}"
        ).andExpect(status().isNoContent());

        assertEquals(
                1,
                count(
                        """
                        select count(*) from api_idempotency_requests
                        where user_id is null
                            and encrypted_response is not null
                            and operation = 'POST /v1/auth/social/kakao'
                        """
                )
        );

        jdbc.update(
                """
                delete from api_idempotency_requests
                where operation = 'POST /v1/auth/social/kakao'
                """
        );
    }

    @Test
    void scrubsPreSignupEmailResponsesAndKeepsAnotherEmailsResponse()
            throws Exception {
        String otherEmail = key() + "@example.invalid";
        var keys = new ArrayList<String>();

        try {
            for (String address : List.of(email, otherEmail)) {
                String verification = key();
                String issueKey = crypto.tokenHash(key());
                String confirmKey = crypto.tokenHash(key());

                keys.add(issueKey);
                keys.add(confirmKey);

                jdbc.update(
                        """
                        insert into email_verifications(
                            id, email, purpose, code_digest,
                            sent_at, expires_at
                        )
                        values (?, ?, 'SIGN_UP', ?, ?, ?)
                        """,
                        verification,
                        address,
                        "0".repeat(64),
                        utc(clock.instant()),
                        utc(clock.instant().plusSeconds(300))
                );

                emailRequests.complete(
                        issueKey,
                        "b".repeat(64),
                        null,
                        new com.dadot.server.domain.auth.dto.response.IssueEmailVerificationResponse(
                                verification,
                                clock.instant().plusSeconds(300),
                                clock.instant().plusSeconds(60)
                        ),
                        clock.instant()
                );

                confirmations.saveSuccess(
                        "POST /v1/auth/email-verifications/"
                                + verification
                                + "/verify",
                        confirmKey,
                        "b".repeat(64),
                        null,
                        new com.dadot.server.domain.auth.dto.response.VerifyEmailVerificationResponse(
                                "private-verification-token",
                                clock.instant().plusSeconds(300)
                        ),
                        clock.instant()
                );
            }

            remove(token, key(), emailBody())
                    .andExpect(status().isNoContent());

            for (String ownKey : keys.subList(0, 2)) {
                assertEquals(
                        0,
                        count(
                                """
                                select count(*)
                                from api_idempotency_requests
                                where key_hash = ? and (
                                    response_payload is not null
                                    or encrypted_response is not null
                                )
                                """,
                                ownKey
                        )
                );
            }

            for (String otherKey : keys.subList(2, 4)) {
                assertEquals(
                        1,
                        count(
                                """
                                select count(*)
                                from api_idempotency_requests
                                where key_hash = ? and (
                                    response_payload is not null
                                    or encrypted_response is not null
                                )
                                """,
                                otherKey
                        )
                );
            }
        } finally {
            for (String requestKey : keys) {
                jdbc.update(
                        """
                        delete from api_idempotency_requests
                        where key_hash = ?
                        """,
                        requestKey
                );
            }

            jdbc.update(
                    "delete from email_verifications where email = ?",
                    otherEmail
            );
        }
    }

    private String learningGraph() {
        long company = insert(
                """
                insert into companies(
                    dart_corp_code, name, is_service_company
                )
                values ('99005600', '회원검증기업', true)
                """
        );

        companies.add(company);

        jdbc.update(
                """
                insert into user_companies(
                    user_id, company_id, active_slot, custom_job_name
                )
                values (?, ?, 1, '개발')
                """,
                user,
                company
        );

        jdbc.update(
                """
                insert into user_preferences(
                    user_id, selected_company_id
                )
                values (?, ?)
                """,
                user,
                company
        );

        long disclosure = insert(
                """
                insert into disclosures(
                    company_id, receipt_no, report_name,
                    report_type, disclosed_on, source_url
                )
                values (
                    ?, ?, '사업보고서', 'ANNUAL',
                    '2026-10-01', 'https://dart.fss.or.kr/'
                )
                """,
                company,
                key().substring(0, 20)
        );

        long dv = insert(
                """
                insert into disclosure_versions(
                    disclosure_id, company_id, version_no,
                    content_hash, extractor_version
                )
                values (?, ?, 1, ?, 'test')
                """,
                disclosure,
                company,
                "a".repeat(64)
        );

        long section = insert(
                """
                insert into disclosure_sections(
                    disclosure_version_id, company_id,
                    section_key, content_text
                )
                values (?, ?, 'business', '기업 사업')
                """,
                dv,
                company
        );

        long generation = policy(company, "GENERATION");
        long evaluation = policy(company, "EVALUATION");
        String context = key();
        String generationTask = key();

        jdbc.update(
                """
                insert into ai_contexts(
                    id, user_id, company_id, job_is_custom,
                    job_name_snapshot, personalization_version
                )
                values (?, ?, ?, true, '개발', 0)
                """,
                context,
                user,
                company
        );

        material("EXPERIENCE");

        long mv = jdbc.queryForObject(
                "select max(id) from material_versions where user_id = ?",
                Long.class,
                user
        );

        jdbc.update(
                """
                insert into ai_context_materials(
                    context_id, material_version_id, user_id
                )
                values (?, ?, ?)
                """,
                context,
                mv,
                user
        );

        jdbc.update(
                """
                insert into ai_tasks(
                    id, user_id, company_id, type, status,
                    context_id, policy_version_id,
                    request_key_hash, request_body_hmac
                )
                values (
                    ?, ?, ?, 'INTERVIEW_GENERATION', 'SUCCEEDED',
                    ?, ?, ?, ?
                )
                """,
                generationTask,
                user,
                company,
                context,
                generation,
                "a".repeat(64),
                "b".repeat(64)
        );

        long question = insert(
                """
                insert into interview_question_keys(
                    user_id, company_id, question_fingerprint
                )
                values (?, ?, ?)
                """,
                user,
                company,
                "a".repeat(64)
        );

        jdbc.update(
                """
                insert into interview_questions(
                    id, user_id, company_id, generation_task_id,
                    context_id, generation_policy_version_id,
                    question_text, question_fingerprint
                )
                values (?, ?, ?, ?, ?, ?, '질문', ?)
                """,
                question,
                user,
                company,
                generationTask,
                context,
                generation,
                "a".repeat(64)
        );

        jdbc.update(
                """
                insert into interview_question_evidence(
                    question_id, user_id, company_id, section_id, quotation
                )
                values (?, ?, ?, ?, '사업')
                """,
                question,
                user,
                company,
                section
        );

        jdbc.update(
                """
                insert into interview_drafts(
                    question_id, user_id, company_id, draft_text
                )
                values (?, ?, ?, '개인 초안')
                """,
                question,
                user,
                company
        );

        jdbc.update(
                """
                insert into interview_daily_questions(
                    user_id, company_id, business_date,
                    question_id, question_slot
                )
                values (?, ?, '2026-11-01', ?, 1)
                """,
                user,
                company,
                question
        );

        long attempt = insert(
                """
                insert into interview_submissions(
                    user_id, company_id, question_id,
                    business_date, daily_slot, attempt_number,
                    request_key_hash, request_body_hmac
                )
                values (?, ?, ?, '2026-11-01', 1, 1, ?, ?)
                """,
                user,
                company,
                question,
                "a".repeat(64),
                "b".repeat(64)
        );

        jdbc.update(
                """
                insert into interview_attempts(
                    id, user_id, company_id, context_id,
                    evaluation_policy_version_id, answer_text
                )
                values (?, ?, ?, ?, ?, '개인 답변')
                """,
                attempt,
                user,
                company,
                context,
                evaluation
        );

        String task = key();

        jdbc.update(
                """
                insert into ai_tasks(
                    id, user_id, company_id, type,
                    context_id, policy_version_id, source_attempt_id,
                    request_key_hash, request_body_hmac,
                    lease_owner, lease_until, lease_version
                )
                values (
                    ?, ?, ?, 'INTERVIEW_EVALUATION',
                    ?, ?, ?, ?, ?, 'old-worker', ?, 1
                )
                """,
                task,
                user,
                company,
                context,
                evaluation,
                attempt,
                "c".repeat(64),
                "d".repeat(64),
                utc(clock.instant().plusSeconds(120))
        );

        jdbc.update(
                """
                insert into ai_task_runs(
                    task_id, run_no, lease_version
                )
                values (?, 1, 1)
                """,
                task
        );

        long result = insert(
                """
                insert into interview_evaluations(
                    attempt_id, task_id, user_id, context_id,
                    policy_version_id, summary_feedback, overall_feedback,
                    strengths, improvements, total_score, evaluated_at
                )
                values (?, ?, ?, ?, ?, '요약', '총평', '[]', '[]', 80, ?)
                """,
                attempt,
                task,
                user,
                context,
                evaluation,
                utc(clock.instant())
        );

        for (long criterion : jdbc.query(
                """
                select id from evaluation_criteria
                where policy_version_id = ?
                """,
                (rs, row) -> rs.getLong(1),
                evaluation
        )) {
            jdbc.update(
                    """
                    insert into evaluation_scores(
                        evaluation_id, criterion_id, policy_version_id,
                        score, feedback, reasoning, answer_quote
                    )
                    values (?, ?, ?, 20, '피드백', '근거', '답변')
                    """,
                    result,
                    criterion,
                    evaluation
            );
        }

        jdbc.update(
                """
                insert into evaluation_material_links(
                    evaluation_id, context_id, material_version_id,
                    display_order, excerpt, connection_reason
                )
                values (?, ?, ?, 1, '경험', '연결')
                """,
                result,
                context,
                mv
        );

        long quiz = insert(
                "insert into quiz_questions(company_id) values (?)",
                company
        );

        long qv = insert(
                """
                insert into quiz_question_versions(
                    question_id, company_id, version_no,
                    question_text, explanation
                )
                values (?, ?, 1, '퀴즈', '해설')
                """,
                quiz,
                company
        );

        long option = insert(
                """
                insert into quiz_options(
                    quiz_version_id, position, option_text, is_correct
                )
                values (?, 1, '정답', true)
                """,
                qv
        );

        long set = insert(
                """
                insert into quiz_sets(
                    user_id, company_id, business_date, mode
                )
                values (?, ?, '2026-11-01', 'NORMAL')
                """,
                user,
                company
        );

        long item = insert(
                """
                insert into quiz_set_items(
                    set_id, user_id, company_id, question_id,
                    quiz_version_id, position
                )
                values (?, ?, ?, ?, ?, 1)
                """,
                set,
                user,
                company,
                quiz,
                qv
        );

        jdbc.update(
                """
                insert into quiz_answers(
                    set_item_id, user_id, company_id, question_id,
                    quiz_version_id, selected_option_id, is_correct,
                    request_key_hash, request_body_hmac
                )
                values (?, ?, ?, ?, ?, ?, true, ?, ?)
                """,
                item,
                user,
                company,
                quiz,
                qv,
                option,
                "a".repeat(64),
                "b".repeat(64)
        );

        return task;
    }

    private long policy(long company, String type) {
        long policy = insert(
                """
                insert into interview_policies(
                    policy_key, type, company_id
                )
                values (?, ?, ?)
                """,
                type + ":" + company + ":0",
                type,
                company
        );

        long version = insert(
                """
                insert into interview_policy_versions(
                    policy_id, version_no, status,
                    prompt_template, rule_config
                )
                values (?, 1, 'PUBLISHED', '지침', '{}')
                """,
                policy
        );

        if (type.equals("EVALUATION")) {
            for (int i = 0; i < 4; i++) {
                jdbc.update(
                        """
                        insert into evaluation_criteria(
                            policy_version_id, position,
                            code, name, description
                        )
                        values (?, ?, ?, ?, '기준')
                        """,
                        version,
                        i + 1,
                        InterviewPolicy.CODES.get(i),
                        InterviewPolicy.LABELS.get(i)
                );
            }
        }

        return version;
    }

    private void toSocial(String provider, String subject) {
        jdbc.update(
                """
                update auth_identities
                set provider = ?, provider_subject = ?,
                    password_hash = null, email = null
                where user_id = ?
                """,
                provider,
                subject,
                user
        );
    }

    private long account(String provider, String subject) {
        long id = insert(
                "insert into users(nickname) values ('회원테스트')"
        );

        users.add(id);

        jdbc.update(
                """
                insert into auth_identities(
                    user_id, provider, provider_subject,
                    email, password_hash
                )
                values (?, ?, ?, ?, ?)
                """,
                id,
                provider,
                subject,
                provider.equals("EMAIL") ? subject : null,
                provider.equals("EMAIL") ? encoded : null
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

    private void material(String type) {
        long id = insert(
                """
                insert into personal_materials(user_id, type)
                values (?, ?)
                """,
                user,
                type
        );

        insert(
                """
                insert into material_versions(
                    material_id, user_id, material_type,
                    version_no, title, content_text
                )
                values (?, ?, ?, 1, ?, '개인 경험 원문')
                """,
                id,
                user,
                type,
                type.equals("EXPERIENCE") ? "경험" : null
        );

        jdbc.update(
                """
                update personal_materials
                set current_version_no = 1 where id = ?
                """,
                id
        );
    }

    private ResultActions summary(String credential) throws Exception {
        return mvc.perform(
                get("/v1/users/me")
                        .header("Authorization", "Bearer " + credential)
        );
    }

    private ResultActions nickname(String body) throws Exception {
        return mvc.perform(
                patch("/v1/users/me/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(body)
        );
    }

    private ResultActions remove(
            String credential,
            String key,
            String body
    ) throws Exception {
        return mvc.perform(
                delete("/v1/users/me")
                        .header("Authorization", "Bearer " + credential)
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(body)
        );
    }

    private String emailBody() {
        return "{\"confirm\":true,\"password\":\"" + password + "\"}";
    }

    private long count(String sql, Object... args) {
        return Objects.requireNonNull(
                jdbc.queryForObject(sql, Long.class, args)
        );
    }

    private long insert(String sql, Object... args) {
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

        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}