package com.dadot.server.global.security;

import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SessionJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            long userId = Long.parseLong(jwt.getSubject());

            String sessionId = UUID.fromString(
                    jwt.getId()
            ).toString();

            Number version = jwt.getClaim("av");

            if (userId <= 0
                    || version == null
                    || version.longValue() < 0
                    || version.longValue() > Integer.MAX_VALUE
                    || version.doubleValue() != version.longValue()
                    || !sessionId.equals(jwt.getId())
                    || jwt.getIssuedAt() == null
                    || jwt.getExpiresAt() == null) {
                throw invalidToken();
            }

            LocalDateTime now = LocalDateTime.ofInstant(
                    clock.instant(),
                    ZoneOffset.UTC
            );

            var roles = jdbc.query(
                    """
                    select u.role
                    from auth_sessions s
                    join users u on u.id = s.user_id
                    where s.id = ?
                      and s.user_id = ?
                      and s.revoked_at is null
                      and s.expires_at > ?
                      and s.created_at <= ?
                      and s.created_at = ?
                      and s.expires_at = ?
                      and u.status = 'ACTIVE'
                      and u.auth_version = ?
                    """,
                    (rs, row) -> rs.getString("role"),
                    sessionId,
                    userId,
                    now,
                    now,
                    LocalDateTime.ofInstant(
                            jwt.getIssuedAt(),
                            ZoneOffset.UTC
                    ),
                    LocalDateTime.ofInstant(
                            jwt.getExpiresAt(),
                            ZoneOffset.UTC
                    ),
                    version.longValue()
            );

            if (roles.isEmpty()) {
                throw invalidToken();
            }

            return UsernamePasswordAuthenticationToken.authenticated(
                    new UserPrincipal(userId),
                    null,
                    List.of(
                            new SimpleGrantedAuthority(
                                    "ROLE_" + roles.getFirst()
                            )
                    )
            );

        } catch (
                TransientDataAccessException
                | DataAccessResourceFailureException ex
        ) {
            throw new AuthenticationServiceException(
                    "Authentication storage unavailable"
            );
        } catch (
                IllegalArgumentException
                | NullPointerException
                | ClassCastException ex
        ) {
            throw invalidToken();
        }
    }

    private static OAuth2AuthenticationException invalidToken() {
        return new OAuth2AuthenticationException(
                new OAuth2Error("invalid_token")
        );
    }
}