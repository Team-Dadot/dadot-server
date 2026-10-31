package com.dadot.server.domain.interview.repository;

import com.dadot.server.domain.interview.model.InterviewEvaluation.*;
import com.dadot.server.domain.interview.model.InterviewGeneration.Failure;
import com.dadot.server.domain.interview.model.InterviewGeneration.Metadata;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Repository
@RequiredArgsConstructor
public class InterviewEvaluationJobRepository {
    private final JdbcTemplate jdbc;
    private final InterviewEvaluationInputRepository inputs;

    public Claim claim(Instant now) {
        LocalDateTime next = jdbc.queryForObject("""
                select next_call_at from interview_evaluation_control where id = 1 for update
                """, LocalDateTime.class);
        if (Objects.requireNonNull(next).isAfter(utc(now)) || count("""
                select count(*) from ai_tasks where type = 'INTERVIEW_EVALUATION'
                and status = 'PROCESSING' and lease_until > ?
                """, utc(now)) != 0) return null;
        var candidates = jdbc.query("""
                select t.id, t.lease_version, j.attempts, j.run_no
                from ai_tasks t join interview_evaluation_jobs j on j.task_id = t.id
                where t.type = 'INTERVIEW_EVALUATION' and t.status = 'PROCESSING' and t.available_at <= ?
                    and (t.lease_until is null or t.lease_until <= ?)
                order by t.available_at, t.created_at, t.id limit 1 for update
                """, (rs, n) -> new Claim(rs.getString(1), UUID.randomUUID().toString(), rs.getLong(2),
                0, rs.getInt(4), rs.getInt(3)), utc(now), utc(now));
        if (candidates.isEmpty()) return null;
        Claim old = candidates.getFirst();
        jdbc.update("""
                update ai_task_runs set status = 'FAILED', error_code = 'LEASE_EXPIRED', finished_at = ?
                where task_id = ? and status = 'PROCESSING'
                """, utc(now), old.taskId());
        if (old.attempt() >= 3 || old.fence() == Long.MAX_VALUE || old.runNo() == Integer.MAX_VALUE) {
            finish(old.taskId(), "FAILED", "LEASE_EXPIRED", now);
            return null;
        }
        Claim nextClaim = new Claim(old.taskId(), old.owner(), old.fence() + 1, 0, old.runNo() + 1, old.attempt() + 1);
        jdbc.update("""
                update ai_tasks set lease_owner = ?, lease_version = ?, lease_until = ? where id = ?
                """, nextClaim.owner(), nextClaim.fence(), utc(now.plusSeconds(120)), old.taskId());
        jdbc.update("update interview_evaluation_jobs set attempts = ?, run_no = ? where task_id = ?",
                nextClaim.attempt(), nextClaim.runNo(), old.taskId());
        long run = insert("""
                insert into ai_task_runs(task_id, run_no, lease_version, provider, model, started_at)
                select task_id, ?, ?, 'OPENAI', model, ? from interview_evaluation_jobs where task_id = ?
                """, nextClaim.runNo(), nextClaim.fence(), utc(now), old.taskId());
        jdbc.update("update interview_evaluation_control set next_call_at = ? where id = 1", utc(now.plusSeconds(1)));
        return new Claim(old.taskId(), nextClaim.owner(), nextClaim.fence(), run, nextClaim.runNo(), nextClaim.attempt());
    }

    public Lease load(Claim claim, Instant now) {
        if (!owned(claim, now, false)) return null;
        var input = inputs.prepare(claim.taskId(), now);
        if (!owned(claim, now, true)) return null;
        return new Lease(claim, input.model(), input.promptHash(), input.rules(), input.input());
    }

    public boolean owned(Claim claim, Instant now, boolean lock) {
        return !jdbc.query("""
                select id from ai_tasks where id = ? and status = 'PROCESSING'
                    and lease_owner = ? and lease_version = ? and lease_until > ?
                """ + (lock ? " for update" : ""), (rs, n) -> rs.getString(1),
                claim.taskId(), claim.owner(), claim.fence(), utc(now)).isEmpty();
    }

    public void success(Claim claim, Metadata metadata, Instant now) {
        run(claim, "SUCCEEDED", null, metadata, now);
        finish(claim.taskId(), "SUCCEEDED", null, now);
    }

    // claim과 동일하게 control → task 순서. 사용자·자료 잠금을 추가로 요구하지 않는다.
    public void fail(Claim claim, Failure failure, Instant now) {
        jdbc.queryForObject("select id from interview_evaluation_control where id = 1 for update", Integer.class);
        if (!owned(claim, now, true)) return;
        run(claim, "FAILED", failure.code(), failure.metadata(), now);
        long delay = Math.max(failure.retryAfter(), (1L << claim.attempt()) + ThreadLocalRandom.current().nextLong(2));
        if (failure.retryable() && claim.attempt() < 3) {
            jdbc.update("""
                    update ai_tasks set available_at = ?, last_error_code = ?, lease_owner = null, lease_until = null
                    where id = ?
                    """, utc(now.plusSeconds(delay)), failure.code(), claim.taskId());
        } else {
            finish(claim.taskId(), "CONTEXT_UNAVAILABLE".equals(failure.code()) ? "CANCELLED" : "FAILED", failure.code(), now);
        }
        jdbc.update("""
                update interview_evaluation_control set next_call_at = greatest(next_call_at, ?) where id = 1
                """, utc(now.plusSeconds(Math.max(1, failure.retryAfter()))));
    }

    public void requeue(String task, Instant now) {
        if (jdbc.update("""
                update ai_tasks set status = 'PROCESSING', completed_at = null, last_error_code = null,
                    available_at = ?, lease_owner = null, lease_until = null, lease_version = lease_version + 1
                where id = ? and status = 'FAILED' and lease_version < ?
                """, utc(now), task, Long.MAX_VALUE) != 1) throw InterviewPolicy.transition();
        jdbc.update("update interview_evaluation_jobs set attempts = 0 where task_id = ?", task);
    }

    private void finish(String task, String status, String code, Instant now) {
        jdbc.update("""
                update ai_tasks set status = ?, last_error_code = ?, completed_at = ?,
                    lease_owner = null, lease_until = null where id = ?
                """, status, code, utc(now), task);
    }

    private void run(Claim claim, String status, String code, Metadata metadata, Instant now) {
        if (jdbc.update("""
                update ai_task_runs set status = ?, error_code = ?, finished_at = ?, provider_request_id = ?,
                    model = coalesce(?, model), input_tokens = ?, output_tokens = ?
                where id = ? and task_id = ? and lease_version = ? and status = 'PROCESSING'
                """, status, code, utc(now), metadata == null ? null : metadata.requestId(),
                metadata == null ? null : metadata.model(), metadata == null ? null : metadata.inputTokens(),
                metadata == null ? null : metadata.outputTokens(), claim.runId(), claim.taskId(), claim.fence()) != 1) {
            throw new IllegalStateException("Invalid evaluation run state");
        }
    }

    private long count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }

    private long insert(String sql, Object... args) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(c -> {
            var statement = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        return InterviewPolicy.id(Objects.requireNonNull(holder.getKey()).longValue());
    }

    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}