package com.dadot.server.domain.quiz.repository;

import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.quiz.model.QuizBank;
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

import static com.dadot.server.domain.quiz.model.QuizPlay.fail;
import static com.dadot.server.domain.quiz.repository.QuizPlayRepository.utc;

@Repository
@RequiredArgsConstructor
public class QuizPlayRequestStore {

    private final JdbcTemplate jdbc;
    private final VerificationCrypto crypto;
    private final JsonMapper mapper;
    private final Clock clock;

    public record Key(String scope, String operation, String hash, String body) {}
    public record Stored<T>(int status, T body) {}

    public Key key(long user, String operation, String value, Object body) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) {
                throw QuizBank.invalid();
            }
        } catch (IllegalArgumentException ex) {
            throw QuizBank.invalid();
        }
        return new Key(crypto.hmac("quiz-play-scope-v1", Long.toString(user)), operation,
                crypto.hmac("quiz-play-key-v1", operation, value),
                crypto.hmac("quiz-play-body-v1", operation, mapper.writeValueAsString(body)));
    }

    // users 행 잠금 아래 호출한다. 빈 조회에는 gap 잠금을 잡지 않는다.
    public <T> Optional<Stored<T>> replay(long user, Key key, Class<T> type) {
        return jdbc.query("""
                select user_id, body_hmac, status, response_http_status, response_payload, expires_at
                from api_idempotency_requests
                where scope_hash = ? and operation = ? and key_hash = ?
                """, (rs, n) -> {
            if (!key.body().equals(rs.getString("body_hmac"))) {
                throw fail(ErrorCode.IDEMPOTENCY_CONFLICT);
            }
            if (!rs.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)
                    .isAfter(clock.instant())) {
                throw fail(ErrorCode.IDEMPOTENCY_KEY_EXPIRED);
            }
            int status = rs.getInt("response_http_status");
            if (rs.getLong("user_id") != user || !"SUCCEEDED".equals(rs.getString("status"))
                    || (status != 200 && status != 201) || rs.getString("response_payload") == null) {
                throw new IllegalStateException("Invalid quiz request replay");
            }
            T body = mapper.readValue(rs.getString("response_payload"), type);
            if (body == null) {
                throw new IllegalStateException("Missing quiz replay payload");
            }
            return new Stored<>(status, body);
        }, key.scope(), key.operation(), key.hash()).stream().findFirst();
    }

    public void complete(long user, Key key, int status, Object body, Instant now) {
        jdbc.update("""
                insert into api_idempotency_requests(user_id, scope_hash, operation, key_hash,
                    body_hmac, status, response_http_status, response_payload, created_at, expires_at)
                values (?, ?, ?, ?, ?, 'SUCCEEDED', ?, ?, ?, ?)
                """, user, key.scope(), key.operation(), key.hash(), key.body(), status,
                mapper.writeValueAsString(body), utc(now), utc(now.plus(Duration.ofDays(1))));
    }
}