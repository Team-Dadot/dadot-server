package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class AppleLoginChallengeService {

    private final JdbcTemplate jdbc;
    private final VerificationTokenCrypto crypto;
    private final Clock clock;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            timeout = 5
    )
    public Challenge issue() {
        Instant now = clock.instant()
                .truncatedTo(ChronoUnit.MICROS);

        Instant expiresAt = now.plusSeconds(300);
        String nonce = crypto.generateToken();

        jdbc.update(
                """
                insert into apple_login_challenges(
                    nonce_hash,
                    issued_at,
                    expires_at
                )
                values (?, ?, ?)
                """,
                crypto.tokenHash(nonce),
                utc(now),
                utc(expiresAt)
        );

        return new Challenge(nonce, expiresAt);
    }

    // 외부 코드 교환 전에 커밋해 동시·재사용 교환을 막는다.
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            timeout = 5
    )
    public Claim consume(
            String nonce,
            String requestHash
    ) {
        String hash = crypto.tokenHash(nonce);

        var stored = jdbc.query(
                        """
                        select
                            issued_at,
                            expires_at,
                            consumed_at,
                            request_hash,
                            failed_at
                        from apple_login_challenges
                        where nonce_hash = ?
                        for update
                        """,
                        (rs, row) -> new Stored(
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
                                ),
                                rs.getString("request_hash"),
                                rs.getObject(
                                        "failed_at",
                                        LocalDateTime.class
                                )
                        ),
                        hash
                ).stream()
                .findFirst()
                .orElseThrow(this::invalid);

        Instant now = clock.instant();

        if (stored.issuedAt().isAfter(now)
                || !stored.expiresAt().isAfter(now)
                || stored.failedAt() != null) {
            throw invalid();
        }

        if (stored.consumedAt() != null) {
            if (!crypto.matches(
                    stored.requestHash(),
                    requestHash
            )) {
                throw invalid();
            }

            return new Claim(
                    stored.expiresAt(),
                    false
            );
        }

        jdbc.update(
                """
                update apple_login_challenges
                set consumed_at = ?,
                    request_hash = ?
                where nonce_hash = ?
                """,
                utc(now),
                requestHash,
                hash
        );

        return new Claim(
                stored.expiresAt(),
                true
        );
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            timeout = 5
    )
    public void fail(
            String nonce,
            String requestHash
    ) {
        jdbc.update(
                """
                update apple_login_challenges
                set failed_at = ?
                where nonce_hash = ?
                  and request_hash = ?
                  and consumed_at is not null
                """,
                utc(clock.instant()),
                crypto.tokenHash(nonce),
                requestHash
        );
    }

    private BusinessException invalid() {
        return new BusinessException(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL
        );
    }

    private static LocalDateTime utc(Instant time) {
        return LocalDateTime.ofInstant(
                time,
                ZoneOffset.UTC
        );
    }

    public record Claim(
            Instant expiresAt,
            boolean owned
    ) {
    }

    private record Stored(
            Instant issuedAt,
            Instant expiresAt,
            LocalDateTime consumedAt,
            String requestHash,
            LocalDateTime failedAt
    ) {
    }

    public record Challenge(
            String nonce,
            Instant expiresAt
    ) {

        @Override
        public String toString() {
            return "Challenge[nonce=REDACTED]";
        }
    }
}