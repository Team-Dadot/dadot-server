package com.dadot.server.domain.interview.repository;

import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.interview.model.InterviewGeneration;
import com.dadot.server.domain.interview.model.InterviewSubmission.Accepted;
import com.dadot.server.domain.interview.model.InterviewSubmission.Submit;
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
public class InterviewSubmissionRequestStore {
    private final JdbcTemplate jdbc;
    private final VerificationCrypto crypto;
    private final JsonMapper mapper;

    public record Key(String scope, String operation, String hash, String body) {}

    public Key key(long user, long question, String value, Submit input) {
        String operation = "POST /v1/interview-questions/" + question + "/attempts";
        String uuid = InterviewGeneration.uuid(value);
        return new Key(
                crypto.hmac("interview-submission-scope-v1", Long.toString(user)),
                operation,
                crypto.hmac("interview-submission-key-v1", operation, uuid),
                crypto.hmac("interview-submission-body-v1", operation,
                        mapper.writeValueAsString(input.canonicalBody())));
    }

    // 호출자는 users 행을 잠근다. 최초 저장과 재시도를 같은 잠금으로 직렬화한다.
    public Accepted replay(long user, Key key, Instant now) {
        return jdbc.query("""
                select user_id, body_hmac, status, response_http_status,
                       response_payload, expires_at
                from api_idempotency_requests
                where scope_hash = ? and operation = ? and key_hash = ?
                """, (rs, row) -> {
            if (!key.body().equals(rs.getString(2))) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
            }
            if (!rs.getObject(6, LocalDateTime.class).toInstant(ZoneOffset.UTC).isAfter(now)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_EXPIRED);
            }
            if (rs.getLong(1) != user || !"SUCCEEDED".equals(rs.getString(3))
                    || rs.getInt(4) != 202 || rs.getString(5) == null) {
                throw new IllegalStateException("Invalid interview submission replay state");
            }
            Accepted result = mapper.readValue(rs.getString(5), Accepted.class);
            if (result == null || result.attemptId() < 1 || result.taskId() == null
                    || !"PROCESSING".equals(result.status()) || result.usage() == null) {
                throw new IllegalStateException("Invalid interview submission replay result");
            }
            return result;
        }, key.scope(), key.operation(), key.hash()).stream().findFirst().orElse(null);
    }

    public void complete(long user, Key key, Accepted result, Instant now) {
        jdbc.update("""
                insert into api_idempotency_requests(
                    user_id, task_id, scope_hash, operation, key_hash, body_hmac,
                    status, response_http_status, response_payload, created_at, expires_at
                ) values (?, ?, ?, ?, ?, ?, 'SUCCEEDED', 202, ?, ?, ?)
                """, user, result.taskId(), key.scope(), key.operation(), key.hash(), key.body(),
                mapper.writeValueAsString(result), utc(now), utc(now.plusSeconds(86_400)));
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}