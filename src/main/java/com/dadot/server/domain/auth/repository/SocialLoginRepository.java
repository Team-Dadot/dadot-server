package com.dadot.server.domain.auth.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class SocialLoginRepository {

    private final JdbcTemplate jdbc;

    public Optional<Long> findUser(String subject) {
        return jdbc.query(
                """
                select user_id
                from auth_identities
                where provider = 'KAKAO'
                  and provider_subject = ?
                """,
                (rs, row) -> rs.getLong("user_id"),
                subject
        ).stream().findFirst();
    }

    public Optional<Account> lockAccount(long userId) {
        return jdbc.query(
                """
                select
                    u.status,
                    u.auth_version,
                    exists(
                        select 1
                        from user_onboarding o
                        where o.user_id = u.id
                          and o.completed_at is not null
                    ) as onboarded
                from users u
                where u.id = ?
                for update
                """,
                (rs, row) -> new Account(
                        "ACTIVE".equals(
                                rs.getString("status")
                        ),
                        rs.getInt("auth_version"),
                        rs.getBoolean("onboarded")
                ),
                userId
        ).stream().findFirst();
    }

    public boolean lockIdentity(
            long userId,
            String subject
    ) {
        return !jdbc.query(
                """
                select id
                from auth_identities
                where provider = 'KAKAO'
                  and provider_subject = ?
                  and user_id = ?
                for update
                """,
                (rs, row) -> rs.getLong("id"),
                subject,
                userId
        ).isEmpty();
    }

    public void createSession(
            String id,
            long userId,
            Instant now,
            Instant expiresAt
    ) {
        jdbc.update(
                """
                insert into auth_sessions(
                    id,
                    user_id,
                    created_at,
                    expires_at
                )
                values (?, ?, ?, ?)
                """,
                id,
                userId,
                utc(now),
                utc(expiresAt)
        );
    }

    public void createProof(
            String id,
            String subject,
            String tokenHash,
            Instant now,
            Instant expiresAt
    ) {
        jdbc.update(
                """
                insert into auth_proofs(
                    id,
                    purpose,
                    provider,
                    provider_subject,
                    token_hash,
                    issued_at,
                    expires_at
                )
                values (?, 'SIGN_UP', 'KAKAO', ?, ?, ?, ?)
                """,
                id,
                subject,
                tokenHash,
                utc(now),
                utc(expiresAt)
        );
    }

    public boolean lockUsableProof(
            String subject,
            String tokenHash,
            Instant expiresAt,
            Instant now
    ) {
        return !jdbc.query(
                """
                select id
                from auth_proofs
                where provider = 'KAKAO'
                  and purpose = 'SIGN_UP'
                  and provider_subject = ?
                  and token_hash = ?
                  and user_id is null
                  and verification_id is null
                  and consumed_at is null
                  and invalidated_at is null
                  and issued_at <= ?
                  and expires_at > ?
                  and expires_at = ?
                for update
                """,
                (rs, row) -> rs.getString("id"),
                subject,
                tokenHash,
                utc(now),
                utc(now),
                utc(expiresAt)
        ).isEmpty();
    }

    private static LocalDateTime utc(Instant time) {
        return LocalDateTime.ofInstant(
                time,
                ZoneOffset.UTC
        );
    }

    public record Account(
            boolean active,
            int authVersion,
            boolean onboarded
    ) {
    }
}