package com.dadot.server.domain.auth.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class EmailIdentityLookup {

    private final JdbcTemplate jdbc;

    public Optional<Identity> find(String email) {
        return jdbc.query(
                """
                select i.user_id, u.status
                from auth_identities i
                join users u on u.id = i.user_id
                where i.provider = 'EMAIL'
                  and i.provider_subject = ?
                """,
                (rs, row) -> new Identity(
                        rs.getLong("user_id"),
                        "ACTIVE".equals(rs.getString("status"))
                ),
                email
        ).stream().findFirst();
    }

    public record Identity(
            long userId,
            boolean active
    ) {
    }
}