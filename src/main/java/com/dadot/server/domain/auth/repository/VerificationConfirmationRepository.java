package com.dadot.server.domain.auth.repository;

import com.dadot.server.domain.auth.entity.VerificationPurpose;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class VerificationConfirmationRepository {

    private final JdbcTemplate jdbc;

    // 재발송과 인증 확인이 같은 인증 행을 동시에 변경하지 못하게 한다.
    public Optional<Verification> lock(String id) {
        return jdbc.query(
                """
                select
                    id,
                    user_id,
                    email,
                    purpose,
                    code_digest,
                    failed_attempts,
                    expires_at,
                    verified_at,
                    invalidated_at
                from email_verifications
                where id = ?
                for update
                """,
                (rs, row) -> new Verification(
                        rs.getString("id"),
                        rs.getObject("user_id", Long.class),
                        rs.getString("email"),
                        VerificationPurpose.valueOf(
                                rs.getString("purpose")
                        ),
                        rs.getString("code_digest"),
                        rs.getInt("failed_attempts"),
                        instant(rs, "expires_at"),
                        instant(rs, "verified_at"),
                        instant(rs, "invalidated_at")
                ),
                id
        ).stream().findFirst();
    }

    public void recordMismatch(
            Verification source,
            Instant now
    ) {
        int attempts = source.failedAttempts() + 1;

        jdbc.update(
                """
                update email_verifications
                set failed_attempts = ?,
                    invalidated_at = ?
                where id = ?
                """,
                attempts,
                attempts >= 5 ? utc(now) : null,
                source.id()
        );
    }

    public void createProof(
            Verification source,
            String tokenHash,
            Instant now
    ) {
        jdbc.update(
                """
                insert into auth_proofs (
                    id,
                    user_id,
                    verification_id,
                    purpose,
                    provider,
                    subject_email,
                    token_hash,
                    issued_at,
                    expires_at
                )
                values (?, ?, ?, ?, 'EMAIL', ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                source.userId(),
                source.id(),
                source.purpose().name(),
                source.email(),
                tokenHash,
                utc(now),
                utc(now.plusSeconds(600))
        );

        jdbc.update(
                """
                update email_verifications
                set verified_at = ?
                where id = ?
                """,
                utc(now),
                source.id()
        );
    }

    public Optional<Proof> proof(String verificationId) {
        return jdbc.query(
                """
                select
                    token_hash,
                    expires_at,
                    consumed_at,
                    invalidated_at
                from auth_proofs
                where verification_id = ?
                for update
                """,
                (rs, row) -> new Proof(
                        rs.getString("token_hash"),
                        instant(rs, "expires_at"),
                        instant(rs, "consumed_at"),
                        instant(rs, "invalidated_at")
                ),
                verificationId
        ).stream().findFirst();
    }

    private static Instant instant(
            ResultSet rs,
            String column
    ) throws SQLException {

        LocalDateTime value = rs.getObject(
                column,
                LocalDateTime.class
        );

        return value == null
                ? null
                : value.toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(
                value,
                ZoneOffset.UTC
        );
    }

    public record Verification(
            String id,
            Long userId,
            String email,
            VerificationPurpose purpose,
            String codeDigest,
            int failedAttempts,
            Instant expiresAt,
            Instant verifiedAt,
            Instant invalidatedAt
    ) {
        @Override
        public String toString() {
            return "Verification[id=" + id + "]";
        }
    }

    public record Proof(
            String tokenHash,
            Instant expiresAt,
            Instant consumedAt,
            Instant invalidatedAt
    ) {
        @Override
        public String toString() {
            return "Proof[REDACTED]";
        }
    }
}