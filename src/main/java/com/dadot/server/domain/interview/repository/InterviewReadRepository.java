package com.dadot.server.domain.interview.repository;

import com.dadot.server.domain.company.support.CompanyLogoUrlResolver;
import com.dadot.server.domain.interview.model.InterviewGeneration;
import com.dadot.server.domain.interview.model.InterviewGeneration.*;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

@Repository
@RequiredArgsConstructor
public class InterviewReadRepository {
    private final JdbcTemplate jdbc;
    private final CompanyLogoUrlResolver logos;

    public Preparation preparation(long user, long company, boolean lock) {
        return jdbc.query("""
                select c.name, uc.job_id, coalesce(j.name, uc.custom_job_name),
                       (uc.active_slot is not null and c.is_service_company = true),
                       u.personalization_version
                from user_companies uc
                join users u on u.id = uc.user_id
                join companies c on c.id = uc.company_id
                left join jobs j on j.id = uc.job_id
                where uc.user_id = ? and uc.company_id = ?
                """ + (lock ? " for update" : ""),
                (rs, n) -> new Preparation(
                        user, company, rs.getString(1),
                        rs.getObject(2, Long.class), rs.getString(3),
                        rs.getBoolean(4), rs.getLong(5)
                ),
                user, company
        ).stream().findFirst().orElseThrow(InterviewPolicy::missing);
    }

    public Daily daily(long user, long company, Instant now) {
        var day = now.atZone(InterviewGeneration.KST).toLocalDate();

        return Daily.of(
                now,
                Math.toIntExact(count("""
                        select count(*) from interview_submissions
                        where user_id = ? and company_id = ? and business_date = ?
                        """, user, company, day)),
                Math.toIntExact(count("""
                        select count(*) from interview_daily_questions
                        where user_id = ? and company_id = ? and business_date = ?
                        """, user, company, day))
        );
    }

    public Usage usage(
            long user,
            long company,
            long questionId,
            boolean active,
            Instant now
    ) {
        Daily daily = daily(user, company, now);

        int used = Math.toIntExact(count("""
                select count(*) from interview_submissions
                where question_id = ? and user_id = ?
                """, questionId, user));

        boolean today = count("""
                select count(*) from interview_daily_questions
                where user_id = ? and company_id = ?
                  and business_date = ? and question_id = ?
                """, user, company, daily.businessDate(), questionId) != 0;

        String blocked = daily.remaining() == 0
                || (!today && daily.questionRemaining() == 0)
                ? "DAILY_LIMIT_EXCEEDED"
                : used >= 3 ? "QUESTION_LIMIT_EXCEEDED"
                : !active ? "COMPANY_INACTIVE"
                : !validEvidence(questionId) ? "CONTENT_UNAVAILABLE"
                : processing(questionId) ? "TASK_PROCESSING"
                : null;

        return new Usage(
                daily, 3, used, Math.max(0, 3 - used),
                blocked == null, blocked, today
        );
    }

    public boolean validEvidence(long questionId) {
        return count("""
                select count(*) from interview_question_evidence
                where question_id = ?
                """, questionId) != 0
                && count("""
                select count(*)
                from interview_question_evidence e
                join disclosure_sections s on s.id = e.section_id
                join disclosure_versions v on v.id = s.disclosure_version_id
                where e.question_id = ?
                  and (
                      v.status <> 'PUBLISHED'
                      or exists(
                          select 1 from disclosures d
                          where d.corrects_disclosure_id = v.disclosure_id
                      )
                  )
                """, questionId) == 0;
    }

    private boolean processing(long questionId) {
        return count("""
                select count(*)
                from ai_tasks t
                join interview_submissions s on s.id = t.source_attempt_id
                where s.question_id = ?
                  and t.type = 'INTERVIEW_EVALUATION'
                  and t.status = 'PROCESSING'
                """, questionId) != 0;
    }

