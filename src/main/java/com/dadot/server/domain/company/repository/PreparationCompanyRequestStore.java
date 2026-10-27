package com.dadot.server.domain.company.repository;

import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.domain.company.model.PreparationCompany;
import com.dadot.server.domain.company.model.PreparationCompany.Add;
import com.dadot.server.domain.company.model.PreparationCompany.Item;
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
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PreparationCompanyRequestStore {

    public static final String OPERATION = "POST /v1/users/me/companies";

    private final JdbcTemplate jdbc;
    private final VerificationCrypto hmac;
    private final VerificationTokenCrypto crypto;
    private final JsonMapper mapper;
    private final Clock clock;

    public record Key(String scope, String hash, String body) {}

    public Key key(long user, String value, Add body) {
        if (value == null || !value.matches(
                "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw PreparationCompany.invalid();
        }

        String canonical = UUID.fromString(value).toString()
                .toLowerCase(Locale.ROOT);

        return new Key(
                hmac.hmac("preparation-scope-v1", Long.toString(user)),
                hmac.hmac("preparation-add-key-v1", OPERATION, canonical),
                hmac.hmac("preparation-add-body-v1", OPERATION,
                        mapper.writeValueAsString(body)));
    }

    // users 잠금 안에서 호출한다. 만료 기록은 남겨 같은 키로 재실행되는 것을 막는다.
    public Optional<Item> replay(long user, Key key) {
        return jdbc.query("""
                select user_id, body_hmac, status, response_http_status,
                    response_payload, expires_at
                from api_idempotency_requests
                where scope_hash = ? and operation = ? and key_hash = ?
                """,
                        (rs, n) -> {
                            if (!crypto.matches(
                                    rs.getString("body_hmac"), key.body())) {
                                throw new BusinessException(
                                        ErrorCode.IDEMPOTENCY_CONFLICT);
                            }

                            if (!rs.getObject("expires_at", LocalDateTime.class)
                                    .toInstant(ZoneOffset.UTC)
                                    .isAfter(clock.instant())) {
                                throw new BusinessException(
                                        ErrorCode.IDEMPOTENCY_KEY_EXPIRED);
                            }

                            if (rs.getLong("user_id") != user
                                    || !"SUCCEEDED".equals(rs.getString("status"))
                                    || rs.getInt("response_http_status") != 201
                                    || rs.getString("response_payload") == null) {
                                throw new IllegalStateException(
                                        "Invalid preparation company replay");
                            }

                            Item item = mapper.readValue(
                                    rs.getString("response_payload"), Item.class);

                            if (item == null) {
                                throw new IllegalStateException(
                                        "Missing preparation company replay");
                            }
                            return item;
                        },
                        key.scope(), OPERATION, key.hash())
                .stream().findFirst();
    }

    public void complete(long user, Key key, Item body, Instant now) {
        jdbc.update("""
                insert into api_idempotency_requests(
                    user_id, scope_hash, operation, key_hash, body_hmac,
                    status, response_http_status, response_payload,
                    created_at, expires_at)
                values (?, ?, ?, ?, ?, 'SUCCEEDED', 201, ?, ?, ?)
                """,
                user, key.scope(), OPERATION, key.hash(), key.body(),
                mapper.writeValueAsString(body),
                LocalDateTime.ofInstant(now, ZoneOffset.UTC),
                LocalDateTime.ofInstant(
                        now.plusSeconds(86400), ZoneOffset.UTC));
    }
}