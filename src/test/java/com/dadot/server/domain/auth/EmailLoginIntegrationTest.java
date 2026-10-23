package com.dadot.server.domain.auth;

import com.dadot.server.domain.auth.dto.request.EmailLoginRequest;
import com.dadot.server.domain.auth.dto.response.EmailLoginResponse;
import com.dadot.server.domain.auth.repository.EmailLoginRepository;
import com.dadot.server.domain.auth.service.EmailLoginService;
import com.dadot.server.domain.auth.service.EmailLoginSessionService;
import com.dadot.server.domain.auth.service.LoginAttemptLimiter;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.AccessTokenIssuer;
import com.dadot.server.global.security.SessionJwtAuthenticationConverter;
import com.dadot.server.global.security.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
@Import({
        EmailVerificationIntegrationTest.TestConfig.class,
        EmailLoginIntegrationTest.TestRoutes.class
})
class EmailLoginIntegrationTest {

    private static final String EMAIL = "login@example.invalid";
    private static final String PASSWORD = "Example123!";
    private static final String IP = "192.0.2.80";
    private static String cachedPasswordHash;

    @Autowired EmailLoginService login;
    @Autowired EmailLoginSessionService sessions;
    @Autowired LoginAttemptLimiter limiter;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtDecoder decoder;
    @Autowired SessionJwtAuthenticationConverter authentication;
    @Autowired EmailVerificationIntegrationTest.MutableClock clock;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @MockitoSpyBean AccessTokenIssuer tokens;
    @MockitoSpyBean EmailLoginRepository repository;

    private long userId;

