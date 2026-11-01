package com.dadot.server.domain.member.repository;

import com.dadot.server.domain.auth.repository.AuthRequestGuardRepository;
import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.domain.member.model.Member.Identity;
import com.dadot.server.domain.member.model.Member.Provider;
import com.dadot.server.global.security.SessionAuthenticationToken;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.dadot.server.domain.member.repository.MemberRepository.utc;

@Repository
@RequiredArgsConstructor
public class AccountDeletionRepository {
    private final JdbcTemplate jdbc;
    private final VerificationCrypto hmac;
    private final VerificationTokenCrypto crypto;
    private final AuthRequestGuardRepository guards;
    private final JsonMapper mapper;
    private final Clock clock;

    public record Fingerprint(String actor, String key, String body) {}

    public record Credentials(String subject, String revocationToken) {
        @Override
        public String toString() {
            return "Credentials[REDACTED]";
        }
    }

    public record Stored(
            String id,
            Long user,
            String key,
            String body,
            boolean succeeded,
            Instant createdAt,
            Instant expiresAt
    ) {}

    public record Lease(
            String id,
            long user,
            Provider provider,
            String encrypted,
            String keyVersion,
            boolean revoked,
            String owner,
            long version
    ) {
        @Override
        public String toString() {
            return "Lease[REDACTED]";
        }
    }

    public String actorHash(long user, String session, int version) {
        return hmac.hmac(
                "member-deletion-actor",
                Long.toString(user),
                session,
                Integer.toString(version)
        );
    }

    public Fingerprint fingerprint(
            SessionAuthenticationToken actor,
            String key,
            com.dadot.server.domain.member.model.Member.Delete body
    ) {
        return new Fingerprint(
                actorHash(
                        actor.getPrincipal().userId(),
                        actor.getSessionId(),
                        actor.getAuthVersion()
                ),
                hmac.hmac(
                        "member-deletion-key",
                        key.toLowerCase(Locale.ROOT)
                ),
                hmac.hmac(
                        "member-deletion-body",
                        body.provider().name(),
                        value(body.password()),
                        value(body.accessToken()),
                        value(body.identityToken()),
                        value(body.authorizationCode()),
                        value(body.nonce())
                )
        );
    }

    public Optional<Stored> find(String actorHash) {
        return jdbc.query(
                """
                select id, user_id, key_hash, body_hmac, status,
                    created_at, expires_at
                from account_deletions where actor_hash = ?
                """,
                (rs, row) -> new Stored(
                        rs.getString(1),
                        rs.getObject(2, Long.class),
                        rs.getString(3),
                        rs.getString(4),
                        "SUCCEEDED".equals(rs.getString(5)),
                        rs.getObject(
                                6, LocalDateTime.class
                        ).toInstant(ZoneOffset.UTC),
                        rs.getObject(
                                7, LocalDateTime.class
                        ).toInstant(ZoneOffset.UTC)
                ),
                actorHash
        ).stream().findFirst();
    }

    public boolean canReplay(
            long user,
            String session,
            int version,
            Instant now
    ) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                select exists(select 1 from account_deletions
                    where actor_hash = ?
                        and created_at <= ? and expires_at > ?)
                """,
                Boolean.class,
                actorHash(user, session, version),
                utc(now),
                utc(now)
        ));
    }

    // 인증 처리와 같은 잠금 순서:
    // 제공자/이메일 guard → 인증번호 → users → 세션/identity.
    public void lockSources(
            long user,
            Identity identity,
            Instant now
    ) {
        if (identity.provider() == Provider.EMAIL) {
            for (String purpose : List.of(
                    "PASSWORD_CHANGE",
                    "PASSWORD_RESET",
                    "SIGN_UP"
            )) {
                guards.lock(
                        hmac.hmac(
                                "verification-email-lock",
                                identity.subject(),
                                purpose
                        ),
                        now.plusSeconds(172800)
                );
            }

            jdbc.query(
                    """
                    select id from email_verifications
                    where user_id = ? or email = ?
                    order by id for update
                    """,
                    (rs, row) -> rs.getString(1),
                    user,
                    identity.subject()
            );
        } else {
            guards.lock(
                    hmac.hmac(
                            "social-subject",
                            identity.provider().name(),
                            identity.subject()
                    ),
                    now.plusSeconds(172800)
            );
        }
    }

    public void start(
            String id,
            long user,
            Fingerprint key,
            Identity identity,
            String revocationToken,
            Instant now
    ) {
        String encrypted = crypto.encrypt(
                mapper.writeValueAsString(
                        new Credentials(
                                identity.subject(),
                                revocationToken
                        )
                ),
                context(id)
        );

        jdbc.update(
                """
                insert into account_deletions(
                    id, user_id, actor_hash, key_hash, body_hmac,
                    provider, encrypted_credentials, key_version,
                    provider_revoked, available_at, created_at, expires_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                user,
                key.actor(),
                key.key(),
                key.body(),
                identity.provider().name(),
                encrypted,
                VerificationTokenCrypto.KEY_VERSION,
                identity.provider() == Provider.EMAIL,
                utc(now),
                utc(now),
                utc(now.plusSeconds(86400))
        );

