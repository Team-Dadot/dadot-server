package com.dadot.server.domain.interview.repository;

import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.interview.model.InterviewPolicy.View;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class InterviewPolicyRequestStore {

    public static final String OPERATION = "POST /v1/admin/interview-policies";
    private final JdbcTemplate jdbc;
    private final VerificationCrypto crypto;
    private final JsonMapper mapper;
    private final Clock clock;

    public record Key(String scope, String hash, String body) {}

    public Key key(long userId, String value, JsonNode body) {
        if (value == null || !value.matches(
                "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw InterviewPolicy.invalid();
        }
        return new Key(crypto.hmac("interview-policy-scope-v1", Long.toString(userId)),
                crypto.hmac("interview-policy-key-v1", OPERATION, UUID.fromString(value).toString()),
                crypto.hmac("interview-policy-body-v1", OPERATION, mapper.writeValueAsString(canonical(body))));
    }

    // 호출자는 관리자 users 행을 잠근다. 다른 관리자의 키와 분리한다.
    public Optional<View> replay(long userId, Key key) {
        return jdbc.query("""
                select user_id, body_hmac, status, response_http_status, response_payload, expires_at
                from api_idempotency_requests where scope_hash = ? and operation = ? and key_hash = ?
                """, (rs, n) -> {
            if (!key.body().equals(rs.getString("body_hmac"))) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
            }
            if (!rs.getObject("expires_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)
                    .isAfter(clock.instant())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_EXPIRED);
            }
            if (rs.getLong("user_id") != userId || !"SUCCEEDED".equals(rs.getString("status"))
                    || rs.getInt("response_http_status") != 201 || rs.getString("response_payload") == null) {
                throw new IllegalStateException("Invalid interview policy replay state");
            }
            View result = mapper.readValue(rs.getString("response_payload"), View.class);
            if (result == null || result.policyId() < 1 || result.policyVersionId() < 1) {
                throw new IllegalStateException("Invalid interview policy replay result");
            }
            return result;
        }, key.scope(), OPERATION, key.hash()).stream().findFirst();
    }

    public void complete(long userId, Key key, View result, Instant now) {
        jdbc.update("""
                insert into api_idempotency_requests(
                    user_id, scope_hash, operation, key_hash, body_hmac, status,
                    response_http_status, response_payload, created_at, expires_at)
                values (?, ?, ?, ?, ?, 'SUCCEEDED', 201, ?, ?, ?)
                """, userId, key.scope(), OPERATION, key.hash(), key.body(), mapper.writeValueAsString(result),
                utc(now), utc(now.plusSeconds(86_400)));
    }

    // 객체 키 순서만 정규화한다. 생략/null, 문자열 내용과 배열 순서는 보존한다.
    private static Object canonical(JsonNode node) {
        if (node.isObject()) {
            var fields = new TreeMap<String, Object>();
            for (String name : node.propertyNames()) fields.put(name, canonical(node.get(name)));
            return fields;
        }
        if (node.isArray()) {
            var values = new ArrayList<Object>();
            for (JsonNode item : node) values.add(canonical(item));
            return values;
        }
        return node;
    }

    private static LocalDateTime utc(Instant now) { return LocalDateTime.ofInstant(now, ZoneOffset.UTC); }
}