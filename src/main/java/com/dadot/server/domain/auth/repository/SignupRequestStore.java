package com.dadot.server.domain.auth.repository;

import com.dadot.server.domain.auth.dto.response.EmailSignupResponse;
import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class SignupRequestStore {

    public static final String OPERATION =
            "POST /v1/auth/signup";

    private final JdbcTemplate jdbc;
    private final VerificationCrypto hmac;
    private final VerificationTokenCrypto crypto;
    private final JsonMapper mapper;

    public Optional<Stored> find(String keyHash) {
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
                keyHash
        ).stream().findFirst();
    }

    public void complete(
            String keyHash,
            String bodyHmac,
            EmailSignupResponse response,
            Instant now
    ) {
        String encrypted = crypto.encrypt(
                mapper.writeValueAsString(response),
                context(keyHash)
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
                values (?, ?, ?, ?, ?, 'SUCCEEDED', 201, ?, ?, ?, ?)
                """,
                response.userId(),
                scope(),
                OPERATION,
                keyHash,
                bodyHmac,
                encrypted,
                VerificationTokenCrypto.KEY_VERSION,
                utc(now),
                utc(now.plus(Duration.ofDays(1)))
        );
    }

    public void fail(
            String keyHash,
            String bodyHmac,
            ErrorCode error,
            Instant now
    ) {
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
                values (?, ?, ?, ?, 'FAILED', ?, ?, ?, ?)
                """,
                scope(),
                OPERATION,
                keyHash,
                bodyHmac,
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
            ErrorCode error = ErrorCode.valueOf(
                    mapper.readValue(
                            stored.payload(),
                            Failure.class
                    ).errorCode()
            );

            if (error != ErrorCode.TERMS_NOT_ACCEPTED
                    && error != ErrorCode.TERMS_CHANGED
                    && error != ErrorCode.SIGNUP_TERMS_UNAVAILABLE
                    && error != ErrorCode.EMAIL_ALREADY_REGISTERED) {
                throw new IllegalArgumentException();
            }

            return error;

        } catch (
                JacksonException
                | IllegalArgumentException ex
        ) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }

    public EmailSignupResponse response(
            Stored stored,
            String keyHash
    ) {
        if (!"SUCCEEDED".equals(stored.status())
                || stored.encryptedResponse() == null) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }

        try {
            String decrypted = crypto.decrypt(
                    stored.encryptedResponse(),
                    stored.keyVersion(),
                    context(keyHash)
            );

            return mapper.readValue(
                    decrypted,
                    EmailSignupResponse.class
            );

        } catch (JacksonException ex) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }

    private String scope() {
        return hmac.hmac(
                "idempotency-scope",
                "AUTH-03"
        );
    }

    private String context(String keyHash) {
        return scope() + ":" + OPERATION + ":" + keyHash;
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(
                value,
                ZoneOffset.UTC
        );
    }

    private record Failure(String errorCode) {
    }

    public record Stored(
            long userId,
            String bodyHmac,
            String status,
            String payload,
            String encryptedResponse,
            String keyVersion,
            Instant expiresAt
    ) {
        @Override
        public String toString() {
            return "Stored[status=" + status + "]";
        }
    }
}