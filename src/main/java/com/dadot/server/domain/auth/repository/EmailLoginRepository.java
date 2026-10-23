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
public class EmailLoginRepository {

    private final JdbcTemplate jdbc;

    public Optional<Credential> findCredential(String email) {
        return jdbc.query(
                """
                select user_id, password_hash
                from auth_identities
                where provider = 'EMAIL'
                  and provider_subject = ?
                """,
                (rs, row) -> new Credential(
                        rs.getLong("user_id"),
                        rs.getString("password_hash")
                ),
                email
        ).stream().findFirst();
    }

    // 세션 생성 트랜잭션에서 회원 → 로그인 수단 순서로 잠근다.
    public Optional<Account> lockAccount(long userId) {
        return jdbc.query(
                """
                select u.status, u.auth_version,
                       exists(
                           select 1 from user_onboarding o
                           where o.user_id = u.id
                             and o.completed_at is not null
                       ) as onboarding_completed
                from users u
                where u.id = ?
                for update
                """,
                (rs, row) -> new Account(
                        "ACTIVE".equals(rs.getString("status")),
                        rs.getInt("auth_version"),
                        rs.getBoolean("onboarding_completed")
                ),
                userId
        ).stream().findFirst();
    }

    public Optional<String> lockPassword(long userId, String email) {
        return jdbc.query(
                """
                select password_hash
                from auth_identities
                where user_id = ?
                  and provider = 'EMAIL'
                  and provider_subject = ?
                for update
                """,
                (rs, row) -> rs.getString("password_hash"),
                userId,
                email
        ).stream().findFirst();
    }

    public void createSession(
            String sessionId,
            long userId,
            Instant issuedAt,
            Instant expiresAt
    ) {
        jdbc.update(
                """
                insert into auth_sessions(id, user_id, created_at, expires_at)
                values (?, ?, ?, ?)
                """,
                sessionId,
                userId,
                LocalDateTime.ofInstant(issuedAt, ZoneOffset.UTC),
                LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)
        );
    }

    public record Credential(long userId, String passwordHash) {
        @Override
        public String toString() {
            return "Credential[userId=" + userId + ", passwordHash=REDACTED]";
        }
    }

    public record Account(
            boolean active,
            int authVersion,
            boolean onboardingCompleted
    ) {
    }
}