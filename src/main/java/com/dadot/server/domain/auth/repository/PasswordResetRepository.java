package com.dadot.server.domain.auth.repository;

import com.dadot.server.domain.auth.dto.request.PasswordResetRequest;
import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PasswordResetRepository {

    public static final String OPERATION =
            "POST /v1/auth/password/reset";

    private final JdbcTemplate jdbc;
    private final VerificationCrypto hmac;

    public RequestKey key(
            PasswordResetRequest request,
            String idempotencyKey
    ) {
        return new RequestKey(
                hmac.hmac(
                        "AUTH-08-key",
                        idempotencyKey.toLowerCase(Locale.ROOT)
                ),
                hmac.hmac(
                        "AUTH-08-body",
                        request.verificationToken(),
                        request.newPassword()
                )
        );
    }

    public Optional<Stored> findRequest(RequestKey key) {
        return jdbc.query(
                """
                select
                    user_id,
                    body_hmac,
                    status,
                    response_http_status,
                    expires_at
                from api_idempotency_requests
                where scope_hash = ?
                  and operation = ?
                  and key_hash = ?
                """,
                (rs, row) -> new Stored(
                        rs.getObject("user_id", Long.class),
                        rs.getString("body_hmac"),
                        rs.getString("status"),
                        rs.getObject(
                                "response_http_status",
                                Integer.class
                        ),
                        instant(rs, "expires_at")
                ),
                scope(),
                OPERATION,
                key.keyHash()
        ).stream().findFirst();
    }

    // 잘못된 증빙으로 비밀번호 해시를 계산하지 않도록 사전 확인한다.
    public Optional<Proof> findUsableProof(
            String tokenHash,
            Instant now
    ) {
        return jdbc.query(
                """
                select
                    p.id,
                    p.user_id,
                    p.verification_id,
                    p.subject_email,
                    p.issued_at,
                    p.expires_at,
                    p.consumed_at,
                    p.invalidated_at
                from auth_proofs p
                join email_verifications v
                  on v.id = p.verification_id
                 and v.user_id = p.user_id
                 and v.email = p.subject_email
                 and v.purpose = p.purpose
                join users u
                  on u.id = p.user_id
                join auth_identities i
                  on i.user_id = p.user_id
                 and i.provider = 'EMAIL'
                 and i.provider_subject = p.subject_email
                 and i.email = p.subject_email
                where p.token_hash = ?
                  and p.provider = 'EMAIL'
                  and p.purpose = 'PASSWORD_RESET'
                  and p.user_id is not null
                  and p.consumed_at is null
                  and p.invalidated_at is null
                  and p.issued_at <= ?
                  and p.expires_at > ?
                  and v.verified_at is not null
                  and v.verified_at <= ?
                  and v.invalidated_at is null
                  and u.status = 'ACTIVE'
                """,
                (rs, row) -> proof(rs),
                tokenHash,
                utc(now),
                utc(now),
                utc(now)
        ).stream().findFirst();
    }

    // 인증 확인의 인증 행 → 증빙 잠금 순서를 따른다.
    public void lockVerifications(Proof source) {
        jdbc.query(
                """
                select id
                from email_verifications
                where user_id = ?
                  and email = ?
                  and purpose in (
                      'PASSWORD_RESET',
                      'PASSWORD_CHANGE'
                  )
                  and invalidated_at is null
                order by id
                for update
                """,
                (rs, row) -> rs.getString("id"),
                source.userId(),
                source.email()
        );
    }

    public Optional<Account> lockAccount(long userId) {
        return jdbc.query(
                """
                select status, auth_version
                from users
                where id = ?
                for update
                """,
                (rs, row) -> new Account(
                        "ACTIVE".equals(rs.getString("status")),
                        rs.getInt("auth_version")
                ),
                userId
        ).stream().findFirst();
    }

    public boolean lockIdentity(Proof source) {
        return !jdbc.query(
                """
                select id
                from auth_identities
                where user_id = ?
                  and provider = 'EMAIL'
                  and provider_subject = ?
                  and email = ?
                for update
                """,
                (rs, row) -> rs.getLong("id"),
                source.userId(),
                source.email(),
                source.email()
        ).isEmpty();
    }

    public Optional<Proof> lockProof(
            Proof source,
            String tokenHash,
            Instant now
    ) {
        return jdbc.query(
                """
                select
                    p.id,
                    p.user_id,
                    p.verification_id,
                    p.subject_email,
                    p.issued_at,
                    p.expires_at,
                    p.consumed_at,
                    p.invalidated_at
                from auth_proofs p
                join email_verifications v
                  on v.id = p.verification_id
                 and v.user_id = p.user_id
                 and v.email = p.subject_email
                 and v.purpose = p.purpose
                where p.id = ?
                  and p.token_hash = ?
                  and p.user_id = ?
                  and p.verification_id = ?
                  and p.subject_email = ?
                  and p.provider = 'EMAIL'
                  and p.purpose = 'PASSWORD_RESET'
                  and v.verified_at is not null
                  and v.verified_at <= ?
                  and v.invalidated_at is null
                for update
                """,
                (rs, row) -> proof(rs),
                source.id(),
                tokenHash,
                source.userId(),
                source.verificationId(),
                source.email(),
                utc(now)
        ).stream().findFirst();
    }

    public void changePassword(
            Proof proof,
            String passwordHash,
            Instant now
    ) {
        int changed = jdbc.update(
                """
                update auth_identities
                set password_hash = ?,
                    updated_at = ?
                where user_id = ?
                  and provider = 'EMAIL'
                  and provider_subject = ?
                  and email = ?
                """,
                passwordHash,
                utc(now),
                proof.userId(),
                proof.email(),
                proof.email()
        );

        if (changed != 1) {
            throw invalid();
        }
    }

    public void consume(
            Proof proof,
            Instant now
    ) {
        int consumed = jdbc.update(
                """
                update auth_proofs
                set consumed_at = ?
                where id = ?
                  and consumed_at is null
                  and invalidated_at is null
                  and issued_at <= ?
                  and expires_at > ?
                """,
                utc(now),
                proof.id(),
                utc(now),
                utc(now)
        );

        if (consumed != 1) {
            throw invalid();
        }
    }

    public void invalidateOtherProofs(
            Proof proof,
            Instant now
    ) {
        jdbc.update(
                """
                update auth_proofs
                set invalidated_at = ?
                where user_id = ?
                  and provider = 'EMAIL'
                  and subject_email = ?
                  and purpose in (
                      'PASSWORD_RESET',
                      'PASSWORD_CHANGE'
                  )
                  and id <> ?
                  and consumed_at is null
                  and invalidated_at is null
                  and issued_at <= ?
                """,
                utc(now),
                proof.userId(),
                proof.email(),
                proof.id(),
                utc(now)
        );

        jdbc.update(
                """
                update email_verifications
                set invalidated_at = ?
                where user_id = ?
                  and email = ?
                  and purpose in (
                      'PASSWORD_RESET',
                      'PASSWORD_CHANGE'
                  )
                  and invalidated_at is null
                """,
                utc(now),
                proof.userId(),
                proof.email()
        );
    }

    public void revokeSessions(
            long userId,
            Instant now
    ) {
        int changed = jdbc.update(
                """
                update users
                set auth_version = auth_version + 1,
                    updated_at = ?
                where id = ?
                  and status = 'ACTIVE'
                  and auth_version < 2147483647
                """,
                utc(now),
                userId
        );

        if (changed != 1) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }

        jdbc.update(
                """
                update auth_sessions
                set revoked_at = ?
                where user_id = ?
                  and revoked_at is null
                """,
                utc(now),
                userId
        );

        jdbc.update(
                "delete from user_devices where user_id = ?",
                userId
        );
    }

    public void complete(
            RequestKey key,
            long userId,
            Instant now
    ) {
        // 204 응답이므로 비밀번호·증빙·응답 본문은 저장하지 않는다.
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
                    created_at,
                    expires_at
                )
                values (
                    ?, ?, ?, ?, ?,
                    'SUCCEEDED', 204,
                    ?, ?
                )
                """,
                userId,
                scope(),
                OPERATION,
                key.keyHash(),
                key.bodyHmac(),
                utc(now),
                utc(now.plus(Duration.ofDays(1)))
        );
    }

    private String scope() {
        return hmac.hmac(
                "idempotency-scope",
                "AUTH-08"
        );
    }

    private Proof proof(ResultSet rs) throws SQLException {
        return new Proof(
                rs.getString("id"),
                rs.getLong("user_id"),
                rs.getString("verification_id"),
                rs.getString("subject_email"),
                instant(rs, "issued_at"),
                instant(rs, "expires_at"),
                instant(rs, "consumed_at"),
                instant(rs, "invalidated_at")
        );
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

    private static LocalDateTime utc(Instant time) {
        return LocalDateTime.ofInstant(
                time,
                ZoneOffset.UTC
        );
    }

    private BusinessException invalid() {
        return new BusinessException(
                ErrorCode.INVALID_VERIFICATION_TOKEN
        );
    }

    public record RequestKey(
            String keyHash,
            String bodyHmac
    ) {
    }

    public record Stored(
            Long userId,
            String bodyHmac,
            String status,
            Integer httpStatus,
            Instant expiresAt
    ) {
    }

    public record Account(
            boolean active,
            int authVersion
    ) {
    }

    public record Proof(
            String id,
            long userId,
            String verificationId,
            String email,
            Instant issuedAt,
            Instant expiresAt,
            Instant consumedAt,
            Instant invalidatedAt
    ) {
        public boolean usable(Instant now) {
            return consumedAt == null
                    && invalidatedAt == null
                    && !issuedAt.isAfter(now)
                    && expiresAt.isAfter(now);
        }

        @Override
        public String toString() {
            return "Proof[identity=REDACTED]";
        }
    }
}