package com.dadot.server.domain.interview.repository;

import com.dadot.server.domain.interview.model.InterviewGeneration.Attempt;
import com.dadot.server.domain.interview.model.InterviewGeneration.Draft;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.interview.model.InterviewSubmission.Question;
import com.dadot.server.domain.interview.repository.InterviewSubmissionRequestStore.Key;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
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

@Repository
@RequiredArgsConstructor
public class InterviewSubmissionRepository {
    private final JdbcTemplate jdbc;
    private final InterviewPolicyRepository policies;

    public Question question(long user, long id) {
        return jdbc.query("""
                select q.id, q.company_id, x.job_id_snapshot, x.job_name_snapshot,
                       x.job_is_custom, u.personalization_version
                from interview_questions q
                join interview_question_keys k on k.id = q.id and k.deleted_at is null
                join ai_contexts x on x.id = q.context_id
                join users u on u.id = q.user_id
                where q.id = ? and q.user_id = ?
                """, (rs, row) -> new Question(rs.getLong(1), rs.getLong(2),
                        rs.getObject(3, Long.class), rs.getString(4), rs.getBoolean(5), rs.getLong(6)),
                id, user).stream().findFirst().orElseThrow(InterviewPolicy::missing);
    }

    public long lockQuestion(long user, long id) {
        return jdbc.query("""
                select draft_version from interview_question_keys
                where id = ? and user_id = ? and deleted_at is null for update
                """, (rs, row) -> rs.getLong(1), id, user)
                .stream().findFirst().orElseThrow(InterviewPolicy::missing);
    }

    public Draft draft(long question) {
        return jdbc.query("""
                select draft_text, version, saved_at from interview_drafts where question_id = ?
                """, (rs, row) -> new Draft(rs.getString(1), rs.getLong(2),
                        rs.getObject(3, LocalDateTime.class).toInstant(ZoneOffset.UTC)), question)
                .stream().findFirst().orElse(null);
    }

    public Draft saveDraft(long user, Question question, String text, long counter, Instant now) {
        if (counter >= InterviewPolicy.MAX_ID) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        long version = counter + 1;
        jdbc.update("update interview_question_keys set draft_version = ? where id = ?", version, question.id());
        jdbc.update("""
                insert into interview_drafts(question_id, user_id, company_id, draft_text, version, saved_at)
                values (?, ?, ?, ?, ?, ?)
                on duplicate key update draft_text = values(draft_text),
                    version = values(version), saved_at = values(saved_at)
                """, question.id(), user, question.companyId(), text, version, utc(now));
        return new Draft(text, version, now);
    }

    public void clearDraft(long question, Long expected) {
        if (expected != null && jdbc.update("""
                delete from interview_drafts where question_id = ? and version = ?
                """, question, expected) != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
    }

    public long evaluationPolicy(Question question) {
        long version = jdbc.query("""
                select v.id
                from interview_policies p
                join interview_policy_versions v on v.policy_id = p.id and v.version_no = p.active_version_no
                where p.type = 'EVALUATION' and v.status = 'PUBLISHED'
                  and (p.company_id is null or p.company_id = ?)
                  and (p.job_id is null or p.job_id = ?)
                order by (p.company_id is not null) desc, (p.job_id is not null) desc
                limit 1 for update
                """, (rs, row) -> rs.getLong(1), question.companyId(), question.jobId())
                .stream().findFirst().orElseThrow(InterviewPolicy::notConfigured);
        policies.requireReady(version);
        return version;
    }

    // 생성 시점의 경험이 아닌, 제출 시점의 전체 EXPERIENCE 최신 버전을 고정한다.
    public String snapshot(long user, Question question, Instant now) {
        String context = UUID.randomUUID().toString();
        jdbc.update("""
                insert into ai_contexts(id, user_id, company_id, job_id_snapshot, job_is_custom,
                    job_name_snapshot, personalization_version, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, context, user, question.companyId(), question.jobId(), question.customJob(),
                question.jobName(), question.personalizationVersion(), utc(now));
        int pinned = jdbc.update("""
                insert into ai_context_materials(context_id, material_version_id, user_id)
                select ?, v.id, m.user_id
                from personal_materials m
                join material_versions v on v.material_id = m.id and v.user_id = m.user_id
                    and v.material_type = m.type and v.version_no = m.current_version_no
                where m.user_id = ? and m.type = 'EXPERIENCE'
                """, context, user);
        if (pinned != count("select count(*) from personal_materials where user_id = ? and type = 'EXPERIENCE'", user)) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return context;
    }

    public long submit(long user, Question question, Key key, String text, String context,
                       long policy, Slots slots, Instant now) {
        var day = now.atZone(com.dadot.server.domain.interview.model.InterviewGeneration.KST).toLocalDate();
        if (!slots.includedToday()) {
            jdbc.update("""
                    insert into interview_daily_questions(user_id, company_id, business_date,
                        question_id, question_slot, admitted_at) values (?, ?, ?, ?, ?, ?)
                    """, user, question.companyId(), day, question.id(), slots.questionSlot(), utc(now));
        }
        long attempt = insert("""
                insert into interview_submissions(user_id, company_id, question_id, business_date,
                    daily_slot, attempt_number, request_key_hash, request_body_hmac, submitted_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, user, question.companyId(), question.id(), day, slots.dailySlot(), slots.attemptNumber(),
                key.hash(), key.body(), utc(now));
        jdbc.update("""
                insert into interview_attempts(id, user_id, company_id, context_id,
                    evaluation_policy_version_id, answer_text) values (?, ?, ?, ?, ?, ?)
                """, attempt, user, question.companyId(), context, policy, text);
        return attempt;
    }

    public record Slots(boolean includedToday, int questionSlot,
                        int dailySlot, int attemptNumber) {}

    public String enqueue(long user, long company, long attempt, String context,
                          long policy, Key key, Instant now) {
        String task = UUID.randomUUID().toString();
        // ai_tasks 자체가 영속 큐다. 실제 평가 워커는 다음 개발에서 이 행을 소비한다.
        jdbc.update("""
                insert into ai_tasks(id, user_id, company_id, type, context_id, policy_version_id,
                    source_attempt_id, request_key_hash, request_body_hmac, available_at, created_at)
                values (?, ?, ?, 'INTERVIEW_EVALUATION', ?, ?, ?, ?, ?, ?, ?)
                """, task, user, company, context, policy, attempt, key.hash(), key.body(), utc(now), utc(now));
        return task;
    }

    public Attempt attempt(long user, long id) {
        return jdbc.query("""
                select s.id, s.question_id, s.attempt_number, a.answer_text, s.submitted_at, t.id, t.status
                from interview_attempts a
                join interview_submissions s on s.id = a.id
                join interview_questions q on q.id = s.question_id and q.user_id = s.user_id
                join interview_question_keys k on k.id = q.id and k.deleted_at is null
                join ai_tasks t on t.source_attempt_id = a.id and t.user_id = a.user_id
                    and t.type = 'INTERVIEW_EVALUATION'
                where a.id = ? and a.user_id = ?
                """, (rs, row) -> new Attempt(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4),
                        rs.getObject(5, LocalDateTime.class).toInstant(ZoneOffset.UTC), rs.getString(6), rs.getString(7)),
                id, user).stream().findFirst().orElseThrow(InterviewPolicy::missing);
    }

    private long count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        return InterviewPolicy.id(Objects.requireNonNull(key.getKey()).longValue());
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}