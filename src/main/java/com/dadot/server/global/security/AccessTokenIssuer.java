package com.dadot.server.global.security;

import com.dadot.server.global.config.JwtConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AccessTokenIssuer {

    private final JwtEncoder encoder;

    public String issue(
            long userId,
            String sessionId,
            int authVersion,
            Instant issuedAt,
            Instant expiresAt
    ) {
        var claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .audience(List.of(JwtConfig.AUDIENCE))
                .subject(Long.toString(userId))
                .id(sessionId)
                .issuedAt(issuedAt)
                .notBefore(issuedAt)
                .expiresAt(expiresAt)
                .claim("av", authVersion)
                .build();

        var header = JwsHeader
                .with(MacAlgorithm.HS256)
                .type("JWT")
                .build();

        return encoder.encode(
                JwtEncoderParameters.from(header, claims)
        ).getTokenValue();
    }
}