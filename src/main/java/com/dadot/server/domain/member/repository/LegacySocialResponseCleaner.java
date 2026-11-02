package com.dadot.server.domain.member.repository;

import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.domain.member.model.Member.Provider;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class LegacySocialResponseCleaner {
    private static final Logger log =
            LoggerFactory.getLogger(LegacySocialResponseCleaner.class);

    private final JdbcTemplate jdbc;
    private final VerificationTokenCrypto crypto;
    private final JsonMapper mapper;
    private final Clock clock;

    // 호출자의 탈퇴 트랜잭션에서 실행한다.
    // 업무 데이터나 가입 증빙을 임의 삭제하지 않는다.
    public void clean(
            Provider provider,
            String subject,
            String subjectHash
    ) {
        if (provider == Provider.EMAIL) {
            throw new IllegalArgumentException(
                    "Social provider required"
            );
        }

        String operation = "POST /v1/auth/social/"
                + provider.name().toLowerCase(Locale.ROOT);

        long cursor = 0;

        while (true) {
            var rows = jdbc.query(
                    """
                    select id, scope_hash, key_hash,
                        encrypted_response, response_key_version,
                        expires_at
                    from api_idempotency_requests
                    where id > ?
                        and subject_hash is null
                        and user_id is null
                        and encrypted_response is not null
                        and operation = ?
                    order by id limit 100
                    """,
                    (rs, row) -> new Legacy(
                            rs.getLong(1),
                            rs.getString(2)
                                    + ":"
                                    + operation
                                    + ":"
                                    + rs.getString(3),
                            rs.getString(4),
                            rs.getString(5),
                            rs.getObject(6, LocalDateTime.class)
                    ),
                    cursor,
                    operation
            );

            if (rows.isEmpty()) {
                return;
            }

            for (Legacy row : rows) {
                cursor = row.id();

                if (!row.expiresAt()
                        .toInstant(ZoneOffset.UTC)
                        .isAfter(clock.instant())) {
                    clear(row, operation, null);
                    continue;
                }

                // 키 버전이 지원되지 않으면 유효한 과거 암호문일 수
                // 있으므로 폐기하지 않는다.
                if (!VerificationTokenCrypto.KEY_VERSION.equals(
                        row.version()
                )) {
                    throw new BusinessException(
                            ErrorCode.SERVICE_UNAVAILABLE
                    );
                }

                String owner = owner(row);

                if (owner == null) {
                    // 현재 지원 키로 복구할 수 없는 일시 인증 응답은
                    // 원문을 남기지 않는다.
                    // 소유자를 추정하거나 탈퇴 회원의 subject_hash를
                    // 붙이지 않는다.
                    clear(row, operation, null);
                } else if (subject.equals(owner)) {
                    clear(row, operation, subjectHash);
                }
            }
        }
    }

    private String owner(Legacy row) {
        String plain;

        try {
            plain = crypto.decrypt(
                    row.encrypted(),
                    row.version(),
                    row.context()
            );
        } catch (BusinessException ex) {
            if (ex.getErrorCode() != ErrorCode.SERVICE_UNAVAILABLE) {
                throw ex;
            }

            invalid(
                    "DECRYPTION_FAILED",
                    ex.getClass().getName()
            );

            return null;
        }

        JsonNode body;

        try {
            body = mapper.reader()
                    .with(
                            tools.jackson.databind.DeserializationFeature
                                    .FAIL_ON_TRAILING_TOKENS
                    )
                    .readTree(plain);
        } catch (JacksonException ex) {
            invalid(
                    "INVALID_JSON",
                    ex.getClass().getName()
            );

            return null;
        }

        JsonNode subject =
                body == null || !body.isObject()
                        ? null
                        : body.get("subject");

        if (subject == null
                || !subject.isString()
                || subject.asString().isBlank()
                || subject.asString().length() > 255) {
            invalid("INVALID_SUBJECT", null);
            return null;
        }

        return subject.asString();
    }

    private void clear(
            Legacy row,
            String operation,
            String subjectHash
    ) {
        // 정상 응답으로 교체되거나 다른 경로에서 정리된 행을
        // 덮어쓰지 않는다.
        // DB 예외는 잡지 않는다.
        // 실패하면 탈퇴 트랜잭션 전체가 롤백된다.
        jdbc.update(
                """
                update api_idempotency_requests
                set response_payload = null,
                    encrypted_response = null,
                    response_key_version = null,
                    subject_hash = ?
                where id = ?
                    and operation = ?
                    and user_id is null
                    and subject_hash is null
                    and encrypted_response = ?
                    and response_key_version <=> ?
                """,
                subjectHash,
                row.id(),
                operation,
                row.encrypted(),
                row.version()
        );
    }

    private static void invalid(
            String reason,
            String exceptionClass
    ) {
        log.warn(
                "Legacy social response invalidated: "
                        + "reason={}, exceptionClass={}",
                reason,
                exceptionClass
        );
    }

    private record Legacy(
            long id,
            String context,
            String encrypted,
            String version,
            LocalDateTime expiresAt
    ) {
        @Override
        public String toString() {
            return "Legacy[REDACTED]";
        }
    }
}