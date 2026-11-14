package com.dadot.server.domain.auth;

import com.dadot.server.domain.auth.dto.request.EmailLoginRequest;
import com.dadot.server.domain.auth.dto.request.RefreshTokenRequest;
import com.dadot.server.domain.auth.dto.response.EmailLoginResponse;
import com.dadot.server.domain.auth.dto.response.RefreshTokenResponse;
import com.dadot.server.domain.auth.repository.RefreshTokenRepository;
import com.dadot.server.domain.auth.repository.PasswordChangeRepository;
import com.dadot.server.domain.auth.repository.PasswordResetRepository;
import com.dadot.server.domain.auth.service.EmailLoginService;
import com.dadot.server.domain.auth.service.RefreshTokenService;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.AccessTokenIssuer;
import com.dadot.server.global.security.UserPrincipal;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({EmailVerificationIntegrationTest.TestConfig.class, RefreshTokenIntegrationTest.Routes.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class RefreshTokenIntegrationTest {
    private static final String EMAIL="refresh@example.invalid", PASSWORD="Example123!", IP="192.0.2.88";
    private static String passwordHash;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired EmailLoginService login;
    @Autowired RefreshTokenService refresh;
    @Autowired VerificationTokenCrypto crypto;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtDecoder decoder;
    @Autowired PlatformTransactionManager manager;
    @Autowired PasswordChangeRepository changes;
    @Autowired PasswordResetRepository resets;
    @Autowired EmailVerificationIntegrationTest.MutableClock clock;
    @MockitoSpyBean AccessTokenIssuer issuer;
    @MockitoSpyBean RefreshTokenRepository repository;
    private long user;

    @BeforeEach void setup() {
        assertEquals("dadot_test_db",jdbc.queryForObject("select database()",String.class));
        reset(issuer,repository);
        for (String table:List.of("auth_refresh_families","api_idempotency_requests","auth_request_guards",
                "auth_proofs","email_verifications","user_devices","auth_sessions","auth_identities",
                "user_agreements","user_onboarding","users","service_terms")) jdbc.update("delete from "+table);
        clock.set(Instant.parse("2026-11-08T00:00:00Z"));
        jdbc.update("insert into users(nickname) values ('갱신 테스트')");
        user=jdbc.queryForObject("select max(id) from users",Long.class);
        if (passwordHash==null) passwordHash=passwords.encode(PASSWORD);
        jdbc.update("""
                insert into auth_identities(user_id,provider,provider_subject,email,password_hash)
                values (?,'EMAIL',?,?,?)
                """,user,EMAIL,EMAIL,passwordHash);
    }

    @AfterEach void clearSpies() { reset(issuer,repository); }
    private String key() { return UUID.randomUUID().toString(); }
    private EmailLoginResponse login() { return login.login(new EmailLoginRequest(EMAIL,PASSWORD),IP); }
    private RefreshTokenResponse rotate(String token,String key) { return refresh.refresh(new RefreshTokenRequest(token),key,IP); }
    private String session(String token) { return decoder.decode(token).getId(); }
    private long count(String table) { return jdbc.queryForObject("select count(*) from "+table,Long.class); }
    private void me(String token,int status) throws Exception {
        mvc.perform(get("/v1/test/refresh-me").header("Authorization","Bearer "+token)).andExpect(status().is(status));
    }
    private void denied(Runnable action) {
        assertEquals(ErrorCode.UNAUTHENTICATED,assertThrows(BusinessException.class,action::run).getErrorCode());
    }

    @Test void loginKeepsExistingFieldsAndAddsThirtyDayRefresh() throws Exception {
        var result=login();
        assertEquals(43,result.refreshToken().length());
        assertEquals(Duration.ofHours(1),Duration.between(clock.instant(),result.expiresAt()));
        assertEquals(Duration.ofDays(30),Duration.between(clock.instant(),result.refreshExpiresAt()));
        assertEquals("Bearer",result.tokenType());
        assertFalse(result.toString().contains(result.refreshToken()));
        assertEquals(1,count("auth_refresh_families"));
        assertEquals(1,count("auth_refresh_tokens"));
        assertEquals(crypto.tokenHash(result.refreshToken()),jdbc.queryForObject("select token_hash from auth_refresh_tokens",String.class));
        me(result.accessToken(),200);
        var legacy=mapper.readValue("{\"userId\":1,\"accessToken\":\"legacy\",\"tokenType\":\"Bearer\",\"expiresAt\":\"2026-11-08T01:00:00Z\",\"onboardingCompleted\":false}",EmailLoginResponse.class);
        assertNull(legacy.refreshToken());
    }

    @Test void refreshWorksAfterAccessAndSessionExpire() throws Exception {
        var initial=login();
        clock.set(initial.expiresAt().plusSeconds(60));
        me(initial.accessToken(),401);
        var next=rotate(initial.refreshToken(),key());
        assertNotEquals(initial.refreshToken(),next.refreshToken());
        assertEquals(clock.instant().plusSeconds(3600),next.expiresAt());
        assertEquals(clock.instant().plus(Duration.ofDays(30)),next.refreshExpiresAt());
        me(next.accessToken(),200);
    }

    @Test void staleBearerHeaderIsIgnoredOnlyOnRefreshEndpoint() throws Exception {
        var initial=login();clock.set(initial.expiresAt().plusSeconds(60));
        mvc.perform(post("/v1/auth/refresh").header("Authorization","Bearer "+initial.accessToken())
                .header("Idempotency-Key",key()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("refreshToken",initial.refreshToken()))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.refreshToken").isString());
        me(initial.accessToken(),401);
    }

    @Test void lostResponseRetryReturnsSamePairAndDoesNotIssueTwice() {
        var initial=login();String key=key();
        var first=rotate(initial.refreshToken(),key);
        assertEquals(first,rotate(initial.refreshToken(),key));
        assertEquals(2,count("auth_sessions"));
        assertEquals(2,count("auth_refresh_tokens"));
        String encrypted=jdbc.queryForObject("select replay_response from auth_refresh_tokens where consumed_at is not null",String.class);
        assertFalse(encrypted.contains(first.refreshToken()));
        assertFalse(encrypted.contains(first.accessToken()));
        assertFalse(first.toString().contains(first.refreshToken()));
    }

    @Test void cachedLoginCannotRestoreAlreadyRotatedRefreshToken() {
        var initial=login();
        var tx=new TransactionTemplate(manager);
        tx.executeWithoutResult(t -> refresh.requireCachedUsable(initial.refreshToken(),
                initial.refreshExpiresAt(),user,session(initial.accessToken())));
        var next=rotate(initial.refreshToken(),key());
        denied(() -> tx.executeWithoutResult(t -> refresh.requireCachedUsable(initial.refreshToken(),
                initial.refreshExpiresAt(),user,session(initial.accessToken()))));
        tx.executeWithoutResult(t -> refresh.requireCachedUsable(null,null,user,session(next.accessToken())));
        assertNotNull(rotate(next.refreshToken(),key()));
    }

    @Test void oldTokenWithDifferentKeyRevokesOnlyItsOwnLoginFamily() throws Exception {
        var one=login();var other=login();
        var next=rotate(one.refreshToken(),key());
        denied(() -> rotate(one.refreshToken(),key()));
        me(one.accessToken(),401);me(next.accessToken(),401);me(other.accessToken(),200);
        denied(() -> rotate(next.refreshToken(),key()));
        assertNotNull(rotate(other.refreshToken(),key()));
    }

    @Test void retryAfterWindowFailsWithoutDestroyingLatestToken() {
        var initial=login();String key=key();var next=rotate(initial.refreshToken(),key);
        clock.set(clock.instant().plusSeconds(301));
        denied(() -> rotate(initial.refreshToken(),key));
        assertNotNull(rotate(next.refreshToken(),key()));
    }

    @Test void delayedRetryNeverRestoresAlreadyConsumedSuccessor() {
        var initial=login();String firstKey=key();var second=rotate(initial.refreshToken(),firstKey);
        var third=rotate(second.refreshToken(),key());
        denied(() -> rotate(initial.refreshToken(),firstKey));
        assertNotNull(rotate(third.refreshToken(),key()));
    }

    @Test void logoutRevokesRefreshAndAllAccessTokensInFamily() throws Exception {
        var initial=login();var other=login();var next=rotate(initial.refreshToken(),key());
        mvc.perform(post("/v1/auth/logout").header("Authorization","Bearer "+next.accessToken())).andExpect(status().isNoContent());
        denied(() -> rotate(next.refreshToken(),key()));
        me(initial.accessToken(),401);me(next.accessToken(),401);me(other.accessToken(),200);
        assertNotNull(rotate(other.refreshToken(),key()));
    }

    @Test void passwordChangeKeepsCurrentFamilyAndRevokesOtherDevices() throws Exception {
        var initial=login();var other=login();var next=rotate(initial.refreshToken(),key());
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            repository.lockAccount(user);
            changes.revokeOtherSessions(user,session(next.accessToken()),clock.instant());
        });
        me(next.accessToken(),200);me(other.accessToken(),401);
        denied(() -> rotate(other.refreshToken(),key()));
        assertNotNull(rotate(next.refreshToken(),key()));
    }

    @Test void passwordResetRevokesAllFamilies() throws Exception {
        var first=login();var second=login();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            repository.lockAccount(user);resets.revokeSessions(user,clock.instant());
        });
        denied(() -> rotate(first.refreshToken(),key()));denied(() -> rotate(second.refreshToken(),key()));
        assertEquals(2,jdbc.queryForObject("select count(*) from auth_refresh_families where revoked_at is not null",Integer.class));
        me(first.accessToken(),401);me(second.accessToken(),401);
    }

    @Test void blockedAccountAndAuthVersionChangePreventRefresh() {
        var initial=login();
        jdbc.update("update users set auth_version=auth_version+1 where id=?",user);
        denied(() -> rotate(initial.refreshToken(),key()));
        jdbc.update("update users set auth_version=0,status='DELETING' where id=?",user);
        denied(() -> rotate(initial.refreshToken(),key()));
    }

    @Test void expiredRefreshDoesNotCreateNewSession() {
        var initial=login();clock.set(initial.refreshExpiresAt());
        denied(() -> rotate(initial.refreshToken(),key()));assertEquals(1,count("auth_sessions"));
    }

    @Test void regularUseExtendsRefreshExpiryBeyondOriginalThirtyDays() {
        var initial=login();clock.set(clock.instant().plus(Duration.ofDays(29)));
        var next=rotate(initial.refreshToken(),key());
        assertTrue(next.refreshExpiresAt().isAfter(initial.refreshExpiresAt()));
        clock.set(initial.refreshExpiresAt().plusSeconds(60));
        assertNotNull(rotate(next.refreshToken(),key()));
    }

    @Test void preRefreshAccessSessionWithoutRefreshRowRemainsUsable() throws Exception {
        String id=key();var now=clock.instant();
        jdbc.update("insert into auth_sessions(id,user_id,created_at,expires_at) values (?,?,?,?)",
                id,user,java.time.LocalDateTime.ofInstant(now,java.time.ZoneOffset.UTC),
                java.time.LocalDateTime.ofInstant(now.plusSeconds(3600),java.time.ZoneOffset.UTC));
        me(issuer.issue(user,id,0,now,now.plusSeconds(3600)),200);
        assertEquals(0,count("auth_refresh_tokens"));
    }

    @Test void migrationUpgradesV19WithExistingSessionWithoutChangingIt() throws Exception {
        var source=Objects.requireNonNull(jdbc.getDataSource());
        try (var connection=source.getConnection()) {
            assertEquals("dadot_test_db",connection.getCatalog());
            assertTrue(connection.getMetaData().getURL().matches(
                    "jdbc:mariadb://127[.]0[.]0[.]1:[0-9]+/dadot_test_db(?:[?].*)?"),
                    "Migration cleanup must use the dedicated localhost test database");
        }

        // Integration tests already share and reset dadot_test_db. Use that
        // explicitly guarded schema and its configured credentials, without
        // CREATE DATABASE privileges or a separate root account.
        var upgrade=org.flywaydb.core.Flyway.configure().dataSource(source)
                .locations("classpath:db/migration").target("19")
                .cleanDisabled(false).load();
        var latest=org.flywaydb.core.Flyway.configure().dataSource(source)
                .locations("classpath:db/migration").load();
        try {
            upgrade.clean();
            upgrade.migrate();
            assertEquals("19",upgrade.info().current().getVersion().toString());
            jdbc.update("insert into users(id,nickname) values (9001,'이전 회원')");
            String session=key();
            jdbc.update("""
                    insert into auth_sessions(id,user_id,created_at,expires_at)
                    values (?,9001,'2026-11-08 00:00:00','2026-11-08 01:00:00')
                    """,session);
            var before=jdbc.queryForMap("select * from auth_sessions where id=?",session);

            latest.migrate();

            // V20 이후 마이그레이션이 추가되어도 V20 적용과 최신 상태를 함께 확인한다.
            assertTrue(latest.info().current().getVersion().compareTo(
                    org.flywaydb.core.api.MigrationVersion.fromVersion("20")) >= 0);
            assertEquals(0,latest.info().pending().length);
            assertEquals(before,jdbc.queryForMap("select * from auth_sessions where id=?",session));
            assertEquals(0,jdbc.queryForObject("select count(*) from auth_refresh_families",Integer.class));
            assertEquals(0,jdbc.queryForObject("select count(*) from auth_refresh_tokens",Integer.class));
        } finally {
            // Leave the shared test schema at the current version even if an
            // assertion fails, so later tests do not inherit a V19 schema.
            latest.migrate();
        }
    }

    @Test void revokedAccessSessionCannotRefreshEvenBeforeRefreshExpiry() {
        var initial=login();jdbc.update("update auth_sessions set revoked_at=created_at where user_id=?",user);
        denied(() -> rotate(initial.refreshToken(),key()));
    }

    @Test void forgedRefreshDoesNotRevokeLegitimateFamily() {
        var initial=login();denied(() -> rotate(crypto.generateToken(),key()));
        assertNotNull(rotate(initial.refreshToken(),key()));
    }

    @Test void tokenIssuanceFailureRollsBackConsumptionAndNewSession() {
        var initial=login();
        doThrow(new JwtEncodingException("test-only failure")).when(issuer).issue(anyLong(),anyString(),anyInt(),any(),any());
        assertThrows(JwtEncodingException.class,() -> rotate(initial.refreshToken(),key()));
        assertEquals(1,count("auth_sessions"));assertEquals(1,count("auth_refresh_tokens"));
        assertEquals(0,jdbc.queryForObject("select count(*) from auth_refresh_tokens where consumed_at is not null",Integer.class));
        reset(issuer);assertNotNull(rotate(initial.refreshToken(),key()));
    }

    @Test void storageFailureRollsBackConsumptionAndReturns503() {
        var initial=login();
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test storage"))
                .when(repository).createToken(anyString(),anyString(),anyLong(),anyString(),any(),any());
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,assertThrows(BusinessException.class,
                () -> rotate(initial.refreshToken(),key())).getErrorCode());
        assertEquals(1,count("auth_sessions"));assertEquals(1,count("auth_refresh_tokens"));
        reset(repository);assertNotNull(rotate(initial.refreshToken(),key()));
    }

    @Test void concurrentSameKeyCreatesOneSuccessor() throws Exception {
        var initial=login();String key=key();
        var results=parallel(() -> rotate(initial.refreshToken(),key),6);
        assertEquals(1,results.stream().map(RefreshTokenResponse::refreshToken).distinct().count());
        assertEquals(2,count("auth_sessions"));assertEquals(2,count("auth_refresh_tokens"));
    }

    @Test void concurrentDifferentKeysCannotLeaveReplayedFamilyActive() throws Exception {
        var initial=login();
        List<Object> result=parallel(() -> {
            try { return rotate(initial.refreshToken(),key()); }
            catch (BusinessException ex) { return ex.getErrorCode(); }
        },2);
        assertEquals(1,result.stream().filter(RefreshTokenResponse.class::isInstance).count());
        assertEquals(1,result.stream().filter(ErrorCode.UNAUTHENTICATED::equals).count());
        me(initial.accessToken(),401);
        var issued=(RefreshTokenResponse)result.stream().filter(RefreshTokenResponse.class::isInstance).findFirst().orElseThrow();
        me(issued.accessToken(),401);
    }

    @ParameterizedTest
    @ValueSource(strings={"{}","null","[]","{\"refreshToken\":null}","{\"refreshToken\":\"short\"}",
            "{\"refreshToken\":123}","{\"refreshToken\":\"x\",\"extra\":1}"})
    void malformedBodiesFailWithoutIssuingOrEchoingTokens(String body) throws Exception {
        mvc.perform(post("/v1/auth/refresh").header("Idempotency-Key",key()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        assertEquals(0,count("auth_sessions"));
    }

    @Test void requestKeyIsRequiredAndMustBeCanonicalUuid() throws Exception {
        String body=mapper.writeValueAsString(Map.of("refreshToken",crypto.generateToken()));
        mvc.perform(post("/v1/auth/refresh").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/v1/auth/refresh").header("Idempotency-Key","bad").contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/auth/refresh?extra=1").header("Idempotency-Key",key()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest());
        assertFalse(new RefreshTokenRequest(crypto.generateToken()).toString().contains("refreshToken="));
    }

    @Test void sixtyRequestsPerMinuteLimitPersistsAcrossInvalidTokens() {
        for (int i=0;i<60;i++) denied(() -> rotate(crypto.generateToken(),key()));
        assertEquals(ErrorCode.RATE_LIMITED,assertThrows(BusinessException.class,
                () -> rotate(crypto.generateToken(),key())).getErrorCode());
    }

    private <T> List<T> parallel(Callable<T> action,int size) throws Exception {
        try (var pool=Executors.newFixedThreadPool(size)) {
            var start=new CountDownLatch(1);List<Future<T>> futures=new ArrayList<>();
            for (int i=0;i<size;i++) futures.add(pool.submit(() -> { start.await();return action.call(); }));
            start.countDown();List<T> result=new ArrayList<>();
            for (var f:futures)result.add(f.get(20,TimeUnit.SECONDS));return result;
        }
    }

    @RestController static class Routes {
        @GetMapping("/v1/test/refresh-me") String me(@AuthenticationPrincipal UserPrincipal user) {
            return Long.toString(user.userId());
        }
    }
}
