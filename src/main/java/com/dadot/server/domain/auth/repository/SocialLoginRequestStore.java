package com.dadot.server.domain.auth.repository;

import com.dadot.server.domain.auth.dto.request.AppleLoginRequest;
import com.dadot.server.domain.auth.dto.response.SocialLoginResponse;
import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class SocialLoginRequestStore {

    public static final String OPERATION =
            "POST /v1/auth/social/kakao";

    private final JdbcTemplate jdbc;
    private final VerificationCrypto hmac;
    private final VerificationTokenCrypto crypto;
    private final JsonMapper mapper;

    public RequestKey key(
            String accessToken,
            String idempotencyKey
    ) {
        return new RequestKey(
                "KAKAO",
                hmac.hmac(
                        "idempotency-scope",
                        "AUTH-05",
                        "KAKAO",
                        accessToken
                ),
                hmac.hmac(
                        "idempotency-key",
                        idempotencyKey.toLowerCase(Locale.ROOT)
                ),
                hmac.hmac(
                        "AUTH-05-body",
                        "KAKAO",
                        accessToken
                )
        );
    }

    public RequestKey appleKey(
            AppleLoginRequest body,
            String idempotencyKey
    ) {
        return new RequestKey(
                "APPLE",
                hmac.hmac(
                        "idempotency-scope",
                        "AUTH-05",
                        "APPLE",
                        body.identityToken()
                ),
                hmac.hmac(
                        "idempotency-key",
                        idempotencyKey.toLowerCase(Locale.ROOT)
                ),
                hmac.hmac(
                        "AUTH-05-body",
                        "APPLE",
                        body.identityToken(),
                        body.authorizationCode(),
                        body.nonce()
                )
        );
    }

    public Optional<Stored> find(RequestKey key) {
        return jdbc.query(
                """
                select
                    body_hmac,
                    status,
                    encrypted_response,
                    response_key_version,
                    expires_at
                from api_idempotency_requests
                where scope_hash = ?
                  and operation = ?
                  and key_hash = ?
                """,
                (rs, row) -> new Stored(
                        rs.getString("body_hmac"),
                        rs.getString("status"),
                        rs.getString("encrypted_response"),
                        rs.getString("response_key_version"),
                        rs.getObject(
                                "expires_at",
                                LocalDateTime.class
                        ).toInstant(ZoneOffset.UTC)
                ),
                key.scopeHash(),
                key.operation(),
                key.keyHash()
        ).stream().findFirst();
    }

    public Result decode(
            RequestKey key,
            Stored stored,
            Instant now
    ) {
        if (!stored.expiresAt().isAfter(now)
                || !crypto.matches(
                stored.bodyHmac(),
                key.bodyHmac()
        )) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_CONFLICT
            );
        }

        if (!"SUCCEEDED".equals(stored.status())
                || stored.encrypted() == null) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }

        try {
            Result result = mapper.readValue(
                    crypto.decrypt(
                            stored.encrypted(),
                            stored.keyVersion(),
                            context(key)
                    ),
                    Result.class
            );

            if (result == null
                    || result.subject() == null
                    || result.response() == null) {
                throw new BusinessException(
                        ErrorCode.SERVICE_UNAVAILABLE
                );
            }

            return result;

        } catch (JacksonException ex) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }

    public void complete(
            RequestKey key,
            Result result,
            Instant now
    ) {
        String encrypted = crypto.encrypt(
                mapper.writeValueAsString(result),
                context(key)
        );

        jdbc.update(
                """
                insert into api_idempotency_requests(
                    user_id,
                    scope_hash,
                    operation,
                    key_hash,
                    body_hmac,
                    status,
                    response_http_status,
                    encrypted_response,
                    response_key_version,
                    created_at,
                    expires_at
                )
                values (
                    ?, ?, ?, ?, ?,
                    'SUCCEEDED', 200,
                    ?, ?, ?, ?
                )
                """,
                result.response().userId(),
                key.scopeHash(),
                key.operation(),
                key.keyHash(),
                key.bodyHmac(),
                encrypted,
                VerificationTokenCrypto.KEY_VERSION,
                utc(now),
                utc(now.plus(Duration.ofDays(1)))
        );
    }

    private String context(RequestKey key) {
        return key.scopeHash()
                + ":"
                + key.operation()
                + ":"
                + key.keyHash();
    }

    private static LocalDateTime utc(Instant time) {
        return LocalDateTime.ofInstant(
                time,
                ZoneOffset.UTC
        );
    }

    public record RequestKey(
            String provider,
            String scopeHash,
            String keyHash,
            String bodyHmac
    ) {
        public RequestKey {
            if (!"KAKAO".equals(provider)
                    && !"APPLE".equals(provider)) {
                throw new IllegalArgumentException(
                        "Unsupported social provider"
                );
            }
        }

        public String operation() {
            return "POST /v1/auth/social/"
                    + provider.toLowerCase(Locale.ROOT);
        }
    }

    public record Stored(
            String bodyHmac,
            String status,
            String encrypted,
            String keyVersion,
            Instant expiresAt
    ) {

        @Override
        public String toString() {
            return "Stored[response=REDACTED]";
        }
    }

    public record Result(
            String subject,
            SocialLoginResponse response
    ) {

        @Override
        public String toString() {
            return "Result[identityAndTokens=REDACTED]";
        }
    }
}