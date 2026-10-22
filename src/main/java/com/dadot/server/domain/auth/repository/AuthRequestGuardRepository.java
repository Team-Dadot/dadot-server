package com.dadot.server.domain.auth.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

@Repository
@RequiredArgsConstructor
public class AuthRequestGuardRepository {

    private final JdbcTemplate jdbc;

    // 반드시 트랜잭션 안에서 호출한다.
    public int lock(String hash, Instant expiresAt) {
        jdbc.update(
                """
                insert into auth_request_guards(
                    guard_hash,
                    request_count,
                    expires_at
                )
                values (?, 0, ?)
                on duplicate key update
                    expires_at = greatest(
                        expires_at,
                        values(expires_at)
                    )
                """,
                hash,
                LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC)
        );

        Integer count = jdbc.queryForObject(
                """
                select request_count
                from auth_request_guards
                where guard_hash = ?
                for update
                """,
                Integer.class,
                hash
        );

        return Objects.requireNonNull(count);
    }

    public void increment(String hash) {
        jdbc.update(
                """
                update auth_request_guards
                set request_count = request_count + 1
                where guard_hash = ?
                """,
                hash
        );
    }
}