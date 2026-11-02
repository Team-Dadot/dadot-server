package com.dadot.server.domain.auth.repository;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class SocialSignupRepository {

    private final JdbcTemplate jdbc;

    // subject 공유 잠금에 사용할 정보를 먼저 조회한다.
    // 증빙 사용 가능 여부는 행을 잠근 뒤 다시 검증한다.
    public Optional<Source> findSource(String tokenHash) {
        return jdbc.query(
                """
                select
                    id,
                    provider,
                    provider_subject
                from auth_proofs
                where token_hash = ?
                  and provider in ('KAKAO', 'APPLE')
                  and purpose = 'SIGN_UP'
                  and user_id is null
                  and verification_id is null
                """,
                (rs, row) -> new Source(
                        rs.getString("id"),
                        rs.getString("provider"),
                        rs.getString("provider_subject")
                ),
                tokenHash
        ).stream().findFirst();
    }

    public Optional<Proof> lockProof(
            Source source,
            String tokenHash
    ) {
        return jdbc.query(
                """
                select
                    subject_email,
                    issued_at,
                    expires_at,
                    consumed_at,
                    invalidated_at
                from auth_proofs
                where id = ?
                  and token_hash = ?
                  and provider = ?
                  and provider_subject = ?
                  and purpose = 'SIGN_UP'
                  and user_id is null
                  and verification_id is null
                for update
                """,
                (rs, row) -> new Proof(
                        source,
                        rs.getString("subject_email"),
                        rs.getObject(
                                "issued_at",
                                LocalDateTime.class
                        ).toInstant(ZoneOffset.UTC),
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
                source.id(),
                tokenHash,
                source.provider(),
                source.subject()
        ).stream().findFirst();
    }

    public boolean registered(Source source) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        """
                        select exists(
                            select 1
                            from auth_identities
                            where provider = ?
                              and provider_subject = ?
                        )
                        """,
                        Boolean.class,
                        source.provider(),
                        source.subject()
                )
        );
    }

    public long createUser(
            Proof proof,
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
                        created_at,
                        updated_at
                    )
                    values (?, ?, ?, ?, ?, ?)
                    """,
                    userId,
                    proof.source().provider(),
                    proof.source().subject(),
                    proof.email(),
                    utc(now),
                    utc(now)
            );

        } catch (DuplicateKeyException ex) {
            // 밖으로 전파해 방금 생성한 users 행도 함께 롤백한다.
            throw new BusinessException(
                    ErrorCode.SOCIAL_ACCOUNT_ALREADY_REGISTERED
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

    public void consumeAndInvalidateOthers(
            Source source,
            Instant now
    ) {
        int updated = jdbc.update(
                """
                update auth_proofs
                set consumed_at = ?
                where id = ?
                  and provider = ?
                  and provider_subject = ?
                  and purpose = 'SIGN_UP'
                  and user_id is null
                  and verification_id is null
                  and consumed_at is null
                  and invalidated_at is null
                  and issued_at <= ?
                  and expires_at > ?
                """,
                utc(now),
                source.id(),
                source.provider(),
                source.subject(),
                utc(now),
                utc(now)
        );

        if (updated != 1) {
            throw new BusinessException(
                    ErrorCode.INVALID_REGISTRATION_TOKEN
            );
        }

        jdbc.update(
                """
                update auth_proofs
                set invalidated_at = ?
                where provider = ?
                  and provider_subject = ?
                  and purpose = 'SIGN_UP'
                  and id <> ?
                  and consumed_at is null
                  and invalidated_at is null
                  and issued_at <= ?
                """,
                utc(now),
                source.provider(),
                source.subject(),
                source.id(),
                utc(now)
        );
    }

    public boolean lockActiveAccount(long userId) {
        return jdbc.query(
                """
                select status
                from users
                where id = ?
                for update
                """,
                (rs, row) -> rs.getString("status"),
                userId
        ).stream().anyMatch("ACTIVE"::equals);
    }

    public boolean lockIdentity(
            long userId,
            String provider,
            String subject
    ) {
        return !jdbc.query(
                """
                select id
                from auth_identities
                where user_id = ?
                  and provider = ?
                  and provider_subject = ?
                for update
                """,
                (rs, row) -> rs.getLong("id"),
                userId,
                provider,
                subject
        ).isEmpty();
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(
                value,
                ZoneOffset.UTC
        );
    }

    public record Source(
            String id,
            String provider,
            String subject
    ) {

        @Override
        public String toString() {
            return "Source[identity=REDACTED]";
        }
    }

    public record Proof(
            Source source,
            String email,
            Instant issuedAt,
            Instant expiresAt,
            boolean consumed,
            boolean invalidated
    ) {

        public boolean usable(Instant now) {
            return !consumed
                    && !invalidated
                    && !issuedAt.isAfter(now)
                    && expiresAt.isAfter(now);
        }

        @Override
        public String toString() {
            return "Proof[identity=REDACTED]";
        }
    }
}