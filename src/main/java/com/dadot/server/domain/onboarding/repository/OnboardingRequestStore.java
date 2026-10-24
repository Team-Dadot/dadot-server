package com.dadot.server.domain.onboarding.repository;

import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.onboarding.dto.response.OnboardingCompleteResponse;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;

import static com.dadot.server.domain.onboarding.repository.OnboardingRepository.utc;

@Repository
@RequiredArgsConstructor
public class OnboardingRequestStore {

    public static final String OPERATION =
            "POST /v1/users/me/onboarding/complete";

    private static final String UUID_PATTERN =
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final JdbcTemplate jdbc;
    private final VerificationCrypto hmac;
    private final JsonMapper mapper;

    public Key key(long userId, String value) {
        if (value == null || !value.matches(UUID_PATTERN)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        return new Key(
                hmac.hmac("ONB-03-scope", Long.toString(userId)),
                hmac.hmac("ONB-03-key", value.toLowerCase(Locale.ROOT)),
                hmac.hmac("ONB-03-body", "NO_BODY")
        );
    }

    // 호출자가 users 행을 잠가 같은 사용자의 모든 완료 요청을 직렬화한다.
    public Optional<OnboardingCompleteResponse> replay(
            Key key, long userId, Instant now
    ) {
        return jdbc.query("""
                select user_id, body_hmac, status, response_http_status,
                    response_payload, expires_at
                from api_idempotency_requests
                where scope_hash = ? and operation = ? and key_hash = ?
                """,
                (rs, row) -> {
                    if (!key.bodyHmac().equals(rs.getString("body_hmac"))) {
                        throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
                    }

                    Instant expires = rs.getObject(
                            "expires_at", LocalDateTime.class
                    ).toInstant(ZoneOffset.UTC);

                    if (!expires.isAfter(now)) {
                        throw new BusinessException(
                                ErrorCode.IDEMPOTENCY_KEY_EXPIRED
                        );
                    }

                    if (rs.getLong("user_id") != userId
                            || !"SUCCEEDED".equals(rs.getString("status"))
                            || rs.getInt("response_http_status") != 200) {
                        throw new IllegalStateException(
                                "Invalid onboarding replay state"
                        );
                    }

                    try {
                        var response = mapper.readValue(
                                rs.getString("response_payload"),
                                OnboardingCompleteResponse.class
                        );

                        if (response == null
                                || !response.completed()
                                || response.selectedCompanyId() < 1
                                || response.selectedCompanyId() > 9007199254740991L) {
                            throw new IllegalStateException(
                                    "Invalid onboarding replay response"
                            );
                        }

                        return response;
                    } catch (JacksonException ex) {
                        throw new IllegalStateException(
                                "Invalid onboarding replay response"
                        );
                    }
                },
                key.scopeHash(), OPERATION, key.keyHash()
        ).stream().findFirst();
    }

    public void complete(
            Key key,
            long userId,
            OnboardingCompleteResponse response,
            Instant now
    ) {
        jdbc.update("""
                insert into api_idempotency_requests(
                    user_id, scope_hash, operation, key_hash, body_hmac, status,
                    response_http_status, response_payload, created_at, expires_at
                ) values (?, ?, ?, ?, ?, 'SUCCEEDED', 200, ?, ?, ?)
                """,
                userId,
                key.scopeHash(),
                OPERATION,
                key.keyHash(),
                key.bodyHmac(),
                mapper.writeValueAsString(response),
                utc(now),
                utc(now.plus(Duration.ofDays(1))));
    }

    public record Key(String scopeHash, String keyHash, String bodyHmac) {
    }
}