        int changed = jdbc.update(
                """
                update users
                set status = 'DELETING',
                    auth_version = auth_version + 1,
                    updated_at = ?
                where id = ? and status = 'ACTIVE'
                    and auth_version < 2147483647
                """,
                utc(now),
                user
        );

        if (changed != 1) {
            throw new IllegalStateException("Cannot block member");
        }

        jdbc.update(
                """
                update auth_sessions
                set revoked_at = coalesce(revoked_at, ?)
                where user_id = ?
                """,
                utc(now),
                user
        );

        jdbc.update(
                "delete from user_devices where user_id = ?",
                user
        );

        jdbc.update(
                """
                update ai_contexts
                set invalidated_at = coalesce(invalidated_at, ?)
                where user_id = ?
                """,
                utc(now),
                user
        );

        jdbc.update(
                """
                update ai_tasks
                set status = 'CANCELLED',
                    last_error_code = 'CONTEXT_UNAVAILABLE',
                    completed_at = ?,
                    lease_owner = null,
                    lease_until = null
                where user_id = ? and status = 'PROCESSING'
                """,
                utc(now),
                user
        );

        jdbc.update(
                """
                update ai_task_runs r
                join ai_tasks t on t.id = r.task_id
                set r.status = 'CANCELLED',
                    r.error_code = 'CONTEXT_UNAVAILABLE',
                    r.finished_at = ?
                where t.user_id = ? and r.status = 'PROCESSING'
                """,
                utc(now),
                user
        );

        jdbc.update(
                """
                update interview_generation_jobs
                set active_user_id = null, active_company_id = null
                where user_id = ?
                """,
                user
        );

