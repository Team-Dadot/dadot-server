package com.dadot.server.domain.quiz.repository;

import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.quiz.model.QuizBank;
import com.dadot.server.domain.quiz.model.QuizBank.Content;
import com.dadot.server.domain.quiz.model.QuizBank.View;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class QuizRequestStore {

    public static final String OPERATION =
            "POST /v1/admin/quiz-questions";

    private final JdbcTemplate jdbc;
    private final VerificationCrypto crypto;
    private final JsonMapper mapper;
    private final Clock clock;

    public record Key(
            String scope,
            String hash,
            String body
    ) {}

    public Key key(
            long userId,
            String value,
            Content content
    ) {
        try {
            if (value == null
                    || !UUID.fromString(value).toString().equals(value)) {
                throw QuizBank.invalid();
            }

        } catch (IllegalArgumentException ex) {
            throw QuizBank.invalid();
        }

        return new Key(
                crypto.hmac(
                        "quiz-admin-scope-v1",
                        Long.toString(userId)
                ),
                crypto.hmac(
                        "quiz-admin-key-v1",
                        OPERATION,
                        value
                ),
                crypto.hmac(
                        "quiz-admin-body-v1",
                        OPERATION,
                        mapper.writeValueAsString(content)
                )
        );
    }

    // 호출자가 users 행을 잠근다.
    // 빈 조회에 FOR UPDATE를 사용하지 않는다.
    public Optional<View> replay(
            Key key,
            long userId
    ) {
        return jdbc.query(
                """
                select user_id,
                       body_hmac,
                       status,
                       response_http_status,
                       response_payload,
                       expires_at
                from api_idempotency_requests
                where scope_hash = ?
                  and operation = ?
                  and key_hash = ?
                """,
                (rs, n) -> {
                    if (!key.body().equals(rs.getString("body_hmac"))) {
                        throw new BusinessException(
                                ErrorCode.IDEMPOTENCY_CONFLICT
                        );
                    }

                    if (!rs.getObject(
                            "expires_at",
                            LocalDateTime.class
                    ).toInstant(ZoneOffset.UTC).isAfter(clock.instant())) {
                        throw new BusinessException(
                                ErrorCode.IDEMPOTENCY_KEY_EXPIRED
                        );
                    }

                    if (rs.getLong("user_id") != userId
                            || !"SUCCEEDED".equals(rs.getString("status"))
                            || rs.getInt("response_http_status") != 201
                            || rs.getString("response_payload") == null) {
                        throw new IllegalStateException(
                                "Invalid quiz replay state"
                        );
                    }

                    var result = mapper.readValue(
                            rs.getString("response_payload"),
                            View.class
                    );

                    if (result == null
                            || result.questionId() < 1
                            || result.questionVersionId() < 1) {
                        throw new IllegalStateException(
                                "Invalid quiz replay result"
                        );
                    }

                    return result;
                },
                key.scope(),
                OPERATION,
                key.hash()
        ).stream().findFirst();
    }

    public void complete(
            Key key,
            long userId,
            View response,
            Instant now
    ) {
        jdbc.update(
                """
                insert into api_idempotency_requests(
                    user_id,
                    scope_hash,
                    operation,
                    key_hash,
                    body_hmac,
                    status,
                    response_http_status,
                    response_payload,
                    created_at,
                    expires_at
                )
                values (?, ?, ?, ?, ?, 'SUCCEEDED', 201, ?, ?, ?)
                """,
                userId,
                key.scope(),
                OPERATION,
                key.hash(),
                key.body(),
                mapper.writeValueAsString(response),
                utc(now),
                utc(now.plus(Duration.ofDays(1)))
        );
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(
                value,
                ZoneOffset.UTC
        );
    }
}