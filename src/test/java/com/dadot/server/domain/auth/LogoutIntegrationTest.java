package com.dadot.server.domain.auth;

import com.dadot.server.domain.auth.repository.LogoutRepository;
import com.dadot.server.domain.auth.service.LogoutService;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.AccessTokenIssuer;
import com.dadot.server.global.security.SessionAuthenticationToken;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({
        EmailVerificationIntegrationTest.TestConfig.class,
        LogoutIntegrationTest.TestRoutes.class
})
class LogoutIntegrationTest {

    @Autowired LogoutService logout;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessTokenIssuer tokens;
    @Autowired JwtDecoder decoder;
    @Autowired SessionJwtAuthenticationConverter converter;
    @Autowired EmailVerificationIntegrationTest.MutableClock clock;
    @Autowired MockMvc mvc;
    @MockitoSpyBean LogoutRepository repository;

    private long userId;
    private Token current;

    @BeforeEach
    void resetDatabase() {
        assertEquals("dadot_test_db", jdbc.queryForObject("select database()", String.class));
        for (String table : List.of(
                "user_devices", "api_idempotency_requests", "auth_request_guards",
                "auth_proofs", "email_verifications", "auth_sessions", "auth_identities",
                "user_agreements", "user_onboarding", "users", "service_terms"
        )) {
            jdbc.update("delete from " + table);
        }
        clock.set(Instant.parse("2026-10-23T15:00:00.123456Z"));
        userId = createUser();
        jdbc.update("insert into user_onboarding(user_id) values (?)", userId);
        current = createSession(userId, 0);
    }

    @AfterEach
    void cleanupDevicesAndSpy() {
        org.mockito.Mockito.reset(repository);
        // 기존 인증 테스트의 사용자 삭제와 FK 충돌하지 않도록 정리한다.
        jdbc.update("delete from user_devices");
    }

