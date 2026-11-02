package com.dadot.server.domain.auth.repository;

import com.dadot.server.domain.auth.dto.response.IssueEmailVerificationResponse;
import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class VerificationRequestStore {

    private static final String OPERATION =
            "POST /v1/auth/email-verifications";

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final VerificationCrypto crypto;

    public Optional<IssueEmailVerificationResponse> replay(
            String keyHash,
            String bodyHmac,
            Instant now
    ) {
        var rows = jdbc.query(
                """
                select
                    body_hmac,
                    status,
                    response_payload,
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
                        rs.getObject(
                                "expires_at",
                                LocalDateTime.class
                        ).toInstant(ZoneOffset.UTC)
                ),
                scope(),
                OPERATION,
                keyHash
        );

        if (rows.isEmpty()) {
            return Optional.empty();
        }

        Stored stored = rows.getFirst();

        if (!stored.bodyHmac().equals(bodyHmac)
                || !stored.expiresAt().isAfter(now)) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_CONFLICT
            );
        }

        if (!"SUCCEEDED".equals(stored.status())
                || stored.payload() == null) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }

        IssueEmailVerificationResponse response = mapper.readValue(
                stored.payload(),
                IssueEmailVerificationResponse.class
        );

        Integer exists = jdbc.queryForObject(
                "select count(*) from email_verifications where id = ?",
                Integer.class,
                response.verificationId()
        );

        if (exists == null || exists != 1) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_CONFLICT
            );
        }

        return Optional.of(response);
    }

    public void complete(
            String keyHash,
            String bodyHmac,
            Long userId,
            IssueEmailVerificationResponse response,
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
                    created_at,
                    expires_at
                )
                values (?, ?, ?, ?, ?, 'SUCCEEDED', 201, ?, ?, ?)
                """,
                userId,
                scope(),
                OPERATION,
                keyHash,
                bodyHmac,
                mapper.writeValueAsString(response),
                LocalDateTime.ofInstant(now, ZoneOffset.UTC),
                LocalDateTime.ofInstant(
                        now.plus(Duration.ofHours(24)),
                        ZoneOffset.UTC
                )
        );
    }

    private String scope() {
        return crypto.hmac("idempotency-scope", "AUTH-01");
    }

    private record Stored(
            String bodyHmac,
            String status,
            String payload,
            Instant expiresAt
    ) {
    }
}