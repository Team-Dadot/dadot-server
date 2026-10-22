package com.dadot.server.domain.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.email-verification.maintenance-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class VerificationMaintenance {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Scheduled(
            fixedDelayString =
                    "${app.email-verification.maintenance-delay-ms:300000}"
    )
    public void clean() {
        LocalDateTime now = LocalDateTime.ofInstant(
                clock.instant(),
                ZoneOffset.UTC
        );

        jdbc.update(
                """
                delete from auth_request_guards
                where expires_at < ?
                order by expires_at
                limit 1000
                """,
                now
        );

        jdbc.update(
                """
                update api_idempotency_requests
                set response_payload = null,
                    encrypted_response = null,
                    response_key_version = null
                where operation = 'POST /v1/auth/email-verifications'
                  and expires_at < ?
                  and response_payload is not null
                limit 1000
                """,
                now
        );
    }
}