    @Test
    void logoutReturns204WithoutBodyAndRevokesCurrentSession() throws Exception {
        mvc.perform(post("/v1/auth/logout").header("Authorization", bearer(current)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Content-Type"));
        assertEquals(utc(clock.instant()), revokedAt(current));
        assertEquals(1, jdbc.queryForObject("select count(*) from auth_sessions", Integer.class));
    }

    @Test
    void loggedOutTokenCannotAccessProtectedApiAndCannotLogoutAgain() throws Exception {
        mvc.perform(post("/v1/auth/logout").header("Authorization", bearer(current)))
                .andExpect(status().isNoContent());
        var firstRevocation = revokedAt(current);
        clock.advance(1);
        mvc.perform(get("/v1/logout-test/me").header("Authorization", bearer(current)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(post("/v1/auth/logout").header("Authorization", bearer(current)))
                .andExpect(status().isUnauthorized());
        assertEquals(firstRevocation, revokedAt(current));
    }

    @Test
    void removesOnlyCurrentSessionsDevicesAndKeepsOtherDeviceLoggedIn() throws Exception {
        var other = createSession(userId, 0);
        String firstDevice = attach(userId, current);
        String otherDevice = attach(userId, other);
        mvc.perform(post("/v1/auth/logout").header("Authorization", bearer(current)))
                .andExpect(status().isNoContent());
        assertEquals(0, countDevice(firstDevice));
        assertEquals(1, countDevice(otherDevice));
        assertNull(revokedAt(other));
        mvc.perform(get("/v1/logout-test/me").header("Authorization", bearer(other)))
                .andExpect(status().isOk()).andExpect(content().string(Long.toString(userId)));
    }

    @Test
    void logoutSucceedsWithoutRegisteredDevice() throws Exception {
        assertEquals(0, jdbc.queryForObject("select count(*) from user_devices", Integer.class));
        mvc.perform(post("/v1/auth/logout").header("Authorization", bearer(current)))
                .andExpect(status().isNoContent());
        assertNotNull(revokedAt(current));
    }

    @Test
    void logoutDetachesEveryInstallationBoundToCurrentSession() throws Exception {
        attach(userId, current);
        attach(userId, current);
        mvc.perform(post("/v1/auth/logout").header("Authorization", bearer(current)))
                .andExpect(status().isNoContent());
        assertEquals(0, jdbc.queryForObject("select count(*) from user_devices", Integer.class));
    }

    @Test
    void doesNotRemoveInstallationReconnectedToAnotherAccount() throws Exception {
        String installation = attach(userId, current);
        long nextUser = createUser();
        var next = createSession(nextUser, 0);
        jdbc.update("update user_devices set user_id = ?, session_id = ? where installation_id = ?",
                nextUser, next.id(), installation);
        mvc.perform(post("/v1/auth/logout").header("Authorization", bearer(current)))
                .andExpect(status().isNoContent());
        assertEquals(1, countDevice(installation));
        assertEquals(nextUser, jdbc.queryForObject(
                "select user_id from user_devices where installation_id = ?", Long.class, installation));
        assertNull(revokedAt(next));
    }

    @Test
    void doesNotRemoveInstallationReconnectedToNewSessionOfSameAccount() throws Exception {
        String installation = attach(userId, current);
        var next = createSession(userId, 0);
        jdbc.update("update user_devices set session_id = ? where installation_id = ?", next.id(), installation);
        mvc.perform(post("/v1/auth/logout").header("Authorization", bearer(current)))
                .andExpect(status().isNoContent());
        assertEquals(1, countDevice(installation));
        assertNull(revokedAt(next));
    }

    @Test
    void missingTokenCannotRevokeSession() throws Exception {
        mvc.perform(post("/v1/auth/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        assertNull(revokedAt(current));
    }

    @Test
    void tamperedTokenCannotRevokeSession() throws Exception {
        String token = current.value();
        int signatureStart = token.lastIndexOf('.') + 1;
        char replacement = token.charAt(signatureStart) == 'A' ? 'B' : 'A';
        String tampered = token.substring(0, signatureStart) + replacement + token.substring(signatureStart + 1);
        mvc.perform(post("/v1/auth/logout").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
        assertNull(revokedAt(current));
    }

    @Test
    void expiredTokenCannotRevokeSessionOrDetachDevice() throws Exception {
        String installation = attach(userId, current);
        clock.set(current.expiresAt());
        mvc.perform(post("/v1/auth/logout").header("Authorization", bearer(current)))
                .andExpect(status().isUnauthorized());
        assertNull(revokedAt(current));
        assertEquals(1, countDevice(installation));
    }

    @Test
    void revokedSessionCannotDetachDeviceOnAnotherRequest() throws Exception {
        String installation = attach(userId, current);
        jdbc.update("update auth_sessions set revoked_at = ? where id = ?", utc(clock.instant()), current.id());
        mvc.perform(post("/v1/auth/logout").header("Authorization", bearer(current)))
                .andExpect(status().isUnauthorized());
        assertEquals(1, countDevice(installation));
    }

    @Test
    void forgedUserAndSessionCombinationIsRejected() throws Exception {
        attach(userId, current);
        long otherUser = createUser();
        String forged = tokens.issue(otherUser, current.id(), 0, current.issuedAt(), current.expiresAt());
        mvc.perform(post("/v1/auth/logout").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
        assertNull(revokedAt(current));
        assertEquals(1, jdbc.queryForObject("select count(*) from user_devices", Integer.class));
    }

    @Test
    void rejectsRequestBodyIncludingEmptyJsonWithoutRevokingSession() throws Exception {
        for (String body : List.of("{}", " ", "null", "[]", "{\"userId\":999}")) {
            mvc.perform(post("/v1/auth/logout").header("Authorization", bearer(current))
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        }
        assertNull(revokedAt(current));
    }

    @Test
    void authenticationWithoutVerifiedSessionInformationIsRejected() throws Exception {
        var unrelated = UsernamePasswordAuthenticationToken.authenticated(
                new UserPrincipal(userId), null, List.of());
        mvc.perform(post("/v1/auth/logout").with(authentication(unrelated)))
                .andExpect(status().isUnauthorized());
        assertNull(revokedAt(current));
    }

    @Test
    void logoutDoesNotChangeMemberOnboardingOrOtherSessions() throws Exception {
        jdbc.update("update user_onboarding set current_step = 'JOBS', version = 2 where user_id = ?", userId);
        var other = createSession(userId, 0);
        mvc.perform(post("/v1/auth/logout").header("Authorization", bearer(current)))
                .andExpect(status().isNoContent());
        assertEquals("ACTIVE", jdbc.queryForObject("select status from users where id = ?", String.class, userId));
        assertEquals(0, jdbc.queryForObject("select auth_version from users where id = ?", Integer.class, userId));
        assertEquals("JOBS", jdbc.queryForObject("select current_step from user_onboarding where user_id = ?", String.class, userId));
        assertEquals(2L, jdbc.queryForObject("select version from user_onboarding where user_id = ?", Long.class, userId));
        assertNull(revokedAt(other));
    }

    @Test
    void deviceCleanupFailureRollsBackRevocationAndReturns503() throws Exception {
        String installation = attach(userId, current);
        doThrow(new DataAccessResourceFailureException("test-only failure"))
                .when(repository).detachDevices(userId, current.id());
        mvc.perform(post("/v1/auth/logout").header("Authorization", bearer(current)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
        assertNull(revokedAt(current));
        assertEquals(1, countDevice(installation));
    }

    @Test
    void storageFailureDuringLogoutReturns503WithoutChangingData() throws Exception {
        doThrow(new DataAccessResourceFailureException("test-only failure"))
                .when(repository).lockAccount(userId);
        mvc.perform(post("/v1/auth/logout").header("Authorization", bearer(current)))
                .andExpect(status().isServiceUnavailable());
        assertNull(revokedAt(current));
    }

    @Test
    void inFlightAuthenticationCannotLogoutAfterAuthVersionChanges() {
        var authenticated = sessionAuthentication();
        jdbc.update("update users set auth_version = auth_version + 1 where id = ?", userId);
        assertCode(ErrorCode.UNAUTHENTICATED, () -> logout.logout(
                userId, authenticated.getSessionId(), authenticated.getAuthVersion()));
        assertNull(revokedAt(current));
    }

    @Test
    void inFlightAuthenticationCannotLogoutAfterAccountStartsDeleting() {
        var authenticated = sessionAuthentication();
        jdbc.update("update users set status = 'DELETING' where id = ?", userId);
        assertCode(ErrorCode.UNAUTHENTICATED, () -> logout.logout(
                userId, authenticated.getSessionId(), authenticated.getAuthVersion()));
        assertNull(revokedAt(current));
    }

    @Test
    void inFlightAuthenticationCannotLogoutAfterExpiry() {
        var authenticated = sessionAuthentication();
        clock.set(current.expiresAt());
        assertCode(ErrorCode.UNAUTHENTICATED, () -> logout.logout(
                userId, authenticated.getSessionId(), authenticated.getAuthVersion()));
        assertNull(revokedAt(current));
    }

    @Test
    void serviceCannotRevokeAnotherUsersSessionEvenWithExistingUserId() {
        long other = createUser();
        assertCode(ErrorCode.UNAUTHENTICATED, () -> logout.logout(other, current.id(), 0));
        assertNull(revokedAt(current));
    }

    @Test
    void concurrentLogoutRequestsRevokeAndDetachOnlyOnce() throws Exception {
        attach(userId, current);
        var authenticated = sessionAuthentication();
        try (var executor = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        logout.logout(userId, authenticated.getSessionId(), authenticated.getAuthVersion());
                        return true;
                    } catch (BusinessException ex) {
                        assertEquals(ErrorCode.UNAUTHENTICATED, ex.getErrorCode());
                        return false;
                    }
                }));
            }
            start.countDown();
            int successes = 0;
            for (var future : futures) if (future.get(20, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        }
        verify(repository, times(1)).detachDevices(userId, current.id());
        assertNotNull(revokedAt(current));
        assertEquals(0, jdbc.queryForObject("select count(*) from user_devices", Integer.class));
    }

    @Test
    void verifiedAuthenticationRetainsOnlySessionMetadataAndExistingPrincipal() {
        var authenticated = sessionAuthentication();
        assertEquals(new UserPrincipal(userId), authenticated.getPrincipal());
        assertEquals(current.id(), authenticated.getSessionId());
        assertEquals(0, authenticated.getAuthVersion());
        assertEquals(Long.toString(userId), authenticated.getName());
        assertNull(authenticated.getCredentials());
        assertTrue(authenticated.isAuthenticated());
        assertFalse(authenticated.toString().contains(current.value()));
    }

    private SessionAuthenticationToken sessionAuthentication() {
        return (SessionAuthenticationToken) converter.convert(decoder.decode(current.value()));
    }

    private long createUser() {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("insert into users(nickname) values (?)", new String[]{"id"});
            statement.setString(1, "로그아웃테스트");
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private Token createSession(long owner, int authVersion) {
        String id = UUID.randomUUID().toString();
        Instant issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expires = issued.plusSeconds(3600);
        jdbc.update("insert into auth_sessions(id, user_id, created_at, expires_at) values (?, ?, ?, ?)",
                id, owner, utc(issued), utc(expires));
        return new Token(id, tokens.issue(owner, id, authVersion, issued, expires), issued, expires);
    }

    private String attach(long owner, Token token) {
        String installation = UUID.randomUUID().toString();
        jdbc.update("""
                insert into user_devices(installation_id, user_id, session_id, platform)
                values (?, ?, ?, 'IOS')
                """, installation, owner, token.id());
        return installation;
    }

    private int countDevice(String installation) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "select count(*) from user_devices where installation_id = ?", Integer.class, installation));
    }

    private LocalDateTime revokedAt(Token token) {
        return jdbc.queryForObject("select revoked_at from auth_sessions where id = ?", LocalDateTime.class, token.id());
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String bearer(Token token) {
        return "Bearer " + token.value();
    }

    private static void assertCode(ErrorCode code, Runnable operation) {
        assertEquals(code, assertThrows(BusinessException.class, operation::run).getErrorCode());
    }

    private record Token(String id, String value, Instant issuedAt, Instant expiresAt) {
        @Override
        public String toString() {
            return "TestToken[REDACTED]";
        }
    }

    @RestController
    static class TestRoutes {
        @GetMapping("/v1/logout-test/me")
        String currentUser(@AuthenticationPrincipal UserPrincipal principal) {
            return Long.toString(principal.userId());
        }
    }
}
