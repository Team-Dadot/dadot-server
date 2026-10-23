package com.dadot.server.domain.auth.repository;

import com.dadot.server.domain.auth.dto.request.SocialSignupRequest;
import com.dadot.server.domain.auth.dto.response.SocialSignupResponse;
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
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class SocialSignupRequestStore {

    public static final String OPERATION =
            "POST /v1/auth/social/signup";

    private static final Set<ErrorCode> FAILURES = Set.of(
            ErrorCode.TERMS_NOT_ACCEPTED,
            ErrorCode.TERMS_CHANGED,
            ErrorCode.SIGNUP_TERMS_UNAVAILABLE,
            ErrorCode.SOCIAL_ACCOUNT_ALREADY_REGISTERED
    );

    private final JdbcTemplate jdbc;
    private final VerificationCrypto hmac;
    private final VerificationTokenCrypto crypto;
    private final JsonMapper mapper;

    public RequestKey key(
            SocialSignupRequest request,
            String idempotencyKey
    ) {
        return new RequestKey(
                hmac.hmac(
                        "AUTH-11-key",
                        idempotencyKey.toLowerCase(Locale.ROOT)
                ),
                hmac.hmac(
                        "AUTH-11-body",
                        request.registrationToken(),
                        request.acceptedTermsIds()
                                .stream()
                                .sorted()
                                .toList()
                                .toString()
                )
        );
    }

    public Optional<Stored> find(RequestKey key) {
        return jdbc.query(
                """
                select
                    user_id,
                    body_hmac,
                    status,
                    response_payload,
                    encrypted_response,
                    response_key_version,
                    expires_at
                from api_idempotency_requests
                where scope_hash = ?
                  and operation = ?
                  and key_hash = ?
                """,
                (rs, row) -> new Stored(
                        rs.getLong("user_id"),
                        rs.getString("body_hmac"),
                        rs.getString("status"),
                        rs.getString("response_payload"),
                        rs.getString("encrypted_response"),
                        rs.getString("response_key_version"),
                        rs.getObject(
                                "expires_at",
                                LocalDateTime.class
                        ).toInstant(ZoneOffset.UTC)
                ),
                scope(),
                OPERATION,
                key.keyHash()
        ).stream().findFirst();
    }

    public void validate(
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
    }

    public void complete(
            RequestKey key,
            Success result,
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
                    'SUCCEEDED', 201,
                    ?, ?, ?, ?
                )
                """,
                result.response().userId(),
                scope(),
                OPERATION,
                key.keyHash(),
                key.bodyHmac(),
                encrypted,
                VerificationTokenCrypto.KEY_VERSION,
                utc(now),
                utc(now.plus(Duration.ofDays(1)))
        );
    }

    public void fail(
            RequestKey key,
            ErrorCode error,
            Instant now
    ) {
        if (!FAILURES.contains(error)) {
            throw new IllegalArgumentException(
                    "Unsupported signup failure"
            );
        }

        jdbc.update(
                """
                insert into api_idempotency_requests(
                    scope_hash,
                    operation,
                    key_hash,
                    body_hmac,
                    status,
                    response_http_status,
                    response_payload,
                    created_at,
                    expires_at
                )
                values (
                    ?, ?, ?, ?,
                    'FAILED',
                    ?, ?, ?, ?
                )
                """,
                scope(),
                OPERATION,
                key.keyHash(),
                key.bodyHmac(),
                error.getStatus().value(),
                mapper.writeValueAsString(
                        new Failure(error.name())
                ),
                utc(now),
                utc(now.plus(Duration.ofDays(1)))
        );
    }

    public ErrorCode failure(Stored stored) {
        try {
            if (stored.payload() == null) {
                throw unavailable();
            }

            Failure failure = mapper.readValue(
                    stored.payload(),
                    Failure.class
            );

            if (failure == null
                    || failure.errorCode() == null) {
                throw unavailable();
            }

            ErrorCode error = ErrorCode.valueOf(
                    failure.errorCode()
            );

            if (!FAILURES.contains(error)) {
                throw unavailable();
            }

            return error;

        } catch (
                JacksonException
                | IllegalArgumentException ex
        ) {
            throw unavailable();
        }
    }

    public Success success(
            RequestKey key,
            Stored stored
    ) {
        if (!"SUCCEEDED".equals(stored.status())
                || stored.encrypted() == null) {
            throw unavailable();
        }

        try {
            Success value = mapper.readValue(
                    crypto.decrypt(
                            stored.encrypted(),
                            stored.keyVersion(),
                            context(key)
                    ),
                    Success.class
            );

            if (value == null
                    || value.response() == null
                    || value.subject() == null
                    || value.subject().isBlank()
                    || value.subject().length() > 255
                    || !(
                    "KAKAO".equals(value.provider())
                            || "APPLE".equals(value.provider())
            )) {
                throw unavailable();
            }

            return value;

        } catch (JacksonException ex) {
            throw unavailable();
        }
    }

    private String scope() {
        return hmac.hmac(
                "idempotency-scope",
                "AUTH-11"
        );
    }

    private String context(RequestKey key) {
        return scope()
                + ":"
                + OPERATION
                + ":"
                + key.keyHash();
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(
                value,
                ZoneOffset.UTC
        );
    }

    private static BusinessException unavailable() {
        return new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE
        );
    }

    public record RequestKey(
            String keyHash,
            String bodyHmac
    ) {
    }

    private record Failure(
            String errorCode
    ) {
    }

    public record Success(
            String provider,
            String subject,
            SocialSignupResponse response
    ) {

        @Override
        public String toString() {
            return "Success[identityAndTokens=REDACTED]";
        }
    }

    public record Stored(
            long userId,
            String bodyHmac,
            String status,
            String payload,
            String encrypted,
            String keyVersion,
            Instant expiresAt
    ) {

        @Override
        public String toString() {
            return "Stored[status=" + status + "]";
        }
    }
}