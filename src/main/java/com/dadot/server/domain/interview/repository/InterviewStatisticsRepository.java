package com.dadot.server.domain.interview.repository;

import com.dadot.server.domain.company.model.PreparationCompany.InterviewToday;
import com.dadot.server.domain.company.model.PreparationCompany.QuizToday;
import com.dadot.server.domain.home.model.Home.EvaluationSummary;
import com.dadot.server.domain.interview.model.InterviewGeneration;
import com.dadot.server.global.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class InterviewStatisticsRepository {
    private final JdbcTemplate jdbc;
    private final InterviewPolicyRepository policies;
    private final InterviewReadRepository reads;

    public Map<Long, InterviewToday> today(long user, List<Long> companies, Instant now) {
        if (companies.isEmpty()) return Map.of();
        var date = now.atZone(InterviewGeneration.KST).toLocalDate();
        var reset = date.plusDays(1).atStartOfDay(InterviewGeneration.KST).toInstant();
        String marks = String.join(",", Collections.nCopies(companies.size(), "?"));
        var args = new ArrayList<Object>(List.of(user, date));
        args.addAll(companies); args.add(user); args.add(date); args.addAll(companies);
        Map<Long, int[]> counts = new HashMap<>();
        jdbc.query("""
                select company_id, count(*) used, 0 questions from interview_submissions
                where user_id = ? and business_date = ? and company_id in (
                """ + marks + ") group by company_id union all " + """
                select company_id, 0 used, count(*) questions from interview_daily_questions
                where user_id = ? and business_date = ? and company_id in (
                """ + marks + ") group by company_id", rs -> {
            int[] value = counts.computeIfAbsent(rs.getLong(1), ignored -> new int[2]);
            value[0] += rs.getInt(2); value[1] += rs.getInt(3);
        }, args.toArray());
        Map<Long, InterviewToday> result = new HashMap<>();
        for (long company : companies) {
            int[] value = counts.getOrDefault(company, new int[2]);
            result.put(company, new InterviewToday(date, 9, value[0], Math.max(0, 9 - value[0]),
                    reset, 3, value[1], Math.max(0, 3 - value[1])));
        }
        return Map.copyOf(result);
    }

    public EvaluationSummary summary(long user, long company, Long job) {
        var values = jdbc.queryForObject("""
                select coalesce(sum(t.status = 'SUCCEEDED'), 0), coalesce(sum(t.status = 'PROCESSING'), 0),
                    coalesce(sum(t.status = 'FAILED'), 0), coalesce(sum(t.status = 'CANCELLED'), 0),
                    coalesce(sum(case when t.status = 'SUCCEEDED' then e.total_score else 0 end), 0),
                    coalesce(sum(t.id is null or (t.status = 'SUCCEEDED' and e.id is null)), 0)
                from interview_questions q
                join interview_submissions s on s.question_id = q.id
                join interview_attempts a on a.id = s.id
                left join ai_tasks t on t.source_attempt_id = a.id and t.type = 'INTERVIEW_EVALUATION'
                left join interview_evaluations e on e.attempt_id = a.id and e.task_id = t.id
                where q.user_id = ? and q.company_id = ? and not exists(
                    select 1 from interview_submissions newer join interview_attempts remaining on remaining.id = newer.id
                    where newer.question_id = q.id and newer.attempt_number > s.attempt_number)
                """, (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)}, user, company);
        if (values == null || values[5] != 0) throw new IllegalStateException("Invalid latest interview evaluation state");
        Integer average = values[0] == 0 ? null : BigDecimal.valueOf(values[4])
                .divide(BigDecimal.valueOf(values[0]), 0, RoundingMode.HALF_UP).intValueExact();
        return new EvaluationSummary(values[0], values[1], values[2], policyStatus(company, job), average, values[3]);
    }

    private String policyStatus(long company, Long job) {
        var versions = jdbc.query("""
                select v.id from interview_policies p join interview_policy_versions v
                    on v.policy_id = p.id and v.version_no = p.active_version_no
                where p.type = 'EVALUATION' and v.status = 'PUBLISHED'
                    and (p.company_id is null or p.company_id = ?) and (p.job_id is null or p.job_id = ?)
                order by (p.company_id is not null) desc, (p.job_id is not null) desc limit 1
                """, (rs, n) -> rs.getLong(1), company, job);
        if (versions.isEmpty()) return "NOT_CONFIGURED";
        try { policies.requireReady(versions.getFirst()); return "CONFIGURED"; }
        catch (BusinessException ex) { return "NOT_CONFIGURED"; }
    }

    public String nextAction(long user, long company, QuizToday quiz, InterviewToday today, Instant now) {
        Long draft = reads.reusable(user, company, true, false, now);
        boolean quizInProgress = quiz.quizSetId() != null && !quiz.completed();
        if (draft != null) {
            var saved = jdbc.queryForObject("select saved_at from interview_drafts where question_id = ?", LocalDateTime.class, draft);
            LocalDateTime quizActivity = !quizInProgress ? null : jdbc.queryForObject("""
                    select coalesce(max(a.submitted_at), s.created_at)
                    from quiz_sets s left join quiz_set_items i on i.set_id = s.id
                    left join quiz_answers a on a.set_item_id = i.id
                    where s.id = ? and s.user_id = ? group by s.id, s.created_at
                    """, LocalDateTime.class, quiz.quizSetId(), user);
            return quizActivity != null && saved != null && quizActivity.isAfter(saved) ? "CONTINUE_QUIZ" : "CONTINUE_INTERVIEW";
        }
        if (quizInProgress) return "CONTINUE_QUIZ";
        if (!quiz.completed()) return "START_QUIZ";
        if (today.remaining() == 0) return "REVIEW";
        // 새 질문 진입은 미답변 질문 또는 남은 질문 슬롯이 있을 때만 안내한다.
        // 답변한 질문의 남은 재도전은 복습/질문 상세에서 계속 사용할 수 있다.
        if (reads.reusable(user, company, false, true, now) != null) return "START_INTERVIEW";
        return today.questionRemaining() == 0 ? "REVIEW" : "START_INTERVIEW";
    }
}