    public Long reusable(
            long user,
            long company,
            boolean drafts,
            boolean unanswered,
            Instant now
    ) {
        Daily daily = daily(user, company, now);
        if (daily.remaining() == 0) return null;

        String filter = drafts
                ? " and d.question_id is not null"
                : unanswered
                ? """
                  and not exists(
                      select 1 from interview_submissions s where s.question_id = q.id
                  )
                  """
                : """
                  and exists(
                      select 1 from interview_daily_questions x
                      where x.question_id = q.id and x.business_date = ?
                  )
                  """;

        var args = new java.util.ArrayList<Object>(List.of(user, company));

        if (!drafts && !unanswered) args.add(daily.businessDate());
        args.add(daily.questionRemaining());
        args.add(daily.businessDate());

        List<Long> candidates = jdbc.query("""
                select q.id
                from interview_questions q
                left join interview_drafts d on d.question_id = q.id
                where q.user_id = ? and q.company_id = ?
                """ + filter + """
                  and (
                      select count(*) from interview_submissions s
                      where s.question_id = q.id
                  ) < 3
                  and (
                      ? > 0
                      or exists(
                          select 1 from interview_daily_questions x
                          where x.question_id = q.id and x.business_date = ?
                      )
                  )
                  and exists(
                      select 1 from interview_question_evidence e
                      where e.question_id = q.id
                  )
                  and not exists(
                      select 1
                      from interview_question_evidence e
                      join disclosure_sections s on s.id = e.section_id
                      join disclosure_versions v on v.id = s.disclosure_version_id
                      where e.question_id = q.id
                        and (
                            v.status <> 'PUBLISHED'
                            or exists(
                                select 1 from disclosures c
                                where c.corrects_disclosure_id = v.disclosure_id
                            )
                        )
                  )
                  and not exists(
                      select 1
                      from ai_tasks t
                      join interview_submissions s on s.id = t.source_attempt_id
                      where s.question_id = q.id
                        and t.type = 'INTERVIEW_EVALUATION'
                        and t.status = 'PROCESSING'
                  )
                order by d.saved_at desc, q.created_at desc, q.id desc
                limit 1
                """,
                (rs, n) -> rs.getLong(1),
                args.toArray()
        );

        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    public Task task(long user, long company, String taskId) {
        return jdbc.query("""
                select t.id, t.status, t.last_error_code,
                       t.created_at, t.completed_at, q.id
                from ai_tasks t
                left join interview_questions q on q.generation_task_id = t.id
                where t.id = ? and t.user_id = ? and t.company_id = ?
                  and t.type = 'INTERVIEW_GENERATION'
                """,
                (rs, n) -> new Task(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3) == null ? null : new Problem(
                                rs.getString(3),
                                "면접 질문을 생성하지 못했습니다. 다시 시도해 주세요."
                        ),
                        instant(rs, 4),
                        instant(rs, 5),
                        rs.getObject(6, Long.class)
                ),
                taskId, user, company
        ).stream().findFirst().orElseThrow(InterviewPolicy::missing);
    }

    public String processingTask(long user, long company) {
        return jdbc.query("""
                select id from ai_tasks
                where user_id = ? and company_id = ?
                  and type = 'INTERVIEW_GENERATION'
                  and status = 'PROCESSING'
                order by created_at, id
                limit 1
                """,
                (rs, n) -> rs.getString(1),
                user, company
        ).stream().findFirst().orElse(null);
    }

    public Detail detail(long user, long questionId, Instant now) {
        var rows = jdbc.query("""
                select q.company_id, c.name, c.industry, c.logo_key,
                       x.job_id_snapshot, x.job_name_snapshot, x.job_is_custom,
                       q.question_text, q.created_at,
                       (uc.active_slot is not null and c.is_service_company = true)
                from interview_questions q
                join companies c on c.id = q.company_id
                join ai_contexts x on x.id = q.context_id
                join user_companies uc
                  on uc.user_id = q.user_id and uc.company_id = q.company_id
                where q.id = ? and q.user_id = ?
                """,
                (rs, n) -> new Detail(
                        questionId,
                        new Company(
                                rs.getLong(1), rs.getString(2),
                                rs.getString(3), logos.resolve(rs.getString(4))
                        ),
                        new Job(
                                rs.getObject(5, Long.class),
                                rs.getString(6), rs.getBoolean(7)
                        ),
                        rs.getString(8),
                        instant(rs, 9),
                        evidence(questionId),
                        draft(questionId),
                        latest(questionId),
                        usage(user, rs.getLong(1), questionId, rs.getBoolean(10), now)
                ),
                questionId, user
        );

        if (rows.isEmpty()) throw InterviewPolicy.missing();
        return rows.getFirst();
    }

    private List<Evidence> evidence(long id) {
        return jdbc.query("""
                select d.id, v.id, d.report_name, d.source_url, s.heading,
                       e.quotation,
                       v.status = 'SUPERSEDED'
                         or exists(
                             select 1 from disclosures c
                             where c.corrects_disclosure_id = d.id
                         )
                from interview_question_evidence e
                join disclosure_sections s on s.id = e.section_id
                join disclosure_versions v on v.id = s.disclosure_version_id
                join disclosures d on d.id = v.disclosure_id
                where e.question_id = ?
                order by e.section_id
                """,
                (rs, n) -> new Evidence(
                        rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getBoolean(7)
                ),
                id
        );
    }

    private Draft draft(long id) {
        return jdbc.query("""
                select draft_text, version, saved_at
                from interview_drafts where question_id = ?
                """,
                (rs, n) -> new Draft(
                        rs.getString(1), rs.getLong(2), instant(rs, 3)
                ),
                id
        ).stream().findFirst().orElse(null);
    }

    private Attempt latest(long id) {
        return jdbc.query("""
                select a.id, s.question_id, s.attempt_number, a.answer_text,
                       s.submitted_at, t.id, t.status
                from interview_attempts a
                join interview_submissions s on s.id = a.id
                left join ai_tasks t
                  on t.source_attempt_id = a.id
                 and t.type = 'INTERVIEW_EVALUATION'
                where s.question_id = ?
                order by s.submitted_at desc, s.id desc
                limit 1
                """,
                (rs, n) -> {
                    if (rs.getString(6) == null) {
                        throw new IllegalStateException(
                                "Latest interview attempt has no evaluation task"
                        );
                    }

                    return new Attempt(
                            rs.getLong(1), rs.getLong(2), rs.getInt(3),
                            rs.getString(4), instant(rs, 5),
                            rs.getString(6), rs.getString(7)
                    );
                },
                id
        ).stream().findFirst().orElse(null);
    }

    private long count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }

    private static Instant instant(ResultSet rs, int i) throws SQLException {
        LocalDateTime value = rs.getObject(i, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}