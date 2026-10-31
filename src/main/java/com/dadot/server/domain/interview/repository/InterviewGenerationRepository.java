package com.dadot.server.domain.interview.repository;

import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.interview.model.InterviewGeneration;
import com.dadot.server.domain.interview.model.InterviewGeneration.*;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Repository
@RequiredArgsConstructor
public class InterviewGenerationRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final VerificationCrypto crypto;
    private final InterviewSourceRepository sources;
    private final InterviewReadRepository reads;

    public record RequestKey(
            String scope, String operation, String key, String body
    ) {}

    public record Claim(
            String taskId,
            long userId,
            long companyId,
            String owner,
            long fence,
            long runId,
            int attempt
    ) {}

    public RequestKey key(long user, long company, String key) {
        String operation = "POST /v1/companies/"
                + company + "/interview-question-tasks";

        return new RequestKey(
                crypto.hmac("interview-scope-v1", Long.toString(user)),
                operation,
                crypto.hmac(
                        "interview-key-v1",
                        operation,
                        InterviewGeneration.uuid(key)
                ),
                crypto.hmac("interview-body-v1", operation, "NO_BODY")
        );
    }

    public String replay(
            RequestKey key,
            long user,
            long company,
            Instant now
    ) {
        return jdbc.query("""
                select r.body_hmac, r.expires_at, r.task_id, t.status,
                       exists(
                           select 1 from interview_questions q
                           where q.generation_task_id = t.id
                       ),
                       t.user_id, t.company_id, t.type
                from api_idempotency_requests r
                left join ai_tasks t on t.id = r.task_id
                where r.scope_hash = ? and r.operation = ?
                  and r.key_hash = ? and r.user_id = ?
                """,
                (rs, n) -> {
                    if (!key.body().equals(rs.getString(1))) {
                        throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
                    }

                    if (!rs.getObject(2, LocalDateTime.class)
                            .toInstant(ZoneOffset.UTC).isAfter(now)) {
                        throw new BusinessException(
                                ErrorCode.IDEMPOTENCY_KEY_EXPIRED
                        );
                    }

                    if (rs.getObject(6) == null
                            || rs.getLong(6) != user
                            || rs.getLong(7) != company
                            || !"INTERVIEW_GENERATION".equals(rs.getString(8))) {
                        throw InterviewPolicy.missing();
                    }

                    if ("SUCCEEDED".equals(rs.getString(4))
                            && !rs.getBoolean(5)) {
                        throw InterviewPolicy.missing();
                    }

                    return rs.getString(3);
                },
                key.scope(), key.operation(), key.key(), user
        ).stream().findFirst().orElse(null);
    }

    public void complete(
            RequestKey key,
            long user,
            String taskId,
            Instant now
    ) {
        jdbc.update("""
                insert into api_idempotency_requests(
                    user_id, task_id, scope_hash, operation, key_hash, body_hmac,
                    status, response_http_status, response_payload,
                    created_at, expires_at
                )
                values (?, ?, ?, ?, ?, ?, 'SUCCEEDED', 202, ?, ?, ?)
                """,
                user, taskId, key.scope(), key.operation(), key.key(), key.body(),
                mapper.writeValueAsString(new Accepted(taskId, "PROCESSING")),
                utc(now), utc(now.plusSeconds(86_400))
        );
    }

    public Policy policy(Preparation prep) {
        return jdbc.query("""
                select v.id, v.prompt_template, v.rule_config
                from interview_policies p
                join interview_policy_versions v
                  on v.policy_id = p.id
                 and v.version_no = p.active_version_no
                where p.type = 'GENERATION'
                  and v.status = 'PUBLISHED'
                  and (p.company_id is null or p.company_id = ?)
                  and (p.job_id is null or p.job_id = ?)
                order by (p.company_id is not null) desc,
                         (p.job_id is not null) desc
                limit 1
                """,
                (rs, n) -> {
                    if (!mapper.readTree(rs.getString(3)).equals(
                            mapper.readTree("""
                                    {
                                      "materialTypes":["EXPERIENCE"],
                                      "aggregationRule":null
                                    }
                                    """)
                    )) {
                        throw InterviewPolicy.notConfigured();
                    }

                    return new Policy(rs.getLong(1), rs.getString(2));
                },
                prep.companyId(), prep.jobId()
        ).stream().findFirst().orElseThrow(InterviewPolicy::notConfigured);
    }

    public void admission(long user, Instant now) {
        jdbc.queryForObject("""
                select id from interview_generation_control
                where id = 1 for update
                """, Integer.class);

        if (count("""
                select count(*) from ai_tasks
                where type = 'INTERVIEW_GENERATION' and status = 'PROCESSING'
                """) >= 20) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }

        if (count("""
                select count(*) from ai_tasks
                where user_id = ? and type = 'INTERVIEW_GENERATION'
                  and created_at > ?
                """, user, utc(now.minusSeconds(3600))) >= 20) {
            throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED);
        }
    }

    public List<String> previous(long user, long company) {
        return jdbc.query("""
                select question_text from interview_questions
                where user_id = ? and company_id = ?
                order by created_at desc, id desc
                limit 10
                """,
                (rs, n) -> rs.getString(1),
                user, company
        );
    }

    public String create(
            Preparation prep,
            Policy policy,
            Plan plan,
            List<Material> materials,
            RequestKey key,
            String model,
            String promptHash,
            Instant now
    ) {
        String context = UUID.randomUUID().toString();
        String task = UUID.randomUUID().toString();

        jdbc.update("""
                insert into ai_contexts(
                    id, user_id, company_id, job_id_snapshot, job_is_custom,
                    job_name_snapshot, personalization_version, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                context, prep.userId(), prep.companyId(),
                prep.jobId(), prep.jobId() == null,
                prep.jobName(), prep.personalizationVersion(), utc(now)
        );

        for (Material material : materials) {
            jdbc.update("""
                    insert into ai_context_materials(
                        context_id, material_version_id, user_id
                    )
                    values (?, ?, ?)
                    """, context, material.versionId(), prep.userId());
        }

        jdbc.update("""
                insert into ai_tasks(
                    id, user_id, company_id, type, context_id, policy_version_id,
                    request_key_hash, request_body_hmac, available_at, created_at
                )
                values (?, ?, ?, 'INTERVIEW_GENERATION', ?, ?, ?, ?, ?, ?)
                """,
                task, prep.userId(), prep.companyId(), context,
                policy.versionId(), key.key(), key.body(), utc(now), utc(now)
        );

        jdbc.update("""
                insert into interview_generation_jobs(
                    task_id, user_id, company_id, active_user_id, active_company_id,
                    model, prompt_hash, sources_json, sections_json,
                    scanned_sections, scanned_characters
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                task, prep.userId(), prep.companyId(),
                prep.userId(), prep.companyId(),
                model, promptHash,
                mapper.writeValueAsString(plan.sources()),
                mapper.writeValueAsString(plan.sections()),
                plan.scannedSections(), plan.scannedCharacters()
        );

        return task;
    }

    public Claim claim(Instant now) {
        var next = jdbc.queryForObject("""
                select next_call_at from interview_generation_control
                where id = 1 for update
                """, LocalDateTime.class);

        if (Objects.requireNonNull(next).isAfter(utc(now))
                || count("""
                select count(*) from ai_tasks
                where type = 'INTERVIEW_GENERATION'
                  and status = 'PROCESSING' and lease_until > ?
                """, utc(now)) != 0) {
            return null;
        }

        var rows = jdbc.query("""
                select t.id, t.user_id, t.company_id, t.lease_version, j.attempts
                from ai_tasks t
                join interview_generation_jobs j on j.task_id = t.id
                where t.type = 'INTERVIEW_GENERATION'
                  and t.status = 'PROCESSING'
                  and t.available_at <= ?
                  and (t.lease_until is null or t.lease_until <= ?)
                order by t.available_at, t.created_at, t.id
                limit 1 for update
                """,
                (rs, n) -> new Claim(
                        rs.getString(1), rs.getLong(2), rs.getLong(3),
                        UUID.randomUUID().toString(),
                        rs.getLong(4), 0, rs.getInt(5)
                ),
                utc(now), utc(now)
        );

        if (rows.isEmpty()) return null;
        Claim old = rows.getFirst();

        jdbc.update("""
                update ai_task_runs
                set status = 'FAILED', error_code = 'LEASE_EXPIRED', finished_at = ?
                where task_id = ? and status = 'PROCESSING'
                """, utc(now), old.taskId());

        if (old.attempt() >= 3 || old.fence() == Long.MAX_VALUE) {
            finish(old.taskId(), "FAILED", "LEASE_EXPIRED", now);
            return null;
        }

        jdbc.update("""
                update ai_tasks
                set lease_owner = ?, lease_version = lease_version + 1,
                    lease_until = ?
                where id = ?
                """, old.owner(), utc(now.plusSeconds(120)), old.taskId());

        jdbc.update("""
                update interview_generation_jobs
                set attempts = attempts + 1 where task_id = ?
                """, old.taskId());

        long run = insert("""
                insert into ai_task_runs(
                    task_id, run_no, lease_version, status,
                    provider, model, started_at
                )
                select task_id, ?, ?, 'PROCESSING', 'OPENAI', model, ?
                from interview_generation_jobs where task_id = ?
                """,
                old.attempt() + 1, old.fence() + 1, utc(now), old.taskId()
        );

        jdbc.update("""
                update interview_generation_control
                set next_call_at = ? where id = 1
                """, utc(now.plusSeconds(1)));

        return new Claim(
                old.taskId(), old.userId(), old.companyId(), old.owner(),
                old.fence() + 1, run, old.attempt() + 1
        );
    }

    // users → 준비 기업/companies → 정책·공시 → task 순서로 잠근다.
    public Lease load(Claim claim, Instant now) {
        if (!validOwner(claim, now)) return null;

        Preparation prep = reads.preparation(
                claim.userId(), claim.companyId(), true
        );

        if (!prep.active()) {
            throw new Failure("COMPANY_INACTIVE", false);
        }

        var rows = jdbc.query("""
                select t.context_id, t.policy_version_id,
                       v.prompt_template, v.status,
                       j.model, j.prompt_hash, j.sources_json, j.sections_json,
                       x.job_id_snapshot, x.job_name_snapshot,
                       x.job_is_custom, x.invalidated_at
                from ai_tasks t
                join interview_generation_jobs j on j.task_id = t.id
                join ai_contexts x on x.id = t.context_id
                join interview_policy_versions v on v.id = t.policy_version_id
                where t.id = ?
                """,
                (rs, n) -> {
                    if (rs.getObject(12) != null
                            || !List.of("PUBLISHED", "SUPERSEDED")
                            .contains(rs.getString(4))) {
                        throw new Failure("CONTEXT_UNAVAILABLE", false);
                    }

                    String context = rs.getString(1);

                    List<Source> pinned = mapper.readerForListOf(Source.class)
                            .readValue(rs.getString(7));

                    List<Section> sections = mapper.readerForListOf(Section.class)
                            .readValue(rs.getString(8));

                    List<Material> materials = sources.materials(
                            claim.userId(), context
                    );

                    if (materials.size() != count("""
                            select count(*) from ai_context_materials
                            where context_id = ?
                            """, context)) {
                        throw new Failure("CONTEXT_UNAVAILABLE", false);
                    }

                    return new Lease(
                            claim.taskId(), claim.userId(), claim.companyId(),
                            context, rs.getLong(2), claim.owner(),
                            claim.fence(), claim.runId(), claim.attempt(),
                            rs.getString(5), rs.getString(6), rs.getString(3),
                            new Input(
                                    prep.companyName(),
                                    new Job(
                                            rs.getObject(9, Long.class),
                                            rs.getString(10), rs.getBoolean(11)
                                    ),
                                    pinned, sections, materials,
                                    previous(claim.userId(), claim.companyId())
                            )
                    );
                },
                claim.taskId()
        );

        if (rows.isEmpty()) return null;
        Lease lease = rows.getFirst();

        String policyStatus = jdbc.queryForObject("""
                select status from interview_policy_versions
                where id = ? for update
                """, String.class, lease.policyVersionId());

        if (!List.of("PUBLISHED", "SUPERSEDED").contains(policyStatus)) {
            throw new Failure("CONTEXT_UNAVAILABLE", false);
        }

        jdbc.queryForObject("""
                select id from companies where id = ? for update
                """, Long.class, claim.companyId());

        sources.validate(claim.companyId(), lease.input().sources());

        if (!owned(claim, now, true)) return null;
        return lease;
    }

    private boolean validOwner(Claim claim, Instant now) {
        var users = jdbc.query("""
                select status from users where id = ? for update
                """, (rs, n) -> rs.getString(1), claim.userId());

        if (users.isEmpty() || !"ACTIVE".equals(users.getFirst())) {
            throw new Failure("CONTEXT_UNAVAILABLE", false);
        }

        return owned(claim, now, false);
    }

    public boolean owned(Claim claim, Instant now, boolean lock) {
        return !jdbc.query("""
                select id from ai_tasks
                where id = ? and status = 'PROCESSING'
                  and lease_owner = ? and lease_version = ? and lease_until > ?
                """ + (lock ? " for update" : ""),
                (rs, n) -> rs.getString(1),
                claim.taskId(), claim.owner(), claim.fence(), utc(now)
        ).isEmpty();
    }

    public void save(Claim claim, Output output, Instant now) {
        Lease current = load(claim, now);
        if (current == null) return;

        Generated checked = InterviewGeneration.validate(
                mapper.valueToTree(output.generated()),
                current.input()
        );

        String fingerprint = InterviewGeneration.fingerprint(checked.question());

        if (count("""
                select count(*) from interview_question_keys
                where user_id = ? and company_id = ? and question_fingerprint = ?
                """, claim.userId(), claim.companyId(), fingerprint) != 0) {
            throw InterviewGeneration.invalidOutput();
        }

        long question;

        try {
            question = insert("""
                    insert into interview_question_keys(
                        user_id, company_id, question_fingerprint, created_at
                    )
                    values (?, ?, ?, ?)
                    """,
                    claim.userId(), claim.companyId(), fingerprint, utc(now)
            );
        } catch (DuplicateKeyException ex) {
            throw InterviewGeneration.invalidOutput();
        }

        jdbc.update("""
                insert into interview_questions(
                    id, user_id, company_id, generation_task_id, context_id,
                    generation_policy_version_id, question_text,
                    question_fingerprint, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                question, claim.userId(), claim.companyId(), claim.taskId(),
                current.contextId(), current.policyVersionId(),
                checked.question(), fingerprint, utc(now)
        );

        for (Quote quote : checked.evidence()) {
            jdbc.update("""
                    insert into interview_question_evidence(
                        question_id, user_id, company_id, section_id, quotation
                    )
                    values (?, ?, ?, ?, ?)
                    """,
                    question, claim.userId(), claim.companyId(),
                    quote.sectionId(), quote.quote()
            );
        }

        run(claim, "SUCCEEDED", null, output.metadata(), now);
        finish(claim.taskId(), "SUCCEEDED", null, now);
    }

    public void fail(Claim claim, Failure failure, Instant now) {
        jdbc.queryForObject("""
                select id from interview_generation_control
                where id = 1 for update
                """, Integer.class);

        if (!owned(claim, now, true)) return;

        run(claim, "FAILED", failure.code(), failure.metadata(), now);

        long delay = Math.max(
                failure.retryAfter(),
                (1L << claim.attempt()) + ThreadLocalRandom.current().nextLong(2)
        );

        if (failure.retryable() && claim.attempt() < 3) {
            jdbc.update("""
                    update ai_tasks
                    set available_at = ?, last_error_code = ?,
                        lease_owner = null, lease_until = null
                    where id = ?
                    """,
                    utc(now.plusSeconds(delay)), failure.code(), claim.taskId()
            );
        } else {
            finish(claim.taskId(), "FAILED", failure.code(), now);
        }

        jdbc.update("""
                update interview_generation_control
                set next_call_at = greatest(next_call_at, ?)
                where id = 1
                """, utc(now.plusSeconds(Math.max(1, failure.retryAfter()))));
    }

    private void finish(
            String task,
            String status,
            String code,
            Instant now
    ) {
        jdbc.update("""
                update ai_tasks
                set status = ?, last_error_code = ?, completed_at = ?,
                    lease_owner = null, lease_until = null
                where id = ?
                """, status, code, utc(now), task);

        jdbc.update("""
                update interview_generation_jobs
                set active_user_id = null, active_company_id = null
                where task_id = ?
                """, task);
    }

    private void run(
            Claim claim,
            String state,
            String code,
            Metadata metadata,
            Instant now
    ) {
        jdbc.update("""
                update ai_task_runs
                set status = ?, error_code = ?, finished_at = ?,
                    provider_request_id = ?, model = coalesce(?, model),
                    input_tokens = ?, output_tokens = ?
                where id = ? and task_id = ? and lease_version = ?
                  and status = 'PROCESSING'
                """,
                state, code, utc(now),
                metadata == null ? null : metadata.requestId(),
                metadata == null ? null : metadata.model(),
                metadata == null ? null : metadata.inputTokens(),
                metadata == null ? null : metadata.outputTokens(),
                claim.runId(), claim.taskId(), claim.fence()
        );
    }

    private long count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();

        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS
            );

            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }

            return statement;
        }, key);

        return InterviewPolicy.id(
                Objects.requireNonNull(key.getKey()).longValue()
        );
    }

    private static LocalDateTime utc(Instant now) {
        return LocalDateTime.ofInstant(now, ZoneOffset.UTC);
    }
}