    @BeforeEach
    void resetDatabase() {
        assertEquals("dadot_test_db", jdbc.queryForObject("select database()", String.class));
        for (String table : List.of(
                "api_idempotency_requests", "auth_request_guards", "auth_proofs",
                "email_verifications", "auth_sessions", "auth_identities",
                "user_agreements", "user_onboarding", "users", "service_terms"
        )) {
            jdbc.update("delete from " + table);
        }
        clock.set(Instant.parse("2026-10-23T10:00:00.123456Z"));
        if (cachedPasswordHash == null) {
            cachedPasswordHash = passwords.encode(PASSWORD);
        }
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    "insert into users(nickname) values (?)", new String[]{"id"}
            );
            statement.setString(1, "로그인테스트");
            return statement;
        }, key);
        userId = Objects.requireNonNull(key.getKey()).longValue();
        jdbc.update("""
                insert into auth_identities(user_id, provider, provider_subject, email, password_hash)
                values (?, 'EMAIL', ?, ?, ?)
                """, userId, EMAIL, EMAIL, cachedPasswordHash);
        jdbc.update("insert into user_onboarding(user_id) values (?)", userId);
    }

    @AfterEach
    void resetSpies() {
        org.mockito.Mockito.reset(tokens, repository);
    }

    @Test
    void httpSuccessWithoutIdempotencyHeaderCreatesUsableOneHourToken() throws Exception {
        String json = mvc.perform(post("/v1/auth/login")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of(
                                "email", "  LOGIN@EXAMPLE.INVALID  ", "password", PASSWORD
                        ))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.data.userId").value(userId))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.onboardingCompleted").value(false))
                .andReturn().getResponse().getContentAsString();

        var node = mapper.readTree(json).get("data");
        var jwt = decoder.decode(node.get("accessToken").asText());
        assertEquals(clock.instant().getEpochSecond() + 3600, jwt.getExpiresAt().getEpochSecond());
        assertEquals(new UserPrincipal(userId), authentication.convert(jwt).getPrincipal());
        assertEquals(1, countSessions());
        assertEquals(0, jdbc.queryForObject("select count(*) from api_idempotency_requests", Integer.class));
        assertFalse(jwt.getClaims().containsKey("email"));
        assertFalse(jwt.getClaims().containsKey("password"));
        assertEquals(LocalDateTime.ofInstant(jwt.getIssuedAt(), ZoneOffset.UTC), jdbc.queryForObject(
                "select created_at from auth_sessions where id = ?", LocalDateTime.class, jwt.getId()));
        mvc.perform(get("/v1/auth04-test/me").header("Authorization", "Bearer " + node.get("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(content().string(Long.toString(userId)));
    }

    @Test
    void returnsCompletedOnboardingWithoutResettingProgress() {
        jdbc.update("update user_onboarding set completed_at = ? where user_id = ?",
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), userId);
        assertTrue(login.login(request(PASSWORD), IP).onboardingCompleted());
        assertNotNull(jdbc.queryForObject("select completed_at from user_onboarding where user_id = ?",
                LocalDateTime.class, userId));
    }

    @Test
    void missingOnboardingRowReturnsFalse() {
        jdbc.update("delete from user_onboarding where user_id = ?", userId);
        assertFalse(login.login(request(PASSWORD), IP).onboardingCompleted());
    }

    @Test
    void eachLoginCreatesIndependentSessionWithoutRevokingPreviousDevice() {
        var first = login.login(request(PASSWORD), IP);
        var second = login.login(request(PASSWORD), IP);
        assertNotEquals(first.accessToken(), second.accessToken());
        assertEquals(2, countSessions());
        assertNotNull(authentication.convert(decoder.decode(first.accessToken())));
        assertNotNull(authentication.convert(decoder.decode(second.accessToken())));
    }

    @Test
    void wrongPasswordUnknownEmailAndDeletingAccountHaveSameHttpError() throws Exception {
        var wrong = mvc.perform(post("/v1/auth/login").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("email", EMAIL, "password", "Wrong123!"))))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        var unknown = mvc.perform(post("/v1/auth/login").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("email", "unknown@example.invalid", "password", PASSWORD))))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        jdbc.update("update users set status = 'DELETING' where id = ?", userId);
        var deleting = mvc.perform(post("/v1/auth/login").contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD))))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertEquals(mapper.readTree(wrong), mapper.readTree(unknown));
        assertEquals(mapper.readTree(wrong), mapper.readTree(deleting));
        assertEquals("INVALID_CREDENTIALS", mapper.readTree(wrong).get("error").get("code").asText());
        assertEquals(0, countSessions());
    }

    @Test
    void socialEmailCannotAuthenticateAsEmailIdentity() {
        jdbc.update("delete from auth_identities where user_id = ?", userId);
        jdbc.update("""
                insert into auth_identities(user_id, provider, provider_subject, email)
                values (?, 'KAKAO', 'kakao-user', ?)
                """, userId, EMAIL);
        assertCode(ErrorCode.INVALID_CREDENTIALS, () -> login.login(request(PASSWORD), IP));
        assertEquals(0, countSessions());
    }

    @Test
    void malformedInputsFailBeforeConsumingLoginBudget() throws Exception {
        List<String> invalid = List.of(
                "{}", "null", "{", "[]",
                mapper.writeValueAsString(Map.of("email", "bad", "password", PASSWORD)),
                mapper.writeValueAsString(Map.of("email", EMAIL, "password", "1234!")),
                mapper.writeValueAsString(Map.of("email", EMAIL, "password", "abcdefgh")),
                mapper.writeValueAsString(Map.of("email", EMAIL, "password", "12345678")),
                mapper.writeValueAsString(Map.of("email", EMAIL, "password", " Example123!")),
                mapper.writeValueAsString(Map.of("email", EMAIL, "password", "Example123!\u00a0")),
                mapper.writeValueAsString(Map.of("email", EMAIL, "password", "A1".repeat(33)))
        );
        for (String body : invalid) {
            mvc.perform(post("/v1/auth/login").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from auth_request_guards", Integer.class));
    }

    @Test
    void wrongPasswordConsumesBudgetAndBlockedCorrectPasswordCannotCreateSession() {
        for (int i = 0; i < 10; i++) {
            assertCode(ErrorCode.INVALID_CREDENTIALS, () -> login.login(request("Wrong123!"), IP));
        }
        assertCode(ErrorCode.RATE_LIMITED, () -> login.login(request(PASSWORD), IP));
        assertEquals(0, countSessions());
        assertEquals(10, jdbc.queryForObject("select min(request_count) from auth_request_guards", Integer.class));
    }

    @Test
    void emailBudgetIsSharedAcrossDifferentIps() {
        for (int i = 0; i < 10; i++) {
            limiter.consume(EMAIL, "192.0.2." + i);
        }
        assertCode(ErrorCode.RATE_LIMITED, () -> login.login(request(PASSWORD), "192.0.2.200"));
    }

    @Test
    void ipMinuteBudgetIsSharedAcrossEmailsAndIgnoresSpoofedForwardingHeader() throws Exception {
        for (int i = 0; i < 20; i++) {
            limiter.consume("unknown" + i + "@example.invalid", "127.0.0.1");
        }
        mvc.perform(post("/v1/auth/login")
                        .header("X-Forwarded-For", "198.51.100.123")
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
        assertEquals(0, countSessions());
    }

    @Test
    void ipHourlyBudgetSurvivesMinuteWindowChanges() {
        for (int batch = 0; batch < 5; batch++) {
            for (int i = 0; i < 20; i++) {
                limiter.consume("unknown" + batch + "-" + i + "@example.invalid", IP);
            }
            clock.advance(61);
        }
        assertCode(ErrorCode.RATE_LIMITED, () -> limiter.consume(EMAIL, IP));
    }

    @Test
    void newFixedWindowAllowsLoginAfterBudgetWasExhausted() {
        for (int i = 0; i < 10; i++) {
            limiter.consume(EMAIL, IP);
        }
        assertCode(ErrorCode.RATE_LIMITED, () -> login.login(request(PASSWORD), IP));
        clock.advance(600);
        assertEquals(userId, login.login(request(PASSWORD), IP).userId());
    }

    @Test
    void concurrentAttemptsCannotExceedSharedEmailBudget() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String ip = "192.0.2." + i;
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        limiter.consume(EMAIL, ip);
                        return true;
                    } catch (BusinessException ex) {
                        assertEquals(ErrorCode.RATE_LIMITED, ex.getErrorCode());
                        return false;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (var future : futures) {
                if (future.get(20, TimeUnit.SECONDS)) accepted++;
            }
            assertEquals(10, accepted);
        }
    }

    @Test
    void changedPasswordBetweenVerificationAndSessionCreationIsRejected() {
        var verified = repository.findCredential(EMAIL).orElseThrow();
        jdbc.update("update auth_identities set password_hash = ? where user_id = ?",
                passwords.encode("Changed123!"), userId);
        assertCode(ErrorCode.INVALID_CREDENTIALS, () -> sessions.create(verified, EMAIL));
        assertEquals(0, countSessions());
    }

    @Test
    void deletingAccountBetweenVerificationAndSessionCreationIsRejected() {
        var verified = repository.findCredential(EMAIL).orElseThrow();
        jdbc.update("update users set status = 'DELETING' where id = ?", userId);
        assertCode(ErrorCode.INVALID_CREDENTIALS, () -> sessions.create(verified, EMAIL));
        assertEquals(0, countSessions());
    }

    @Test
    void removedEmailIdentityCannotCreateSessionFromEarlierVerification() {
        var verified = repository.findCredential(EMAIL).orElseThrow();
        jdbc.update("delete from auth_identities where user_id = ?", userId);
        assertCode(ErrorCode.INVALID_CREDENTIALS, () -> sessions.create(verified, EMAIL));
        assertEquals(0, countSessions());
    }

    @Test
    void currentAuthenticationVersionIsIncludedInToken() {
        jdbc.update("update users set auth_version = 3 where id = ?", userId);
        var result = login.login(request(PASSWORD), IP);
        var jwt = decoder.decode(result.accessToken());
        assertEquals(3L, ((Number) jwt.getClaim("av")).longValue());
        assertNotNull(authentication.convert(jwt));
    }

    @Test
    void loginTokenBecomesUnusableWhenSessionIsRevoked() throws Exception {
        var result = login.login(request(PASSWORD), IP);
        jdbc.update("update auth_sessions set revoked_at = ? where user_id = ?",
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), userId);
        mvc.perform(get("/v1/auth04-test/me").header("Authorization", "Bearer " + result.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginTokenExpiresAtExactOneHourBoundary() throws Exception {
        var result = login.login(request(PASSWORD), IP);
        clock.set(result.expiresAt());
        mvc.perform(get("/v1/auth04-test/me").header("Authorization", "Bearer " + result.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void jwtIssuanceFailureRollsBackSessionButKeepsAttemptBudget() {
        doThrow(new JwtEncodingException("test-only failure"))
                .when(tokens).issue(anyLong(), anyString(), anyInt(), any(Instant.class), any(Instant.class));
        assertThrows(JwtEncodingException.class, () -> login.login(request(PASSWORD), IP));
        assertEquals(0, countSessions());
        assertEquals(1, jdbc.queryForObject("select min(request_count) from auth_request_guards", Integer.class));
    }

    @Test
    void unavailableCredentialStorageReturnsServiceUnavailable() {
        doThrow(new DataAccessResourceFailureException("test-only failure"))
                .when(repository).findCredential(EMAIL);
        assertCode(ErrorCode.SERVICE_UNAVAILABLE, () -> login.login(request(PASSWORD), IP));
        assertEquals(0, countSessions());
    }

    @Test
    void corruptStoredHashIsNotReportedAsWrongPassword() {
        jdbc.update("update auth_identities set password_hash = '{unsupported}broken' where user_id = ?", userId);
        assertCode(ErrorCode.SERVICE_UNAVAILABLE, () -> login.login(request(PASSWORD), IP));
        assertEquals(0, countSessions());
    }

    @Test
    void credentialsAndTokensAreRedactedFromToString() {
        var result = login.login(request(PASSWORD), IP);
        assertFalse(request(PASSWORD).toString().contains(PASSWORD));
        assertFalse(request(PASSWORD).toString().contains(EMAIL));
        assertFalse(result.toString().contains(result.accessToken()));
        assertFalse(repository.findCredential(EMAIL).orElseThrow().toString().contains(cachedPasswordHash));
    }

    private EmailLoginRequest request(String password) {
        return new EmailLoginRequest(EMAIL, password);
    }

    private int countSessions() {
        return Objects.requireNonNull(jdbc.queryForObject("select count(*) from auth_sessions", Integer.class));
    }

    private void assertCode(ErrorCode code, Runnable operation) {
        var ex = assertThrows(BusinessException.class, operation::run);
        assertEquals(code, ex.getErrorCode());
    }

    @RestController
    static class TestRoutes {
        @GetMapping("/v1/auth04-test/me")
        String currentUser(@AuthenticationPrincipal UserPrincipal principal) {
            return Long.toString(principal.userId());
        }
    }
}
