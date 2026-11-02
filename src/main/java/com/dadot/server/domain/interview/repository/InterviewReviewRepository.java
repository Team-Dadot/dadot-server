package com.dadot.server.domain.interview.repository;

import com.dadot.server.domain.interview.model.InterviewGeneration.Attempt;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.interview.model.InterviewReview.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Repository
@RequiredArgsConstructor
public class InterviewReviewRepository {
    private final JdbcTemplate jdbc;

    public void requireCompany(long user, long company) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from user_companies uc
                where uc.user_id = ? and uc.company_id = ? and (
                    uc.active_slot is not null
                    or exists(select 1 from interview_questions q
                        where q.user_id = uc.user_id and q.company_id = uc.company_id)
                    or exists(select 1 from quiz_answers a
                        where a.user_id = uc.user_id and a.company_id = uc.company_id)))
                """, Boolean.class, user, company))) throw InterviewPolicy.missing();
    }

    public long questionCompany(long user, long question, boolean lock, boolean includeDeleted) {
        return jdbc.query("""
                select company_id from interview_question_keys where id = ? and user_id = ?
                """ + (includeDeleted ? "" : " and deleted_at is null") + (lock ? " for update" : ""),
                (rs, n) -> rs.getLong(1), question, user).stream().findFirst().orElseThrow(InterviewPolicy::missing);
    }

    public long questionForAttempt(long user, long attempt) {
        return jdbc.query("select question_id from interview_submissions where id = ? and user_id = ?",
                (rs, n) -> rs.getLong(1), attempt, user).stream().findFirst().orElseThrow(InterviewPolicy::missing);
    }

    public Page<Question> questions(long user, long company, Paging paging) {
        long total = count("select count(*) from interview_questions where user_id = ? and company_id = ?", user, company);
        if (paging.offset() >= total) return Page.of(List.of(), paging, total);
        var content = jdbc.query("""
                select q.id, q.question_text, q.created_at,
                    exists(select 1 from interview_drafts d where d.question_id = q.id),
                    a.id, s.question_id, s.attempt_number, a.answer_text, s.submitted_at, t.id, t.status,
                    e.total_score,
                    (select count(*) from interview_submissions used where used.question_id = q.id)
                from interview_questions q
                left join interview_submissions s on s.question_id = q.id and s.attempt_number = (
                    select max(s2.attempt_number) from interview_submissions s2
                    join interview_attempts a2 on a2.id = s2.id where s2.question_id = q.id)
                left join interview_attempts a on a.id = s.id
                left join ai_tasks t on t.source_attempt_id = a.id and t.type = 'INTERVIEW_EVALUATION'
                left join interview_evaluations e on e.attempt_id = a.id and e.task_id = t.id
                where q.user_id = ? and q.company_id = ?
                order by q.created_at desc, q.id desc limit ? offset ?
                """, (rs, n) -> {
            Attempt latest = rs.getObject(5) == null ? null : attempt(rs, 5);
            Integer score = latest != null && "SUCCEEDED".equals(latest.evaluationStatus()) ? rs.getObject(12, Integer.class) : null;
            if (latest != null && "SUCCEEDED".equals(latest.evaluationStatus()) && score == null) {
                throw new IllegalStateException("Successful interview attempt has no evaluation");
            }
            int used = rs.getInt(13);
            return new Question(rs.getLong(1), rs.getString(2), instant(rs, 3), rs.getBoolean(4), latest,
                    score, score == null ? null : score >= 80, used, Math.max(0, 3 - used),
                    score == null ? null : score >= 80 ? "READY" : "NEEDS_IMPROVEMENT");
        }, user, company, paging.size(), paging.offset());
        return Page.of(content, paging, total);
    }

    public Page<Attempt> attempts(long user, long question, Paging paging) {
        questionCompany(user, question, false, false);
        long total = count("""
                select count(*) from interview_attempts a join interview_submissions s on s.id = a.id
                where a.user_id = ? and s.question_id = ?
                """, user, question);
        if (paging.offset() >= total) return Page.of(List.of(), paging, total);
        var content = jdbc.query("""
                select a.id, s.question_id, s.attempt_number, a.answer_text, s.submitted_at, t.id, t.status
                from interview_attempts a join interview_submissions s on s.id = a.id
                left join ai_tasks t on t.source_attempt_id = a.id and t.type = 'INTERVIEW_EVALUATION'
                where a.user_id = ? and s.question_id = ?
                order by s.attempt_number desc limit ? offset ?
                """, (rs, n) -> attempt(rs, 1), user, question, paging.size(), paging.offset());
        return Page.of(content, paging, total);
    }

    // 호출자는 users → auth_sessions → 준비 기업/companies → 질문 키 순서로 잠근다.
    public void deleteQuestion(long user, long question, Instant now) {
        var generation = jdbc.query("""
                select generation_task_id, context_id from interview_questions where id = ? and user_id = ?
                """, (rs, n) -> new TaskRef(rs.getString(1), rs.getString(2)), question, user);
        if (generation.isEmpty()) return; // 보존된 본인 질문 키로 삭제 재호출을 확인했다.
        var contexts = new ArrayList<>(jdbc.query("""
                select a.context_id from interview_attempts a join interview_submissions s on s.id = a.id
                where a.user_id = ? and s.question_id = ?
                """, (rs, n) -> rs.getString(1), user, question));
        contexts.add(generation.getFirst().context());
        List<TaskRef> tasks = lockTasks(user, question, null, generation.getFirst().task());
        cancel(tasks, user, now);
        deleteEvaluations(user, question, null);
        jdbc.update("delete from interview_drafts where question_id = ? and user_id = ?", question, user);
        jdbc.update("delete from interview_question_evidence where question_id = ? and user_id = ?", question, user);
        jdbc.update("delete from interview_questions where id = ? and user_id = ?", question, user);
        removeTasks(tasks, user);
        jdbc.update("""
                delete a from interview_attempts a join interview_submissions s on s.id = a.id
                where a.user_id = ? and s.question_id = ?
                """, user, question);
        jdbc.update("update interview_question_keys set deleted_at = coalesce(deleted_at, ?) where id = ? and user_id = ?",
                utc(now), question, user);
        contexts.stream().distinct().forEach(context -> removeUnusedContext(user, context));
    }

    public void deleteAttempt(long user, long question, long attempt, Instant now) {
        var contexts = jdbc.query("select context_id from interview_attempts where id = ? and user_id = ?",
                (rs, n) -> rs.getString(1), attempt, user);
        if (contexts.isEmpty()) return; // 제출 소비 원장은 삭제하지 않는다.
        List<TaskRef> tasks = lockTasks(user, question, attempt, null);
        cancel(tasks, user, now);
        deleteEvaluations(user, question, attempt);
        removeTasks(tasks, user);
        jdbc.update("delete from interview_attempts where id = ? and user_id = ?", attempt, user);
        contexts.forEach(context -> removeUnusedContext(user, context));
    }

    private record TaskRef(String task, String context) {}

    private List<TaskRef> lockTasks(long user, long question, Long attempt, String generation) {
        return jdbc.query("""
                select t.id, t.context_id from ai_tasks t
                where t.user_id = ? and (
                    (t.type = 'INTERVIEW_EVALUATION' and exists(
                        select 1 from interview_submissions s where s.id = t.source_attempt_id
                        and s.user_id = ? and s.question_id = ? and (? is null or s.id = ?)))
                    or (t.type = 'INTERVIEW_GENERATION' and t.id = ?))
                order by t.id for update
                """, (rs, n) -> new TaskRef(rs.getString(1), rs.getString(2)), user, user, question, attempt, attempt, generation);
    }

    private void cancel(List<TaskRef> tasks, long user, Instant now) {
        for (TaskRef task : tasks) jdbc.update("""
                update ai_tasks set status = 'CANCELLED', last_error_code = 'CONTEXT_UNAVAILABLE',
                    completed_at = ?, lease_owner = null, lease_until = null where id = ? and user_id = ?
                """, utc(now), task.task(), user);
    }

    private void deleteEvaluations(long user, long question, Long attempt) {
        var ids = jdbc.query("""
                select e.id from interview_evaluations e join interview_submissions s on s.id = e.attempt_id
                where e.user_id = ? and s.question_id = ? and (? is null or s.id = ?)
                order by e.id
                """, (rs, n) -> rs.getLong(1), user, question, attempt, attempt);
        for (long id : ids) {
            jdbc.update("delete from evaluation_material_links where evaluation_id = ?", id);
            jdbc.update("delete from evaluation_scores where evaluation_id = ?", id);
            jdbc.update("delete from interview_evaluations where id = ? and user_id = ?", id, user);
        }
    }

    private void removeTasks(List<TaskRef> tasks, long user) {
        for (TaskRef task : tasks) {
            jdbc.update("update api_idempotency_requests set task_id = null where task_id = ? and user_id = ?", task.task(), user);
            jdbc.update("delete from ai_task_runs where task_id = ?", task.task());
            jdbc.update("delete from interview_generation_jobs where task_id = ? and user_id = ?", task.task(), user);
            jdbc.update("delete from interview_evaluation_jobs where task_id = ?", task.task());
            jdbc.update("delete from ai_tasks where id = ? and user_id = ?", task.task(), user);
        }
    }

    // 공유된 스냅샷이 있어도 다른 질문·답변의 입력을 삭제하지 않는다.
    private void removeUnusedContext(long user, String context) {
        if (count("""
                select (select count(*) from ai_tasks where context_id = ?)
                     + (select count(*) from interview_questions where context_id = ?)
                     + (select count(*) from interview_attempts where context_id = ?)
                """, context, context, context) != 0) return;
        jdbc.update("delete from ai_context_materials where context_id = ? and user_id = ?", context, user);
        jdbc.update("delete from ai_contexts where id = ? and user_id = ?", context, user);
    }

    private static Attempt attempt(ResultSet rs, int offset) throws SQLException {
        if (rs.getString(offset + 5) == null) throw new IllegalStateException("Interview attempt has no evaluation task");
        return new Attempt(rs.getLong(offset), rs.getLong(offset + 1), rs.getInt(offset + 2), rs.getString(offset + 3),
                instant(rs, offset + 4), rs.getString(offset + 5), rs.getString(offset + 6));
    }

    private long count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }

    private static Instant instant(ResultSet rs, int index) throws SQLException {
        return rs.getObject(index, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}