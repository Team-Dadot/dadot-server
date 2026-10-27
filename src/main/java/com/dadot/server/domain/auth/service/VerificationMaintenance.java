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

        int deleted;
        int rounds = 0;

        do {
            deleted = jdbc.update(
                    """
                    delete from auth_request_guards
                    where expires_at < ?
                    order by expires_at
                    limit 1000
                    """,
                    now
            );

            rounds++;

        } while (deleted == 1000 && rounds < 20);

        jdbc.update(
                """
                delete from apple_login_challenges
                where expires_at < ?
                order by expires_at
                limit 1000
                """,
                now
        );

        // 응답 데이터만 제거하고 중복 실행 방지용 키 기록은 유지한다.
        jdbc.update(
                """
                update api_idempotency_requests
                set response_payload = null,
                    encrypted_response = null,
                    response_key_version = null
                where (
                    operation = 'POST /v1/users/me/onboarding/complete'
                    or operation = 'POST /v1/users/me/companies'
                    or operation = 'POST /v1/admin/quiz-questions'
                    or operation like 'POST /v1/companies/%/quiz-sets/today'
                    or operation like 'POST /v1/quiz-sets/%/answers'
                    or operation = 'POST /v1/auth/social/apple'
                    or operation = 'POST /v1/auth/social/signup'
                    or operation = 'POST /v1/auth/social/kakao'
                    or operation = 'POST /v1/auth/signup'
                    or operation = 'POST /v1/auth/email-verifications'
                    or operation like 'POST /v1/auth/email-verifications/%/verify'
                )
                  and expires_at < ?
                  and (
                      response_payload is not null
                      or encrypted_response is not null
                  )
                limit 1000
                """,
                now
        );
    }
}