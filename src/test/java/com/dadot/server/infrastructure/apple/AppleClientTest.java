package com.dadot.server.infrastructure.apple;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AppleClientTest {

    static final String NONCE = "a".repeat(43);
    static final String SUBJECT = "001.test.user";

    static RSAKey appleKey;
    static ECKey developerKey;

    @TempDir
    Path directory;

    AppleClient client;
    MockRestServiceServer server;
    AtomicReference<Instant> now;
    Path privateKey;

    @BeforeAll
    static void keys() throws Exception {
        appleKey = new RSAKeyGenerator(2048)
                .keyID("apple-key")
                .generate();

        developerKey = new ECKeyGenerator(Curve.P_256)
                .generate();
    }

    @BeforeEach
    void setup() throws Exception {
        now = new AtomicReference<>(
                Instant.parse("2026-10-24T00:00:00Z")
        );

        privateKey = directory.resolve("apple.p8");

        Files.writeString(
                privateKey,
                "-----BEGIN PRIVATE KEY-----\n"
                        + Base64.getEncoder().encodeToString(
                        developerKey.toECPrivateKey().getEncoded()
                )
                        + "\n-----END PRIVATE KEY-----\n"
        );

        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        Clock clock = new Clock() {
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            public Instant instant() {
                return now.get();
            }
        };

        client = new AppleClient(
                "3PVV8DQPL6",
                "com.dadot.app",
                "TESTKEY123",
                privateKey.toString(),
                builder.build(),
                JsonMapper.builder().build(),
                clock
        );
    }

    @Test
    void verifiesSignatureNonceClaimsAndCachesPublicKeys()
            throws Exception {
        keysResponse();

        String token = token(claims(), appleKey);
        var first = client.verifyIdentity(token, NONCE);

        assertEquals(SUBJECT, first.subject());
        assertEquals(
                "user@privaterelay.appleid.com",
                first.email()
        );
        assertEquals(
                first,
                client.verifyIdentity(token, NONCE)
        );
        assertFalse(first.toString().contains(SUBJECT));

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "issuer",
            "audience",
            "expired",
            "future",
            "nonce",
            "missingNonce",
            "missingExpiry",
            "missingIssuedAt",
            "subject",
            "notBefore",
            "email"
    })
    void rejectsInvalidSignedClaims(String field)
            throws Exception {
        var claims = claims();

        switch (field) {
            case "issuer" ->
                    claims.issuer("https://attacker.invalid");
            case "audience" ->
                    claims.audience("another.app");
            case "expired" ->
                    claims.expirationTime(Date.from(now.get()));
            case "future" ->
                    claims.issueTime(
                            Date.from(now.get().plusSeconds(31))
                    );
            case "nonce" ->
                    claims.claim("nonce", "b".repeat(64));
            case "missingNonce" ->
                    claims.claim("nonce", null);
            case "missingExpiry" ->
                    claims.expirationTime(null);
            case "missingIssuedAt" ->
                    claims.issueTime(null);
            case "subject" ->
                    claims.subject(" ");
            case "notBefore" ->
                    claims.notBeforeTime(
                            Date.from(now.get().plusSeconds(1))
                    );
            case "email" ->
                    claims.claim("email", "a".repeat(255));
        }

        keysResponse();

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> client.verifyIdentity(
                        token(claims, appleKey),
                        NONCE
                )
        );

        server.verify();
    }

    @Test
    void forgedSignatureIsRejected() throws Exception {
        var forged = new RSAKeyGenerator(2048)
                .keyID("apple-key")
                .generate();

        keysResponse();

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> client.verifyIdentity(
                        token(claims(), forged),
                        NONCE
                )
        );
    }

    @Test
    void malformedOrWrongAlgorithmDoesNotFetchKeys()
            throws Exception {
        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> client.verifyIdentity("bad", NONCE)
        );

        var wrong = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                claims().build()
        );

        wrong.sign(
                new com.nimbusds.jose.crypto.MACSigner(
                        new byte[32]
                )
        );

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> client.verifyIdentity(
                        wrong.serialize(),
                        NONCE
                )
        );

        server.verify();
    }

    @Test
    void unknownKeyRefreshIsThrottledAndRotationCanBeLoaded()
            throws Exception {
        keysResponse();

        var rotated = new RSAKeyGenerator(2048)
                .keyID("rotated")
                .generate();

        String token = token(claims(), rotated);

        server.expect(requestTo(AppleClient.KEYS_URL))
                .andRespond(
                        withSuccess(
                                new JWKSet(
                                        rotated.toPublicJWK()
                                ).toString(),
                                MediaType.APPLICATION_JSON
                        )
                );

        client.verifyIdentity(
                token(claims(), appleKey),
                NONCE
        );

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> client.verifyIdentity(token, NONCE)
        );

        now.set(now.get().plusSeconds(61));

        assertEquals(
                SUBJECT,
                client.verifyIdentity(token, NONCE).subject()
        );

        server.verify();
    }

    @Test
    void keyServerFailureIsUnavailableAndThrottled()
            throws Exception {
        server.expect(requestTo(AppleClient.KEYS_URL))
                .andRespond(withServerError());

        String token = token(claims(), appleKey);

        assertCode(
                ErrorCode.SERVICE_UNAVAILABLE,
                () -> client.verifyIdentity(token, NONCE)
        );
        assertCode(
                ErrorCode.SERVICE_UNAVAILABLE,
                () -> client.verifyIdentity(token, NONCE)
        );

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-json",
            "{\"keys\":[]}",
            "oversized"
    })
    void badKeyResponseIsUnavailable(String body)
            throws Exception {
        server.expect(requestTo(AppleClient.KEYS_URL))
                .andRespond(
                        withSuccess(
                                body.equals("oversized")
                                        ? "x".repeat(65_537)
                                        : body,
                                MediaType.APPLICATION_JSON
                        )
                );

        assertCode(
                ErrorCode.SERVICE_UNAVAILABLE,
                () -> client.verifyIdentity(
                        token(claims(), appleKey),
                        NONCE
                )
        );
    }

    @Test
    void exchangeUsesNativeClientAndEs256SecretThenVerifiesReturnedToken()
            throws Exception {
        keysResponse();

        String token = token(claims(), appleKey);

        var identity = new AppleClient.Verified(
                SUBJECT,
                now.get().plusSeconds(300),
                "user@privaterelay.appleid.com"
        );

        server.expect(requestTo(AppleClient.TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    String form = URLDecoder.decode(
                            ((MockClientHttpRequest) request)
                                    .getBodyAsString(),
                            StandardCharsets.UTF_8
                    );

                    assertTrue(
                            form.contains("client_id=com.dadot.app")
                    );
                    assertTrue(
                            form.contains("code=ONE_TIME_CODE")
                    );
                    assertTrue(
                            form.contains(
                                    "grant_type=authorization_code"
                            )
                    );
                    assertFalse(form.contains("redirect_uri"));

                    String secret = java.util.Arrays.stream(
                                    form.split("&")
                            )
                            .filter(
                                    x -> x.startsWith("client_secret=")
                            )
                            .findFirst()
                            .orElseThrow()
                            .substring("client_secret=".length());

                    try {
                        var jwt = SignedJWT.parse(secret);

                        assertEquals(
                                JWSAlgorithm.ES256,
                                jwt.getHeader().getAlgorithm()
                        );
                        assertEquals(
                                "TESTKEY123",
                                jwt.getHeader().getKeyID()
                        );
                        assertTrue(
                                jwt.verify(
                                        new ECDSAVerifier(
                                                developerKey.toECPublicKey()
                                        )
                                )
                        );

                        var claims = jwt.getJWTClaimsSet();

                        assertEquals(
                                "3PVV8DQPL6",
                                claims.getIssuer()
                        );
                        assertEquals(
                                "com.dadot.app",
                                claims.getSubject()
                        );
                        assertEquals(
                                java.util.List.of(AppleClient.ISSUER),
                                claims.getAudience()
                        );
                        assertEquals(
                                now.get().plusSeconds(300),
                                claims.getExpirationTime().toInstant()
                        );

                    } catch (Exception ex) {
                        throw new AssertionError(ex);
                    }
                })
                .andRespond(
                        withSuccess(
                                "{\"id_token\":\"" + token + "\"}",
                                MediaType.APPLICATION_JSON
                        )
                );

        assertEquals(
                identity,
                client.verifyIdentity(token, NONCE)
        );
        assertEquals(
                identity,
                client.exchange(
                        "ONE_TIME_CODE",
                        NONCE,
                        identity
                )
        );

        server.verify();
    }

    @Test
    void exchangedIdentityCannotBelongToAnotherUser()
            throws Exception {
        keysResponse();

        var identity = new AppleClient.Verified(
                SUBJECT,
                now.get().plusSeconds(300),
                "user@privaterelay.appleid.com"
        );

        String other = token(
                claims().subject("other-user"),
                appleKey
        );

        server.expect(requestTo(AppleClient.TOKEN_URL))
                .andRespond(
                        withSuccess(
                                "{\"id_token\":\"" + other + "\"}",
                                MediaType.APPLICATION_JSON
                        )
                );

        client.verifyIdentity(
                token(claims(), appleKey),
                NONCE
        );

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL,
                () -> client.exchange(
                        "CODE",
                        NONCE,
                        identity
                )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "invalid_grant",
            "invalid_client",
            "server_error",
            "malformed",
            "missingId"
    })
    void mapsCodeExchangeErrorsWithoutLeakingBody(
            String error
    ) {
        server.expect(requestTo(AppleClient.TOKEN_URL))
                .andRespond(
                        error.equals("server_error")
                                ? withServerError()
                                : error.equals("malformed")
                                ? withSuccess(
                                "bad-json",
                                MediaType.APPLICATION_JSON
                        )
                                : error.equals("missingId")
                                ? withSuccess(
                                "{}",
                                MediaType.APPLICATION_JSON
                        )
                                : withStatus(HttpStatus.BAD_REQUEST)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(
                                        "{\"error\":\"" + error + "\"}"
                                )
                );

        assertCode(
                error.equals("invalid_grant")
                        ? ErrorCode.INVALID_SOCIAL_CREDENTIAL
                        : ErrorCode.SERVICE_UNAVAILABLE,
                () -> client.exchange(
                        "CODE",
                        NONCE,
                        new AppleClient.Verified(
                                SUBJECT,
                                now.get().plusSeconds(300),
                                null
                        )
                )
        );
    }

    @Test
    void missingPrivateKeyFailsWithoutNetworkOrSecretLeak()
            throws Exception {
        Files.delete(privateKey);

        var ex = assertThrows(
                BusinessException.class,
                client::checkConfigured
        );

        assertEquals(
                ErrorCode.SERVICE_UNAVAILABLE,
                ex.getErrorCode()
        );
        assertFalse(
                ex.toString().contains(privateKey.toString())
        );

        server.verify();
    }

    @Test
    void unverifiedEmailIsNotTrusted() throws Exception {
        keysResponse();

        assertNull(
                client.verifyIdentity(
                        token(
                                claims().claim("email_verified", false),
                                appleKey
                        ),
                        NONCE
                ).email()
        );
    }

    private void keysResponse() {
        server.expect(requestTo(AppleClient.KEYS_URL))
                .andRespond(
                        withSuccess(
                                new JWKSet(
                                        appleKey.toPublicJWK()
                                ).toString(),
                                MediaType.APPLICATION_JSON
                        )
                );
    }

    private JWTClaimsSet.Builder claims() throws Exception {
        return new JWTClaimsSet.Builder()
                .issuer(AppleClient.ISSUER)
                .audience("com.dadot.app")
                .subject(SUBJECT)
                .issueTime(
                        Date.from(now.get().minusSeconds(1))
                )
                .expirationTime(
                        Date.from(now.get().plusSeconds(300))
                )
                .claim(
                        "nonce",
                        HexFormat.of().formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                NONCE.getBytes(
                                                        StandardCharsets.US_ASCII
                                                )
                                        )
                        )
                )
                .claim(
                        "email",
                        "user@privaterelay.appleid.com"
                )
                .claim("email_verified", "true");
    }

    private String token(
            JWTClaimsSet.Builder claims,
            RSAKey key
    ) throws Exception {
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(key.getKeyID())
                        .build(),
                claims.build()
        );

        jwt.sign(new RSASSASigner(key));

        return jwt.serialize();
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