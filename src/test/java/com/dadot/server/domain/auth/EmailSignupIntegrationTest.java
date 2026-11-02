package com.dadot.server.domain.auth;

import com.dadot.server.domain.auth.dto.request.*;
import com.dadot.server.domain.auth.dto.response.EmailSignupResponse;
import com.dadot.server.domain.auth.entity.VerificationPurpose;
import com.dadot.server.domain.auth.service.*;
import com.dadot.server.domain.appconfig.service.SignupTermsService;
import com.dadot.server.global.exception.*;
import com.dadot.server.global.security.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({EmailVerificationIntegrationTest.TestConfig.class, EmailSignupIntegrationTest.TestRoutes.class})
class EmailSignupIntegrationTest {

    private static final String EMAIL = "signup@example.invalid";
    private static final String PASSWORD = "Example123!";
    private static final String TERMS_LOCK =
            "db99e235c84abf94e7c3d11b65b25a90606d982cb8dce3ac9eee3e920c18d969";
    @Autowired EmailSignupService signup;
    @Autowired EmailVerificationService issuance;
    @Autowired EmailVerificationConfirmationService confirmation;
    @Autowired EmailVerificationIntegrationTest.MutableClock clock;
    @Autowired EmailVerificationIntegrationTest.FakeMailer mailer;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtDecoder decoder;
    @Autowired JwtEncoder encoder;
    @Autowired SessionJwtAuthenticationConverter authentication;
    @Autowired SignupTermsService terms;
    @Autowired JsonMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactionManager;
    private long termsId;

    @BeforeEach
    void reset() {
        assertEquals("dadot_test_db", jdbc.queryForObject("select database()", String.class));
        jdbc.execute("drop trigger if exists auth03_fail_save");
        for (String table : List.of("api_idempotency_requests", "auth_request_guards", "auth_proofs",
                "email_verifications", "auth_sessions", "auth_identities", "user_agreements",
                "user_onboarding", "users", "service_terms")) {
            jdbc.update("delete from " + table);
        }
        clock.set(Instant.parse("2026-10-23T10:00:00Z"));
        mailer.clear();
        termsId = publish("v1", clock.instant().minusSeconds(3600), true);
    }

    @AfterEach
    void cleanup() {
        jdbc.execute("drop trigger if exists auth03_fail_save");
        // 다른 테스트 클래스와 약관 데이터를 공유하지 않는다.
        jdbc.update("delete from user_agreements");
        jdbc.update("delete from service_terms");
    }

    @Test
    void createsAllRecordsAndUsableJwtWithoutPlainSecrets() {
        var request = request(proof(), termsId);
        var result = signup.signup(request, key());
        assertEquals("Bearer", result.tokenType());
        assertFalse(result.onboardingCompleted());
        assertEquals(clock.instant().plusSeconds(3600), result.expiresAt());
        for (String table : List.of("users", "auth_identities", "user_agreements", "user_onboarding",
                "auth_sessions", "auth_proofs")) {
            assertEquals(1, count(table), table);
        }
        assertTrue(passwords.matches(PASSWORD, jdbc.queryForObject(
                "select password_hash from auth_identities", String.class)));
        assertNotNull(jdbc.queryForObject("select consumed_at from auth_proofs", LocalDateTime.class));
        var jwt = decoder.decode(result.accessToken());
        assertEquals(Long.toString(result.userId()), jwt.getSubject());
        assertEquals(new UserPrincipal(result.userId()), authentication.convert(jwt).getPrincipal());
        var row = jdbc.queryForMap("select * from api_idempotency_requests where operation = 'POST /v1/auth/signup'");
        assertNull(row.get("response_payload"));
        assertFalse(row.get("encrypted_response").toString().contains(result.accessToken()));
        assertEquals(termsId, jdbc.queryForObject("select terms_id from user_agreements", Long.class));
    }

    @Test
    void successfulReplayPrecedesConsumedAndExpiredProofChecks() {
        var request = request(proof(), termsId);
        String key = key();
        var first = signup.signup(request, key);
        clock.advance(601);
        assertEquals(first, signup.signup(request, key));
        assertEquals(1, count("users"));
        assertEquals(1, count("auth_sessions"));
    }

