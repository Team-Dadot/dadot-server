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
public class LogoutRepository {

    private final JdbcTemplate jdbc;

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

    public Optional<Session> lockSession(
            long userId,
            String sessionId
    ) {
        return jdbc.query(
                """
                select created_at, expires_at, revoked_at
                from auth_sessions
                where id = ? and user_id = ?
                for update
                """,
                (rs, row) -> new Session(
                        rs.getObject(
                                "created_at",
                                LocalDateTime.class
                        ).toInstant(ZoneOffset.UTC),
                        rs.getObject(
                                "expires_at",
                                LocalDateTime.class
                        ).toInstant(ZoneOffset.UTC),
                        rs.getObject(
                                "revoked_at",
                                LocalDateTime.class
                        ) != null
                ),
                sessionId,
                userId
        ).stream().findFirst();
    }

    public int revoke(
            long userId,
            String sessionId,
            Instant now
    ) {
        var timestamp = LocalDateTime.ofInstant(
                now,
                ZoneOffset.UTC
        );

        return jdbc.update(
                """
                update auth_sessions
                set revoked_at = ?
                where id = ? and user_id = ?
                  and revoked_at is null
                  and created_at <= ?
                  and expires_at > ?
                """,
                timestamp,
                sessionId,
                userId,
                timestamp,
                timestamp
        );
    }

    public void detachDevices(
            long userId,
            String sessionId
    ) {
        jdbc.update(
                """
                delete from user_devices
                where user_id = ? and session_id = ?
                """,
                userId,
                sessionId
        );
    }

    public record Account(
            boolean active,
            int authVersion
    ) {
    }

    public record Session(
            Instant createdAt,
            Instant expiresAt,
            boolean revoked
    ) {
        public boolean usable(Instant now) {
            return !revoked
                    && !createdAt.isAfter(now)
                    && expiresAt.isAfter(now);
        }
    }
}