        jdbc.update(
                """
                update quiz_generation_jobs j
                join ai_tasks t on t.id = j.task_id
                set j.active_company_id = null
                where t.user_id = ?
                """,
                user
        );
    }

    public List<String> ready(Instant now) {
        return jdbc.query(
                """
                select id from account_deletions
                where status = 'PROCESSING' and available_at <= ?
                    and (lease_until is null or lease_until <= ?)
                order by available_at, id limit 10
                """,
                (rs, row) -> rs.getString(1),
                utc(now),
                utc(now)
        );
    }

    public Optional<Long> user(String id) {
        return jdbc.query(
                """
                select user_id from account_deletions
                where id = ? and status = 'PROCESSING'
                """,
                (rs, row) -> rs.getLong(1),
                id
        ).stream().findFirst();
    }

    public void lockDeletingUser(long user) {
        var rows = jdbc.query(
                "select status from users where id = ? for update",
                (rs, row) -> rs.getString(1),
                user
        );

        if (rows.size() != 1
                || !"DELETING".equals(rows.getFirst())) {
            throw new IllegalStateException("Expected deleting member");
        }
    }

    public Optional<Lease> claim(
            String id,
            String owner,
            Instant now
    ) {
        int changed = jdbc.update(
                """
                update account_deletions
                set lease_owner = ?, lease_until = ?,
                    lease_version = lease_version + 1
                where id = ? and status = 'PROCESSING'
                    and available_at <= ?
                    and (lease_until is null or lease_until <= ?)
                """,
                owner,
                utc(now.plusSeconds(120)),
                id,
                utc(now),
                utc(now)
        );

        if (changed != 1) {
            return Optional.empty();
        }

        return jdbc.query(
                """
                select user_id, provider, encrypted_credentials,
                    key_version, provider_revoked, lease_version
                from account_deletions
                where id = ? and lease_owner = ?
                """,
                (rs, row) -> new Lease(
                        id,
                        rs.getLong(1),
                        Provider.valueOf(rs.getString(2)),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getBoolean(5),
                        owner,
                        rs.getLong(6)
                ),
                id,
                owner
        ).stream().findFirst();
    }

    public Credentials credentials(Lease lease) {
        return mapper.readValue(
                crypto.decrypt(
                        lease.encrypted(),
                        lease.keyVersion(),
                        context(lease.id())
                ),
                Credentials.class
        );
    }

    public boolean owned(Lease lease, Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                select exists(select 1 from account_deletions
                    where id = ? and user_id = ?
                        and status = 'PROCESSING'
                        and lease_owner = ? and lease_version = ?
                        and lease_until > ?)
                """,
                Boolean.class,
                lease.id(),
                lease.user(),
                lease.owner(),
                lease.version(),
                utc(now)
        ));
    }

    public void revoked(Lease lease, Instant now) {
        jdbc.update(
                """
                update account_deletions
                set provider_revoked = true
                where id = ? and user_id = ?
                    and status = 'PROCESSING'
                    and lease_owner = ? and lease_version = ?
                    and lease_until > ?
                """,
                lease.id(),
                lease.user(),
                lease.owner(),
                lease.version(),
                utc(now)
        );
    }

    public void retry(Lease lease, Instant now, String code) {
        jdbc.update(
                """
                update account_deletions
                set attempts = least(attempts + 1, 2147483647),
                    last_error_code = ?,
                    available_at = timestampadd(
                        second,
                        least(3600, 30 * pow(2, least(attempts, 7))),
                        ?
                    ),
                    lease_owner = null,
                    lease_until = null
                where id = ? and status = 'PROCESSING'
                    and lease_owner = ? and lease_version = ?
                    and lease_until > ?
                """,
                code,
                utc(now),
                lease.id(),
                lease.owner(),
                lease.version(),
                utc(now)
        );
    }

    // FK를 해제하지 않고 자식 → 부모 순서로 지운다.
    // 호출자는 관련 인증 guard와 users를 잠근다.
    public void purge(
            Lease lease,
            Identity identity,
            Instant now
    ) {
        long user = lease.user();

        scrubAuthentication(user, identity);

        jdbc.update(
                "delete from api_idempotency_requests where user_id = ?",
                user
        );

        jdbc.update(
                """
                delete l from evaluation_material_links l
                join interview_evaluations e on e.id = l.evaluation_id
                where e.user_id = ?
                """,
                user
        );

        jdbc.update(
                """
                delete s from evaluation_scores s
                join interview_evaluations e on e.id = s.evaluation_id
                where e.user_id = ?
                """,
                user
        );

        delete("interview_evaluations", user);

        for (String table : List.of(
                "interview_drafts",
                "interview_question_evidence",
                "interview_questions"
        )) {
            delete(table, user);
        }

        for (String table : List.of(
                "quiz_generation_candidates",
                "quiz_generation_batches",
                "quiz_generation_jobs",
                "disclosure_sync_items",
                "ai_task_runs",
                "interview_evaluation_jobs"
        )) {
            jdbc.update(
                    "delete j from " + table + " j "
                            + "join ai_tasks t on t.id = j.task_id "
                            + "where t.user_id = ?",
                    user
            );
        }

        delete("interview_generation_jobs", user);
        delete("ai_tasks", user);

        for (String table : List.of(
                "interview_attempts",
                "interview_submissions",
                "interview_daily_questions",
                "interview_question_keys",
                "ai_context_materials",
                "ai_contexts",
                "quiz_answers",
                "quiz_set_items",
                "quiz_sets"
        )) {
            delete(table, user);
        }

        jdbc.update(
                """
                update personal_materials set current_version_no = null
                where user_id = ?
                """,
                user
        );

        for (String table : List.of(
                "material_versions",
                "personal_materials",
                "user_preferences",
                "notification_settings",
                "user_onboarding",
                "user_companies",
                "user_devices"
        )) {
            delete(table, user);
        }

        jdbc.update(
                """
                delete p from auth_proofs p
                where p.user_id = ? or (
                    p.provider = ? and (
                        (p.provider = 'EMAIL' and p.subject_email = ?)
                        or (
                            p.provider <> 'EMAIL'
                            and p.provider_subject = ?
                        )
                    )
                )
                """,
                user,
                identity.provider().name(),
                identity.subject(),
                identity.subject()
        );

        if (identity.provider() == Provider.EMAIL) {
            jdbc.update(
                    """
                    delete from email_verifications
                    where user_id = ? or email = ?
                    """,
                    user,
                    identity.subject()
            );
        } else {
            delete("email_verifications", user);
        }

        for (String table : List.of(
                "auth_sessions",
                "auth_identities",
                "user_agreements"
        )) {
            delete(table, user);
        }

        // 관리자 계정도 공통 게시 자료를 삭제하지 않는다.
        // 작성자 연결만 제거한다.
        for (String table : List.of(
                "quiz_question_versions",
                "interview_policy_versions"
        )) {
            jdbc.update(
                    "update " + table
                            + " set reviewed_by = null where reviewed_by = ?",
                    user
            );
        }

        Instant finishingAt = clock.instant();

        int finished = jdbc.update(
                """
                update account_deletions
                set status = 'SUCCEEDED',
                    user_id = null,
                    provider = null,
                    encrypted_credentials = null,
                    key_version = null,
                    lease_owner = null,
                    lease_until = null,
                    completed_at = ?,
                    last_error_code = null
                where id = ? and user_id = ?
                    and status = 'PROCESSING'
                    and provider_revoked = true
                    and lease_owner = ? and lease_version = ?
                    and lease_until > ?
                """,
                utc(finishingAt),
                lease.id(),
                user,
                lease.owner(),
                lease.version(),
                utc(finishingAt)
        );

        if (finished != 1) {
            throw new IllegalStateException("Deletion lease lost");
        }

        if (jdbc.update(
                "delete from users where id = ? and status = 'DELETING'",
                user
        ) != 1) {
            throw new IllegalStateException("Member deletion failed");
        }
    }

    private void scrubAuthentication(long user, Identity identity) {
        String subjectHash = hmac.hmac(
                "auth-subject",
                identity.provider().name(),
                identity.subject()
        );

        jdbc.update(
                """
                update api_idempotency_requests
                set response_payload = null,
                    encrypted_response = null,
                    response_key_version = null
                where subject_hash = ?
                """,
                subjectHash
        );

        if (identity.provider() == Provider.EMAIL) {
            jdbc.update(
                    """
                    update api_idempotency_requests r
                    join email_verifications v on (
                        r.operation = concat(
                            'POST /v1/auth/email-verifications/',
                            v.id,
                            '/verify'
                        )
                        or (
                            r.operation = 'POST /v1/auth/email-verifications'
                            and json_unquote(
                                json_extract(
                                    r.response_payload,
                                    '$.verificationId'
                                )
                            ) = v.id
                        )
                    )
                    set r.response_payload = null,
                        r.encrypted_response = null,
                        r.response_key_version = null
                    where v.user_id = ? or v.email = ?
                    """,
                    user,
                    identity.subject()
            );
        } else {
            // V18 이전 가입 전 소셜 응답에는 subject_hash가 없다.
            // 이행 기간에만 100행씩 읽어 소유자를 검증한다.
            long cursor = 0;

            while (true) {
                var legacy = jdbc.query(
                        """
                        select id, scope_hash, operation, key_hash,
                            encrypted_response, response_key_version
                        from api_idempotency_requests
                        where id > ? and subject_hash is null
                            and user_id is null
                            and encrypted_response is not null
                            and operation in (
                                'POST /v1/auth/social/kakao',
                                'POST /v1/auth/social/apple'
                            )
                        order by id limit 100
                        """,
                        (rs, row) -> new Legacy(
                                rs.getLong(1),
                                rs.getString(2)
                                        + ":"
                                        + rs.getString(3)
                                        + ":"
                                        + rs.getString(4),
                                rs.getString(3),
                                rs.getString(5),
                                rs.getString(6)
                        ),
                        cursor
                );

                if (legacy.isEmpty()) {
                    break;
                }

                for (Legacy item : legacy) {
                    cursor = item.id();

                    String suffix =
                            identity.provider() == Provider.APPLE
                                    ? "/apple"
                                    : "/kakao";

                    if (!item.operation().endsWith(suffix)) {
                        continue;
                    }

                    var body = mapper.readTree(
                            crypto.decrypt(
                                    item.encrypted(),
                                    item.version(),
                                    item.context()
                            )
                    );

                    if (identity.subject().equals(
                            body.path("subject").asString()
                    )) {
                        jdbc.update(
                                """
                                update api_idempotency_requests
                                set response_payload = null,
                                    encrypted_response = null,
                                    response_key_version = null,
                                    subject_hash = ?
                                where id = ?
                                """,
                                subjectHash,
                                item.id()
                        );
                    }
                }
            }
        }
    }

    public void clean(Instant now) {
        jdbc.update(
                """
                delete from account_deletions
                where status = 'SUCCEEDED' and expires_at <= ?
                order by expires_at limit 500
                """,
                utc(now)
        );
    }

    private void delete(String table, long user) {
        jdbc.update(
                "delete from " + table + " where user_id = ?",
                user
        );
    }

    private static String context(String id) {
        return "member-deletion:" + id;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private record Legacy(
            long id,
            String context,
            String operation,
            String encrypted,
            String version
    ) {}
}