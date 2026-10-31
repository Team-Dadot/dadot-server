package com.dadot.server.domain.interview.repository;

import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.interview.model.InterviewGeneration;
import com.dadot.server.domain.interview.model.InterviewGeneration.Accepted;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Repository
@RequiredArgsConstructor
public class InterviewEvaluationRequestStore {
    private final JdbcTemplate jdbc;
    private final VerificationCrypto crypto;
    private final JsonMapper mapper;
    public record Key(String scope, String operation, String hash, String body) {}

    public Key key(long user, long attempt, String value) {
        String operation = "POST /v1/interview-attempts/" + attempt + "/evaluation-tasks";
        return new Key(crypto.hmac("evaluation-retry-scope-v1", Long.toString(user)), operation,
                crypto.hmac("evaluation-retry-key-v1", operation, InterviewGeneration.uuid(value)),
                crypto.hmac("evaluation-retry-body-v1", operation, "NO_BODY"));
    }

    public Accepted replay(long user, String task, Key key, Instant now) {
        return jdbc.query("""
                select user_id, task_id, body_hmac, status, response_http_status, response_payload, expires_at
                from api_idempotency_requests where scope_hash = ? and operation = ? and key_hash = ?
                """, (rs, n) -> {
            if (!key.body().equals(rs.getString(3))) throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
            if (!rs.getObject(7, LocalDateTime.class).toInstant(ZoneOffset.UTC).isAfter(now)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_EXPIRED);
            }
            if (rs.getLong(1) != user || !task.equals(rs.getString(2)) || !"SUCCEEDED".equals(rs.getString(4))
                    || rs.getInt(5) != 202 || rs.getString(6) == null) throw new IllegalStateException("Invalid evaluation replay state");
            Accepted result = mapper.readValue(rs.getString(6), Accepted.class);
            if (result == null || !task.equals(result.taskId()) || !"PROCESSING".equals(result.status())) {
                throw new IllegalStateException("Invalid evaluation replay response");
            }
            return result;
        }, key.scope(), key.operation(), key.hash()).stream().findFirst().orElse(null);
    }

    public boolean rateLimited(long user, Instant now) {
        Long count = jdbc.queryForObject("""
                select count(*) from api_idempotency_requests where user_id = ? and created_at > ?
                    and operation like 'POST /v1/interview-attempts/%/evaluation-tasks'
                """, Long.class, user, utc(now.minusSeconds(3600)));
        return count != null && count >= 20;
    }

    public void complete(long user, Key key, Accepted accepted, Instant now) {
        jdbc.update("""
                insert into api_idempotency_requests(user_id, task_id, scope_hash, operation, key_hash,
                    body_hmac, status, response_http_status, response_payload, created_at, expires_at)
                values (?, ?, ?, ?, ?, ?, 'SUCCEEDED', 202, ?, ?, ?)
                """, user, accepted.taskId(), key.scope(), key.operation(), key.hash(), key.body(),
                mapper.writeValueAsString(accepted), utc(now), utc(now.plusSeconds(86_400)));
    }

    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}