    @Test
    void changedBodyConflictsIncludingPasswordTokenAndTerms() {
        var request = request(proof(), termsId);
        String key = key();
        signup.signup(request, key);
        for (EmailSignupRequest changed : List.of(
                new EmailSignupRequest(EMAIL, "Changed123!", request.verificationToken(), List.of(termsId)),
                new EmailSignupRequest("other@example.invalid", PASSWORD, request.verificationToken(), List.of(termsId)),
                request("A".repeat(43), termsId), request(request.verificationToken(), termsId + 100))) {
            assertCode(ErrorCode.IDEMPOTENCY_CONFLICT, () -> signup.signup(changed, key));
        }
    }

    @Test
    void consumedProofWithNewKeyDoesNotCreateAnotherUser() {
        var request = request(proof(), termsId);
        signup.signup(request, key());
        assertCode(ErrorCode.INVALID_VERIFICATION_TOKEN, () -> signup.signup(request, key()));
        assertEquals(1, count("users"));
    }

    @Test
    void wrongEmailCannotUseProof() {
        var request = new EmailSignupRequest("other@example.invalid", PASSWORD, proof(), List.of(termsId));
        assertCode(ErrorCode.INVALID_VERIFICATION_TOKEN, () -> signup.signup(request, key()));
        assertNoSignup();
    }

    @Test
    void unknownProofIsRejected() {
        assertCode(ErrorCode.INVALID_VERIFICATION_TOKEN, () -> signup.signup(request("A".repeat(43), termsId), key()));
        assertEquals(0, count("users"));
    }

    @Test
    void proofExpiresAtExactBoundary() {
        var request = request(proof(), termsId);
        clock.advance(600);
        assertCode(ErrorCode.INVALID_VERIFICATION_TOKEN, () -> signup.signup(request, key()));
        assertNoSignup();
    }

    @Test
    void resendInvalidatesPreviousProof() {
        var request = request(proof(), termsId);
        clock.advance(60);
        issuance.issue(new IssueEmailVerificationRequest(EMAIL, VerificationPurpose.SIGN_UP), key(), "192.0.2.31", null);
        assertCode(ErrorCode.INVALID_VERIFICATION_TOKEN, () -> signup.signup(request, key()));
        assertNoSignup();
    }

    @Test
    void emptyConsentDoesNotConsumeProof() {
        String proof = proof();
        assertCode(ErrorCode.TERMS_NOT_ACCEPTED, () -> signup.signup(request(proof), key()));
        assertNoSignup();
    }

    @Test
    void staleUnknownAndAdditionalTermsDoNotConsumeProof() {
        String proof = proof();
        publish("v2", clock.instant(), true);
        for (var request : List.of(request(proof, termsId), request(proof, termsId + 100),
                request(proof, termsId, termsId + 100))) {
            assertCode(ErrorCode.TERMS_CHANGED, () -> signup.signup(request, key()));
        }
        assertNoSignup();
    }

    @Test
    void duplicateTermsAreInputError() {
        String proof = proof();
        assertCode(ErrorCode.INVALID_INPUT, () -> signup.signup(request(proof, termsId, termsId), key()));
        assertNoSignup();
    }

    @Test
    void failedTermsRequestKeepsBodyBindingAndRequiresNewKey() {
        String proof = proof();
        String key = key();
        var rejected = request(proof);
        assertCode(ErrorCode.TERMS_NOT_ACCEPTED, () -> signup.signup(rejected, key));
        assertCode(ErrorCode.TERMS_NOT_ACCEPTED, () -> signup.signup(rejected, key));
        assertCode(ErrorCode.IDEMPOTENCY_CONFLICT, () -> signup.signup(request(proof, termsId), key));
        assertNoSignup();
        assertNotNull(signup.signup(request(proof, termsId), key()));
    }

    @Test
    void invalidatedProofCannotSignup() {
        var request = request(proof(), termsId);
        jdbc.update("update auth_proofs set invalidated_at = issued_at");
        assertCode(ErrorCode.INVALID_VERIFICATION_TOKEN, () -> signup.signup(request, key()));
        assertNoSignup();
    }

