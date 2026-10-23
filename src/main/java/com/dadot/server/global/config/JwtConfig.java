package com.dadot.server.global.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;

@Configuration(proxyBeanMethods = false)
public class JwtConfig {

    public static final String ISSUER = "dadot";
    public static final String AUDIENCE = "dadot-app";

    @Bean
    public SecretKey accessTokenKey(
            @Value("${app.jwt.secret-base64}") String secret
    ) {
        byte[] decoded;

        try {
            decoded = Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "JWT key must be Base64"
            );
        }

        if (decoded.length < 32) {
            throw new IllegalStateException(
                    "JWT key must be at least 32 bytes"
            );
        }

        SecretKey key = new SecretKeySpec(
                decoded,
                "HmacSHA256"
        );

        Arrays.fill(decoded, (byte) 0);

        return key;
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey accessTokenKey) {
        return new NimbusJwtEncoder(
                new ImmutableSecret<>(accessTokenKey)
        );
    }

    @Bean
    public JwtDecoder jwtDecoder(
            SecretKey accessTokenKey,
            Clock clock
    ) {
        var decoder = NimbusJwtDecoder
                .withSecretKey(accessTokenKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        var timestamp = new JwtTimestampValidator(
                Duration.ZERO
        );
        timestamp.setClock(clock);

        OAuth2TokenValidator<Jwt> required = jwt -> {
            if (jwt.getExpiresAt() == null
                    || jwt.getIssuedAt() == null
                    || jwt.getIssuedAt().isAfter(clock.instant())
                    || !jwt.getExpiresAt().equals(
                    jwt.getIssuedAt().plusSeconds(3600)
            )
                    || jwt.getAudience() == null
                    || !jwt.getAudience().contains(AUDIENCE)) {

                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token")
                );
            }

            return OAuth2TokenValidatorResult.success();
        };

        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        timestamp,
                        new JwtIssuerValidator(ISSUER),
                        required
                )
        );

        return decoder;
    }
}