package com.dadot.server.domain.auth.repository;

import com.dadot.server.domain.auth.dto.response.VerifyEmailVerificationResponse;
import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class VerificationConfirmationRequestStore {

    private final JdbcTemplate jdbc;
    private final VerificationCrypto hmac;
    private final VerificationTokenCrypto tokens;
    private final JsonMapper mapper;

    public Optional<Stored> find(
            String operation,
            String keyHash
    ) {
        return jdbc.query(
                """
                select
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
                operation,
                keyHash
        ).stream().findFirst();
    }

    public void saveSuccess(
            String operation,
            String keyHash,
            String bodyHmac,
            Long userId,
            VerifyEmailVerificationResponse response,
            Instant now
    ) {
        String encrypted = tokens.encrypt(
                mapper.writeValueAsString(response),
                context(operation, keyHash)
        );

        save(
                operation,
                keyHash,
                bodyHmac,
                userId,
                "SUCCEEDED",
                200,
                null,
                encrypted,
                VerificationTokenCrypto.KEY_VERSION,
                now
        );
    }

    public void saveMismatch(
            String operation,
            String keyHash,
            String bodyHmac,
            Long userId,
            ErrorCode error,
            Instant now
    ) {
        save(
                operation,
                keyHash,
                bodyHmac,
                userId,
                "FAILED",
                error.getStatus().value(),
                mapper.writeValueAsString(
                        new Failure(error.name())
                ),
                null,
                null,
                now
        );
    }

    public VerifyEmailVerificationResponse response(
            Stored stored,
            String operation,
            String keyHash
    ) {
        if (stored.encryptedResponse() == null) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }

        try {
            String decrypted = tokens.decrypt(
                    stored.encryptedResponse(),
                    stored.keyVersion(),
                    context(operation, keyHash)
            );

            return mapper.readValue(
                    decrypted,
                    VerifyEmailVerificationResponse.class
            );

        } catch (JacksonException ex) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }

    public ErrorCode failure(Stored stored) {
        try {
            ErrorCode code = ErrorCode.valueOf(
                    mapper.readValue(
                            stored.payload(),
                            Failure.class
                    ).errorCode()
            );

            if (code != ErrorCode.INVALID_VERIFICATION_CODE
                    && code != ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED) {
                throw new IllegalArgumentException();
            }

            return code;

        } catch (
                JacksonException
                | IllegalArgumentException ex
        ) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }

    private void save(
            String operation,
            String keyHash,
            String bodyHmac,
            Long userId,
            String status,
            int httpStatus,
            String payload,
            String encrypted,
            String version,
            Instant now
    ) {
        jdbc.update(
                """
                insert into api_idempotency_requests (
                    user_id,
                    scope_hash,
                    operation,
                    key_hash,
                    body_hmac,
                    status,
                    response_http_status,
                    response_payload,
                    encrypted_response,
                    response_key_version,
                    created_at,
                    expires_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                userId,
                scope(),
                operation,
                keyHash,
                bodyHmac,
                status,
                httpStatus,
                payload,
                encrypted,
                version,
                utc(now),
                utc(now.plusSeconds(86400))
        );
    }

    private String scope() {
        return hmac.hmac(
                "idempotency-scope",
                "AUTH-02"
        );
    }

    private String context(
            String operation,
            String keyHash
    ) {
        return scope() + ":" + operation + ":" + keyHash;
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