    @Test
    void noPublishedTermsDisablesSignup() {
        jdbc.update("update service_terms set published_at = null");
        assertFalse(terms.config().signupAvailable());
        assertNull(terms.config().termsUrl());
        assertTrue(terms.config().signupTerms().isEmpty());
        String proof = proof();
        assertCode(ErrorCode.SIGNUP_TERMS_UNAVAILABLE, () -> signup.signup(request(proof, termsId), key()));
        assertNoSignup();
    }

    @Test
    void configExcludesFutureAndDraftTerms() throws Exception {
        publish("future", clock.instant().plusSeconds(100), true);
        publish("draft", clock.instant().plusSeconds(200), false);
        mvc.perform(get("/v1/app-config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signupAvailable").value(true))
                .andExpect(jsonPath("$.data.signupTerms[0].termsId").value(termsId));
    }

    @Test
    void successfulHttpResponseUses201NoStoreAndAcceptsNormalizedEmail() throws Exception {
        var request = new EmailSignupRequest(" SIGNUP@EXAMPLE.INVALID ", PASSWORD, proof(), List.of(termsId));
        mvc.perform(post("/v1/auth/signup").header("Idempotency-Key", key().toUpperCase(Locale.ROOT))
                        .contentType("application/json").content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.error").isEmpty());
    }

    @Test
    void invalidHttpInputCreatesNothing() throws Exception {
        String proof = proof();
        for (String body : List.of(
                "{\"email\":\"bad\",\"password\":\"Example123!\",\"verificationToken\":\"" + proof + "\",\"acceptedTermsIds\":[1]}",
                mapper.writeValueAsString(new EmailSignupRequest(EMAIL, "onlyletters", proof, List.of(termsId))),
                mapper.writeValueAsString(new EmailSignupRequest(EMAIL, "Example12\u00a0", proof, List.of(termsId))),
                mapper.writeValueAsString(new EmailSignupRequest(EMAIL, PASSWORD, proof, Arrays.asList((Long) null))))) {
            mvc.perform(post("/v1/auth/signup").header("Idempotency-Key", key())
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/v1/auth/signup").header("Idempotency-Key", "bad")
                        .contentType("application/json").content(mapper.writeValueAsString(request(proof, termsId))))
                .andExpect(status().isBadRequest());
        assertNoSignup();
    }

    @Test
    void simultaneousSameKeyCreatesExactlyOneUserAndSession() throws Exception {
        var request = request(proof(), termsId);
        String key = key();
        var results = parallel(() -> signup.signup(request, key));
        assertEquals(1, new HashSet<>(results).size());
        assertEquals(1, count("users"));
        assertEquals(1, count("auth_sessions"));
    }

    @Test
    void simultaneousDifferentKeysCannotReuseProof() throws Exception {
        var request = request(proof(), termsId);
        var results = parallel(() -> {
            try { signup.signup(request, key()); return "OK"; }
            catch (BusinessException ex) { return ex.getErrorCode().name(); }
        });
        assertEquals(1, Collections.frequency(results, "OK"));
        assertEquals(7, Collections.frequency(results, "INVALID_VERIFICATION_TOKEN"));
        assertEquals(1, count("users"));
    }

    @Test
    void persistenceFailureRollsBackUserProofConsentAndSession() {
        var request = request(proof(), termsId);
        jdbc.execute("""
                create trigger auth03_fail_save before insert on api_idempotency_requests
                for each row begin
                    if new.operation = 'POST /v1/auth/signup' then
                        signal sqlstate '45000' set message_text = 'synthetic failure';
                    end if;
                end
                """);
        assertThrows(RuntimeException.class, () -> signup.signup(request, key()));
        assertNoSignup();
        assertEquals(0, count("auth_sessions"));
        assertEquals(0, count("user_agreements"));
        assertEquals(0, count("user_onboarding"));
    }

    @Test
    void jwtAuthenticatesProtectedRouteAndUsesDatabaseRole() throws Exception {
        var result = signup.signup(request(proof(), termsId), key());
        mvc.perform(get("/v1/test/current-user").header("Authorization", "Bearer " + result.accessToken()))
                .andExpect(status().isOk()).andExpect(content().string(Long.toString(result.userId())));
        mvc.perform(get("/v1/admin/test").header("Authorization", "Bearer " + result.accessToken()))
                .andExpect(status().isForbidden());
        jdbc.update("update users set role = 'ADMIN' where id = ?", result.userId());
        mvc.perform(get("/v1/admin/test").header("Authorization", "Bearer " + result.accessToken()))
                .andExpect(status().isOk());
    }

    @Test
    void absentInvalidAndExpiredJwtAreRejected() throws Exception {
        var result = signup.signup(request(proof(), termsId), key());
        mvc.perform(get("/v1/test/current-user")).andExpect(status().isUnauthorized());
        String token = result.accessToken();
        int dot = token.lastIndexOf('.') + 1;
        String tampered = token.substring(0, dot) + (token.charAt(dot) == 'A' ? 'B' : 'A') + token.substring(dot + 1);
        mvc.perform(get("/v1/test/current-user").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
        clock.advance(3600);
        mvc.perform(get("/v1/test/current-user").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedSessionCannotAuthenticateOrReplay() throws Exception {
        var request = request(proof(), termsId);
        String key = key();
        var result = signup.signup(request, key);
        jdbc.update("update auth_sessions set revoked_at = created_at");
        mvc.perform(get("/v1/test/current-user").header("Authorization", "Bearer " + result.accessToken()))
                .andExpect(status().isUnauthorized());
        assertCode(ErrorCode.UNAUTHENTICATED, () -> signup.signup(request, key));
        assertEquals(1, count("auth_sessions"));
    }

    @Test
    void deletingUserCannotAuthenticateOrReplay() throws Exception {
        var request = request(proof(), termsId);
        String key = key();
        var result = signup.signup(request, key);
        jdbc.update("update users set status = 'DELETING'");
        mvc.perform(get("/v1/test/current-user").header("Authorization", "Bearer " + result.accessToken()))
                .andExpect(status().isUnauthorized());
        assertCode(ErrorCode.UNAUTHENTICATED, () -> signup.signup(request, key));
    }

    @Test
    void authVersionChangeImmediatelyRejectsExistingToken() throws Exception {
        var result = signup.signup(request(proof(), termsId), key());
        jdbc.update("update users set auth_version = auth_version + 1");
        mvc.perform(get("/v1/test/current-user").header("Authorization", "Bearer " + result.accessToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signedButInvalidClaimsAreRejected() throws Exception {
        var result = signup.signup(request(proof(), termsId), key());
        var original = decoder.decode(result.accessToken());
        for (String change : List.of("issuer", "audience", "missing-exp", "missing-version", "session")) {
            Map<String, Object> claims = new HashMap<>(original.getClaims());
            switch (change) {
                case "issuer" -> claims.put("iss", "other");
                case "audience" -> claims.put("aud", List.of("other"));
                case "missing-exp" -> claims.remove("exp");
                case "missing-version" -> claims.remove("av");
                case "session" -> claims.put("jti", key());
                default -> throw new AssertionError();
            }
            var token = encoder.encode(JwtEncoderParameters.from(
                    JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).type("JWT").build(),
                    JwtClaimsSet.builder().claims(value -> value.putAll(claims)).build())).getTokenValue();
            mvc.perform(get("/v1/test/current-user").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void removedIdentityPreventsSignupReplay() {
        var request = request(proof(), termsId);
        String key = key();
        signup.signup(request, key);
        jdbc.update("delete from auth_identities");
        assertCode(ErrorCode.UNAUTHENTICATED, () -> signup.signup(request, key));
        assertEquals(1, count("users"));
    }

    @Test
    void maintenancePurgesSignupResponseButKeepsExpiredKey() {
        var request = request(proof(), termsId);
        String key = key();
        signup.signup(request, key);
        clock.advance(86401);
        new VerificationMaintenance(jdbc, clock).clean();
        var row = jdbc.queryForMap("select * from api_idempotency_requests where operation = 'POST /v1/auth/signup'");
        assertNull(row.get("encrypted_response"));
        assertNotNull(row.get("key_hash"));
        assertCode(ErrorCode.IDEMPOTENCY_CONFLICT, () -> signup.signup(request, key));
    }

    @Test
    void expiredSessionDoesNotIssueNewJwtOnReplay() {
        var request = request(proof(), termsId);
        String key = key();
        signup.signup(request, key);
        clock.advance(3600);
        assertCode(ErrorCode.UNAUTHENTICATED, () -> signup.signup(request, key));
        assertEquals(1, count("auth_sessions"));
        clock.advance(86400);
        assertCode(ErrorCode.IDEMPOTENCY_CONFLICT, () -> signup.signup(request, key));
    }

    @Test
    void termsPublicationAndSignupShareLockAndValidationTime() throws Exception {
        var request = request(proof(), termsId);
        try (var executor = Executors.newSingleThreadExecutor()) {
            final Future<?>[] pending = new Future<?>[1];
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                jdbc.update("""
                        insert into auth_request_guards(guard_hash, expires_at)
                        values (?, ?) on duplicate key update expires_at = values(expires_at)
                        """, TERMS_LOCK, LocalDateTime.ofInstant(clock.instant().plusSeconds(3600), ZoneOffset.UTC));
                jdbc.queryForObject("select request_count from auth_request_guards where guard_hash = ? for update",
                        Integer.class, TERMS_LOCK);
                clock.advance(1);
                publish("v2", clock.instant(), true);
                pending[0] = executor.submit(() -> assertCode(ErrorCode.TERMS_CHANGED,
                        () -> signup.signup(request, key())));
            });
            pending[0].get(20, TimeUnit.SECONDS);
        }
        assertNoSignup();
    }

    @Test
    void dtoStringsDoNotLeakEmailPasswordProofOrJwt() {
        String proof = proof();
        var request = request(proof, termsId);
        var response = signup.signup(request, key());
        assertFalse(request.toString().contains(EMAIL));
        assertFalse(request.toString().contains(PASSWORD));
        assertFalse(request.toString().contains(proof));
        assertFalse(response.toString().contains(response.accessToken()));
    }

    private String proof() {
        var issued = issuance.issue(new IssueEmailVerificationRequest(EMAIL, VerificationPurpose.SIGN_UP),
                key(), "192.0.2.31", null);
        return confirmation.verify(issued.verificationId(), mailer.messages.getLast().code(), key(), null)
                .verificationToken();
    }

    private long publish(String version, Instant effective, boolean published) {
        jdbc.update("""
                insert into service_terms(version, title, content_url, content_hash, published_at, effective_at)
                values (?, '테스트 전용 약관', 'https://example.invalid/terms', ?, ?, ?)
                """, version, "a".repeat(64), published ? utc(effective.minusSeconds(1)) : null, utc(effective));
        return jdbc.queryForObject("select id from service_terms where version = ?", Long.class, version);
    }

    private EmailSignupRequest request(String proof, Long... ids) {
        return new EmailSignupRequest(EMAIL, PASSWORD, proof, List.of(ids));
    }

    private void assertNoSignup() {
        assertEquals(0, count("users"));
        assertEquals(0, jdbc.queryForObject("select count(*) from auth_proofs where consumed_at is not null", Integer.class));
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private void assertCode(ErrorCode code, Runnable action) {
        assertEquals(code, assertThrows(BusinessException.class, action::run).getErrorCode());
    }

    private String key() { return UUID.randomUUID().toString(); }
    private LocalDateTime utc(Instant time) { return LocalDateTime.ofInstant(time, ZoneOffset.UTC); }

    private <T> List<T> parallel(Callable<T> action) throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> { start.await(); return action.call(); }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (var future : futures) { results.add(future.get(30, TimeUnit.SECONDS)); }
            return results;
        }
    }

    @RestController
    static class TestRoutes {
        @GetMapping("/v1/test/current-user")
        public long current(@AuthenticationPrincipal UserPrincipal principal) { return principal.userId(); }
        @GetMapping("/v1/admin/test")
        public String admin() { return "OK"; }
    }
}
