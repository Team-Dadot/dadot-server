package com.dadot.server.domain.experience.repository;

import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.domain.experience.model.Experience;
import com.dadot.server.domain.experience.model.Experience.Create;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ExperienceRequestStore {

    public static final String OPERATION = "POST /v1/users/me/experiences";

    private final JdbcTemplate jdbc;
    private final VerificationCrypto hmac;
    private final VerificationTokenCrypto crypto;
    private final JsonMapper mapper;
    private final Clock clock;

    public record Key(String scope, String hash, String body) {}
    public record Reference(long experienceId, long version) {}

    public Key key(long user, String value, Create body) {
        if (value == null || !value.matches(
                "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw Experience.invalid();
        }
        String canonical = UUID.fromString(value).toString();
        return new Key(hmac.hmac("experience-scope-v1", Long.toString(user)),
                hmac.hmac("experience-create-key-v1", OPERATION, canonical),
                hmac.hmac("experience-create-body-v1", OPERATION, mapper.writeValueAsString(body)));
    }

    public Optional<Reference> replay(long user, Key key) {
        return jdbc.query("""
                select user_id,body_hmac,status,response_http_status,response_payload,expires_at
                from api_idempotency_requests
                where scope_hash = ? and operation = ? and key_hash = ?
                """, (rs, n) -> {
            if (!crypto.matches(rs.getString("body_hmac"), key.body())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
            }
            if (!rs.getObject("expires_at", LocalDateTime.class)
                    .toInstant(ZoneOffset.UTC).isAfter(clock.instant())) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_EXPIRED);
            }
            if (rs.getLong("user_id") != user || !"SUCCEEDED".equals(rs.getString("status"))
                    || rs.getInt("response_http_status") != 201
                    || rs.getString("response_payload") == null) {
                throw new IllegalStateException("Invalid experience replay");
            }
            Reference reference = mapper.readValue(rs.getString("response_payload"), Reference.class);
            if (reference == null || reference.experienceId() <= 0 || reference.version() != 1) {
                throw new IllegalStateException("Invalid experience replay reference");
            }
            return reference;
        }, key.scope(), OPERATION, key.hash()).stream().findFirst();
    }

    // 제목·본문·토큰을 복제하지 않는다. 원문 삭제 후 동일 키로 복원하지 않는다.
    public void complete(long user, Key key, long id, Instant now) {
        jdbc.update("""
                insert into api_idempotency_requests(
                    user_id,scope_hash,operation,key_hash,body_hmac,status,
                    response_http_status,response_payload,created_at,expires_at)
                values (?,?,?,?,?,'SUCCEEDED',201,?,?,?)
                """, user, key.scope(), OPERATION, key.hash(), key.body(),
                mapper.writeValueAsString(new Reference(id, 1)),
                LocalDateTime.ofInstant(now, ZoneOffset.UTC),
                LocalDateTime.ofInstant(now.plusSeconds(86400), ZoneOffset.UTC));
    }
}