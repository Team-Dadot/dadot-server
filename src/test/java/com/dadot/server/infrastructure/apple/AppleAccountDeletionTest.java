package com.dadot.server.infrastructure.apple;

import com.dadot.server.global.exception.BusinessException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AppleAccountDeletionTest {
    @TempDir
    Path directory;

    @Test
    void exchangesCodeRetainsRefreshTokenAndRevokesWithCorrectForm()
            throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = client(builder.build());

        Instant now = Instant.parse("2026-11-01T00:00:00Z");

        var key = new RSAKeyGenerator(2048)
                .keyID("apple-test-key")
                .generate();

        String nonce = "a".repeat(43);

        var token = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .keyID(key.getKeyID())
                        .build(),
                new JWTClaimsSet.Builder()
                        .issuer("https://appleid.apple.com")
                        .audience("com.dadot.app")
                        .subject("apple-user")
                        .issueTime(Date.from(now))
                        .expirationTime(
                                Date.from(now.plusSeconds(300))
                        )
                        .claim(
                                "nonce",
                                HexFormat.of().formatHex(
                                        MessageDigest.getInstance("SHA-256")
                                                .digest(
                                                        nonce.getBytes(
                                                                StandardCharsets.US_ASCII
                                                        )
                                                )
                                )
                        )
                        .build()
        );

        token.sign(new RSASSASigner(key));

        server.expect(requestTo(AppleClient.TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(
                        withSuccess(
                                "{\"id_token\":\""
                                        + token.serialize()
                                        + "\",\"refresh_token\":\"test-refresh\"}",
                                MediaType.APPLICATION_JSON
                        )
                );

        server.expect(requestTo(AppleClient.KEYS_URL))
                .andRespond(
                        withSuccess(
                                new JWKSet(
                                        key.toPublicJWK()
                                ).toString(),
                                MediaType.APPLICATION_JSON
                        )
                );

        var prepared = client.prepareDeletion(
                "code",
                nonce,
                new AppleClient.Verified(
                        "apple-user",
                        now.plusSeconds(300),
                        null
                )
        );

        assertEquals("test-refresh", prepared.refreshToken());
        assertFalse(prepared.toString().contains("test-refresh"));

        server.verify();
        server.reset();

        server.expect(
                        requestTo(
                                "https://appleid.apple.com/auth/revoke"
                        )
                )
                .andExpect(method(HttpMethod.POST))
                .andExpect(
                        content().string(
                                org.hamcrest.Matchers.containsString(
                                        "token=test-refresh&token_type_hint=refresh_token"
                                )
                        )
                )
                .andRespond(withSuccess());

        client.revoke(prepared.refreshToken());

        server.verify();
    }

    @Test
    void failedRevocationIsRetryableAndInvalidTokenNeverLeavesProcess()
            throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = client(builder.build());

        server.expect(
                        requestTo(
                                "https://appleid.apple.com/auth/revoke"
                        )
                )
                .andRespond(
                        withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                );

        assertThrows(
                BusinessException.class,
                () -> client.revoke("test-refresh")
        );

        server.verify();

        assertThrows(
                BusinessException.class,
                () -> client.revoke("token\nheader")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null",
            "123",
            "true",
            "\"\""
    })
    void deletionRequiresStringRefreshToken(String refresh)
            throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = client(builder.build());

        server.expect(requestTo(AppleClient.TOKEN_URL))
                .andRespond(
                        withSuccess(
                                "{\"id_token\":\"a.b.c\",\"refresh_token\":"
                                        + refresh
                                        + "}",
                                MediaType.APPLICATION_JSON
                        )
                );

        assertThrows(
                BusinessException.class,
                () -> client.prepareDeletion(
                        "code",
                        "a".repeat(43),
                        new AppleClient.Verified(
                                "apple-user",
                                Instant.parse("2026-11-01T00:05:00Z"),
                                null
                        )
                )
        );

        server.verify();
    }

    private AppleClient client(RestClient rest) throws Exception {
        Path file = directory.resolve("test.p8");

        Files.writeString(
                file,
                "-----BEGIN PRIVATE KEY-----\n"
                        + Base64.getEncoder().encodeToString(
                        new ECKeyGenerator(Curve.P_256)
                                .generate()
                                .toECPrivateKey()
                                .getEncoded()
                )
                        + "\n-----END PRIVATE KEY-----\n"
        );

        return new AppleClient(
                "TESTTEAM12",
                "com.dadot.app",
                "TESTKEY123",
                file.toString(),
                rest,
                JsonMapper.builder().build(),
                Clock.fixed(
                        Instant.parse("2026-11-01T00:00:00Z"),
                        ZoneOffset.UTC
                )
        );
    }
}