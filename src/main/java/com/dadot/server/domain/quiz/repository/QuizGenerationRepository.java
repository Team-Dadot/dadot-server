package com.dadot.server.domain.quiz.repository;

import com.dadot.server.domain.quiz.model.QuizBank;
import com.dadot.server.domain.quiz.model.QuizGeneration;
import com.dadot.server.domain.quiz.model.QuizGeneration.*;
import com.dadot.server.domain.quiz.support.QuizGenerationProperties;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
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
public class QuizGenerationRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final QuizGenerationProperties properties;
    private final QuizBankRepository bank;
    private final QuizGenerationSourceRepository sources;
    private final QuizGenerationDraftRepository drafts;

    public Accepted replay(String scope, String operation, String key, String body, Instant now) {
        var rows = jdbc.query("""
                select body_hmac, task_id, expires_at from api_idempotency_requests
                where scope_hash = ? and operation = ? and key_hash = ?
                """, (rs, n) -> {
            if (!body.equals(rs.getString(1))) throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
            if (!instant(rs, "expires_at").isAfter(now)) throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_EXPIRED);
            return new Accepted(rs.getString(2), "PROCESSING");
        }, scope, operation, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Accepted create(long actor, long company, Request request, Plan plan, String scope,
                           String operation, String key, String body, String promptHash, Instant now) {
        // 접수와 claim은 같은 제어 행으로 직렬화한다. 기업 행은 호출자가 먼저 잠근다.
        jdbc.queryForObject("select id from quiz_generation_control where id = 1 for update", Integer.class);
        if (count("select count(*) from quiz_generation_jobs where active_company_id = ?", company) != 0
                || count("select count(*) from ai_tasks where type = 'QUIZ_GENERATION' and status = 'PROCESSING'") >= 20) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                insert into ai_tasks(id, user_id, company_id, type, request_key_hash, request_body_hmac,
                                     request_parameters, available_at, created_at)
                values (?, ?, ?, 'QUIZ_GENERATION', ?, ?, ?, ?, ?)
                """, id, actor, company, key, body, json(request), utc(now), utc(now));
        int perBatch = Math.min(5, (request.questionCount() + plan.batches().size() - 1) / plan.batches().size() + 1);
        jdbc.update("""
                insert into quiz_generation_jobs(task_id, company_id, active_company_id, requested_count,
                    sources_json, model, prompt_hash, max_output_tokens, questions_per_batch, total_batches)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, company, company, request.questionCount(), json(plan.sources()), properties.model(),
                promptHash, properties.maxOutputTokens(), perBatch, plan.batches().size());
        var batches = plan.batches();
        jdbc.batchUpdate("insert into quiz_generation_batches(task_id, batch_no, input_json) values (?, ?, ?)",
                java.util.stream.IntStream.range(0, batches.size()).boxed().toList(), 100, (s, i) -> {
                    s.setString(1, id); s.setInt(2, i); s.setString(3, json(batches.get(i)));
                });
        var accepted = new Accepted(id, "PROCESSING");
        jdbc.update("""
                insert into api_idempotency_requests(user_id, task_id, scope_hash, operation, key_hash,
                    body_hmac, status, response_http_status, response_payload, created_at, expires_at)
                values (?, ?, ?, ?, ?, ?, 'SUCCEEDED', 202, ?, ?, ?)
                """, actor, id, scope, operation, key, body, json(accepted), utc(now), utc(now.plusSeconds(86400)));
        return accepted;
    }

    public Result result(String id) {
        var rows = jdbc.query("""
                select t.id, t.status, t.company_id, t.last_error_code, t.created_at, t.completed_at,
                       j.requested_count, j.next_batch, j.total_batches, j.rejected_count,
                       j.created_count, j.sources_json
                from ai_tasks t join quiz_generation_jobs j on j.task_id = t.id
                where t.id = ? and t.type = 'QUIZ_GENERATION'
                """, (rs, n) -> {
            String state = rs.getString("status");
            int requested = rs.getInt("requested_count"), created = rs.getInt("created_count");
            var ids = jdbc.query("""
                    select selected_question_id from quiz_generation_candidates
                    where task_id = ? and selected_question_id is not null order by candidate_no, batch_no
                    """, (r, i) -> r.getLong(1), id);
            return new Result(id, state, rs.getLong("company_id"), requested, rs.getInt("next_batch"),
                    rs.getInt("total_batches"), (int) count("select count(*) from quiz_generation_candidates where task_id = ?", id),
                    rs.getInt("rejected_count"), created, "SUCCEEDED".equals(state) && created == requested,
                    "SUCCEEDED".equals(state) && created < requested ? "INSUFFICIENT_VALID_QUESTIONS" : null,
                    ids, readSources(rs.getString("sources_json")), rs.getString("last_error_code"),
                    instant(rs, "created_at"), instant(rs, "completed_at"));
        }, id);
        if (rows.isEmpty()) throw QuizBank.missing();
        return rows.getFirst();
    }

    public Lease claim(Instant now) {
        var next = jdbc.queryForObject("select next_call_at from quiz_generation_control where id = 1 for update", LocalDateTime.class);
        if (Objects.requireNonNull(next).isAfter(utc(now)) || count("""
                select count(*) from ai_tasks where type = 'QUIZ_GENERATION'
                and status = 'PROCESSING' and lease_until > ?
                """, utc(now)) > 0) return null;
        var tasks = jdbc.query("""
                select t.id, t.company_id, t.user_id, t.lease_version, j.next_batch, j.attempt,
                       j.requested_count, j.total_batches, j.questions_per_batch, j.max_output_tokens,
                       j.model, j.prompt_hash
                from ai_tasks t join quiz_generation_jobs j on j.task_id = t.id
                where t.type = 'QUIZ_GENERATION' and t.status = 'PROCESSING' and t.available_at <= ?
                  and (t.lease_until is null or t.lease_until <= ?)
                order by t.available_at, t.created_at, t.id limit 1 for update
                """, (rs, n) -> new Pending(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10),
                rs.getString(11), rs.getString(12)), utc(now), utc(now));
        if (tasks.isEmpty()) return null;
        var t = tasks.getFirst();
        jdbc.update("""
                update ai_task_runs set status = 'FAILED', error_code = 'LEASE_EXPIRED', finished_at = ?
                where task_id = ? and status = 'PROCESSING'
                """, utc(now), t.id());
        if (t.attempt() >= 3 || t.fence() == Long.MAX_VALUE) {
            finish(t.id(), 0, "LEASE_EXPIRED", now);
            return null;
        }
        String owner = UUID.randomUUID().toString();
        long fence = t.fence() + 1;
        jdbc.update("""
                update ai_tasks set lease_owner = ?, lease_version = ?, lease_until = ? where id = ?
                """, owner, fence, utc(now.plusSeconds(120)), t.id());
        jdbc.update("update quiz_generation_jobs set attempt = attempt + 1 where task_id = ?", t.id());
        jdbc.update("update quiz_generation_control set next_call_at = ? where id = 1", utc(now.plusSeconds(1)));
        int run = (int) count("select coalesce(max(run_no), 0) + 1 from ai_task_runs where task_id = ?", t.id());
        long runId = insert("""
                insert into ai_task_runs(task_id, run_no, lease_version, provider, model, started_at, batch_no)
                values (?, ?, ?, 'OPENAI', ?, ?, ?)
                """, t.id(), run, fence, t.model(), utc(now), t.batch());
        String input = jdbc.queryForObject("select input_json from quiz_generation_batches where task_id = ? and batch_no = ?",
                String.class, t.id(), t.batch());
        return new Lease(t.id(), t.company(), t.user(), owner, fence, runId, t.batch(), t.attempt() + 1,
                t.requested(), t.total(), t.perBatch(), t.outputTokens(), t.model(), t.hash(),
                mapper.readValue(input, Input.class));
    }

    public boolean validateBeforeCall(Lease lease, Instant now) {
        bank.lockCompany(lease.companyId());
        if (!owns(lease, now)) return false;
        requireCreator(lease.userId());
        sources.validate(lease.companyId(), pinnedSources(lease.taskId()));
        return true;
    }

    public void save(Lease lease, Validated output, Metadata metadata, Instant now) {
        bank.lockCompany(lease.companyId());
        if (!owns(lease, now)) return;
        requireCreator(lease.userId());
        sources.validate(lease.companyId(), pinnedSources(lease.taskId()));
        int rejected = output.rejectedCount();
        int ordinal = 0;
        for (var content : output.questions()) {
            String fingerprint = QuizGeneration.fingerprint(content.question());
            if (count("select count(*) from quiz_generation_candidates where task_id = ? and question_hash = ?",
                    lease.taskId(), fingerprint) > 0) {
                rejected++;
                continue;
            }
            bank.validateReferences(content);
            jdbc.update("""
                    insert into quiz_generation_candidates(task_id, company_id, batch_no, candidate_no,
                        question_hash, content_json) values (?, ?, ?, ?, ?, ?)
                    """, lease.taskId(), lease.companyId(), lease.batchNo(), ordinal++, fingerprint, json(content));
        }
        completeRun(lease, metadata, null, now);
        jdbc.update("""
                update quiz_generation_jobs set next_batch = next_batch + 1, attempt = 0,
                    rejected_count = rejected_count + ? where task_id = ?
                """, rejected, lease.taskId());
        if (lease.batchNo() + 1 == lease.batchCount()) {
            int created = drafts.saveDrafts(lease, now);
            finish(lease.taskId(), created, created == 0 ? "NO_VALID_QUESTIONS" : null, now);
        } else {
            jdbc.update("""
                    update ai_tasks set lease_owner = null, lease_until = null, last_error_code = null,
                        available_at = ? where id = ?
                    """, utc(now), lease.taskId());
        }
    }

    public void fail(Lease lease, Failure failure, Instant now) {
        // claim과 같은 잠금 순서다. 공급자의 대기 시간은 다른 기업의 다음 호출에도 적용한다.
        jdbc.queryForObject("select id from quiz_generation_control where id = 1 for update", Integer.class);
        if (!owns(lease, now)) return;
        completeRun(lease, failure.metadata(), failure.code(), now);
        if (failure.retryable() && lease.attempt() < 3 && failure.retryAfterSeconds() <= 3600) {
            long delay = Math.max(failure.retryAfterSeconds(), 2L << lease.attempt())
                    + ThreadLocalRandom.current().nextLong(1, 4);
            jdbc.update("update quiz_generation_control set next_call_at = greatest(next_call_at, ?) where id = 1",
                    utc(now.plusSeconds(delay)));
            jdbc.update("""
                    update ai_tasks set lease_owner = null, lease_until = null, last_error_code = ?, available_at = ?
                    where id = ?
                    """, failure.code(), utc(now.plusSeconds(delay)), lease.taskId());
        } else {
            finish(lease.taskId(), 0, failure.code(), now);
        }
    }

    public void cleanup(Instant now) {
        jdbc.update("""
                update api_idempotency_requests set response_payload = null
                where operation like 'POST /v1/admin/companies/%/quiz-generation-tasks'
                  and expires_at <= ? and response_payload is not null
                """, utc(now));
    }

    private boolean owns(Lease lease, Instant now) {
        return !jdbc.query("""
                select id from ai_tasks where id = ? and type = 'QUIZ_GENERATION'
                  and status = 'PROCESSING' and lease_owner = ? and lease_version = ? and lease_until > ? for update
                """, (rs, n) -> rs.getString(1), lease.taskId(), lease.owner(), lease.fence(), utc(now)).isEmpty();
    }

    private void finish(String id, int created, String error, Instant now) {
        jdbc.update("""
                update ai_tasks set status = ?, last_error_code = ?, completed_at = ?,
                    lease_owner = null, lease_until = null where id = ?
                """, error == null ? "SUCCEEDED" : "FAILED", error, utc(now), id);
        jdbc.update("update quiz_generation_jobs set active_company_id = null, created_count = ? where task_id = ?", created, id);
    }

    private void completeRun(Lease lease, Metadata metadata, String error, Instant now) {
        jdbc.update("""
                update ai_task_runs set status = ?, error_code = ?, finished_at = ?, provider_request_id = ?,
                    model = coalesce(?, model), input_tokens = ?, output_tokens = ?
                where id = ? and task_id = ? and lease_version = ? and status = 'PROCESSING'
                """, error == null ? "SUCCEEDED" : "FAILED", error, utc(now),
                metadata == null ? null : metadata.requestId(), metadata == null ? null : metadata.model(),
                metadata == null ? null : metadata.inputTokens(), metadata == null ? null : metadata.outputTokens(),
                lease.runId(), lease.taskId(), lease.fence());
    }

    private void requireCreator(long user) {
        if (count("select count(*) from users where id = ? and role = 'ADMIN' and status = 'ACTIVE'", user) != 1) {
            throw new Failure("AUTHORIZATION_CHANGED", false);
        }
    }
    private List<Source> pinnedSources(String id) {
        return readSources(jdbc.queryForObject("select sources_json from quiz_generation_jobs where task_id = ?", String.class, id));
    }
    private List<Source> readSources(String value) {
        return mapper.readValue(value, new TypeReference<List<Source>>() {});
    }
    private String json(Object value) { return mapper.writeValueAsString(value); }
    private long count(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(c -> {
            var s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            return s;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
    private record Pending(String id, long company, long user, long fence, int batch, int attempt,
                           int requested, int total, int perBatch, int outputTokens, String model, String hash) {}
}