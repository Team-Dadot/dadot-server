package com.dadot.server.domain.auth.repository;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.time.*;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class EmailSignupRepository {

    private final JdbcTemplate jdbc;

    public Optional<Proof> lockProof(
            String tokenHash,
            String email
    ) {
        var ids = jdbc.query(
                """
                select verification_id
                from auth_proofs
                where token_hash = ?
                  and provider = 'EMAIL'
                  and purpose = 'SIGN_UP'
                  and user_id is null
                  and subject_email = ?
                """,
                (rs, row) -> rs.getString(1),
                tokenHash,
                email
        );

        if (ids.isEmpty()) {
            return Optional.empty();
        }

        // AUTH-02와 동일하게 인증번호 → 증빙 순서로 잠근다.
        var sources = jdbc.query(
                """
                select verified_at, invalidated_at
                from email_verifications
                where id = ?
                  and purpose = 'SIGN_UP'
                  and email = ?
                for update
                """,
                (rs, row) ->
                        rs.getObject(
                                "verified_at",
                                LocalDateTime.class
                        ) != null
                                && rs.getObject(
                                "invalidated_at",
                                LocalDateTime.class
                        ) == null,
                ids.getFirst(),
                email
        );

        if (sources.isEmpty() || !sources.getFirst()) {
            return Optional.empty();
        }

        return jdbc.query(
                """
                select
                    id,
                    expires_at,
                    consumed_at,
                    invalidated_at
                from auth_proofs
                where token_hash = ?
                  and verification_id = ?
                for update
                """,
                (rs, row) -> new Proof(
                        rs.getString("id"),
                        rs.getObject(
                                "expires_at",
                                LocalDateTime.class
                        ).toInstant(ZoneOffset.UTC),
                        rs.getObject(
                                "consumed_at",
                                LocalDateTime.class
                        ) != null,
                        rs.getObject(
                                "invalidated_at",
                                LocalDateTime.class
                        ) != null
                ),
                tokenHash,
                ids.getFirst()
        ).stream().findFirst();
    }

    public long createUser(
            String email,
            String passwordHash,
            long termsId,
            Instant now
    ) {
        var key = new GeneratedKeyHolder();

        String nickname = "다닷"
                + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12);

        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    """
                    insert into users(
                        nickname,
                        created_at,
                        updated_at
                    )
                    values (?, ?, ?)
                    """,
                    new String[]{"id"}
            );

            statement.setString(1, nickname);
            statement.setObject(2, utc(now));
            statement.setObject(3, utc(now));

            return statement;
        }, key);

        long userId = Objects.requireNonNull(
                key.getKey()
        ).longValue();

        try {
            jdbc.update(
                    """
                    insert into auth_identities(
                        user_id,
                        provider,
                        provider_subject,
                        email,
                        password_hash,
                        created_at,
                        updated_at
                    )
                    values (?, 'EMAIL', ?, ?, ?, ?, ?)
                    """,
                    userId,
                    email,
                    email,
                    passwordHash,
                    utc(now),
                    utc(now)
            );
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(
                    ErrorCode.EMAIL_ALREADY_REGISTERED
            );
        }

        jdbc.update(
                """
                insert into user_agreements(
                    user_id,
                    terms_id,
                    agreed_at
                )
                values (?, ?, ?)
                """,
                userId,
                termsId,
                utc(now)
        );

        jdbc.update(
                """
                insert into user_onboarding(
                    user_id,
                    updated_at
                )
                values (?, ?)
                """,
                userId,
                utc(now)
        );

        return userId;
    }

    public void createSession(
            String sessionId,
            long userId,
            Instant issuedAt,
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
                sessionId,
                userId,
                utc(issuedAt),
                utc(expiresAt)
        );
    }

    public void consume(
            String proofId,
            Instant now
    ) {
        int updated = jdbc.update(
                """
                update auth_proofs
                set consumed_at = ?
                where id = ?
                  and consumed_at is null
                  and invalidated_at is null
                  and expires_at > ?
                """,
                utc(now),
                proofId,
                utc(now)
        );

        if (updated != 1) {
            throw new BusinessException(
                    ErrorCode.INVALID_VERIFICATION_TOKEN
            );
        }
    }

    public boolean sameIdentity(
            long userId,
            String email
    ) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                        select exists(
                            select 1
                            from auth_identities
                            where user_id = ?
                              and provider = 'EMAIL'
                              and provider_subject = ?
                        )
                        """,
                        Boolean.class,
                        userId,
                        email
                )
        );
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(
                value,
                ZoneOffset.UTC
        );
    }

    public record Proof(
            String id,
            Instant expiresAt,
            boolean consumed,
            boolean invalidated
    ) {
        public boolean usable(Instant now) {
            return !consumed
                    && !invalidated
                    && expiresAt.isAfter(now);
        }
    }
}