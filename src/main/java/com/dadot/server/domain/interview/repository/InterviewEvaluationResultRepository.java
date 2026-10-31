package com.dadot.server.domain.interview.repository;

import com.dadot.server.domain.interview.model.InterviewEvaluation;
import com.dadot.server.domain.interview.model.InterviewEvaluation.*;
import com.dadot.server.domain.interview.model.InterviewGeneration.Attempt;
import com.dadot.server.domain.interview.model.InterviewGeneration.Problem;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import lombok.RequiredArgsConstructor;
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

@Repository
@RequiredArgsConstructor
public class InterviewEvaluationResultRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final InterviewEvaluationJobRepository jobs;
    private final InterviewReadRepository reads;

    public void save(Claim claim, Output output, Instant now) {
        Lease lease = jobs.load(claim, now);
        if (lease == null) return;
        Generated value = InterviewEvaluation.validate(mapper.valueToTree(output.generated()), lease.input());
        long evaluation = insert("""
                insert into interview_evaluations(attempt_id, task_id, user_id, context_id, policy_version_id,
                    summary_feedback, overall_feedback, strengths, improvements, total_score, evaluated_at)
                select source_attempt_id, id, user_id, context_id, policy_version_id, ?, ?, ?, ?, ?, ?
                from ai_tasks where id = ?
                """, value.summaryFeedback(), value.overallFeedback(), mapper.writeValueAsString(value.strengths()),
                mapper.writeValueAsString(value.improvements()), InterviewEvaluation.total(value), utc(now), claim.taskId());
        for (Score score : value.criteria()) {
            if (jdbc.update("""
                    insert into evaluation_scores(evaluation_id, criterion_id, policy_version_id, score, feedback, reasoning, answer_quote)
                    select ?, c.id, c.policy_version_id, ?, ?, ?, ?
                    from evaluation_criteria c join ai_tasks t on t.policy_version_id = c.policy_version_id
                    where t.id = ? and c.code = ?
                    """, evaluation, score.score(), score.feedback(), score.reason(), score.answerQuote(),
                    claim.taskId(), score.criterionCode()) != 1) throw new IllegalStateException("Invalid evaluation criteria");
        }
        for (int i = 0; i < value.relatedMaterials().size(); i++) {
            Link link = value.relatedMaterials().get(i);
            jdbc.update("""
                    insert into evaluation_material_links(evaluation_id, context_id, material_version_id,
                        display_order, excerpt, connection_reason)
                    select ?, context_id, ?, ?, ?, ? from ai_tasks where id = ?
                    """, evaluation, link.materialVersionId(), i + 1, link.excerpt(), link.connectionReason(), claim.taskId());
        }
        jobs.success(claim, output.metadata(), now);
    }

    public View view(long user, Attempt attempt, Instant now) {
        var state = jdbc.query("""
                select t.status, t.last_error_code, q.question_text, s.company_id, v.status, p.type
                from ai_tasks t join interview_submissions s on s.id = t.source_attempt_id
                join interview_questions q on q.id = s.question_id
                join interview_policy_versions v on v.id = t.policy_version_id
                join interview_policies p on p.id = v.policy_id
                where t.id = ? and t.user_id = ?
                """, (rs, n) -> {
            String status = rs.getString(1), code = rs.getString(2);
            var prep = reads.preparation(user, rs.getLong(4), false);
            Result result = "SUCCEEDED".equals(status) ? result(attempt.attemptId(), user) : null;
            Instant evaluated = result == null ? null : jdbc.queryForObject(
                    "select evaluated_at from interview_evaluations where attempt_id = ?", LocalDateTime.class,
                    attempt.attemptId()).toInstant(ZoneOffset.UTC);
            boolean configured = "EVALUATION".equals(rs.getString(6))
                    && List.of("PUBLISHED", "SUPERSEDED").contains(rs.getString(5));
            Problem problem = List.of("FAILED", "CANCELLED").contains(status)
                    ? new Problem(code != null && code.matches("[A-Z][A-Z0-9_]{0,79}") ? code : "EVALUATION_FAILED",
                    "면접 답변을 평가하지 못했습니다. 작업 상태와 재접수 가능 여부를 확인해 주세요.") : null;
            return new View(attempt, rs.getString(3), status, configured ? "CONFIGURED" : "NOT_CONFIGURED", result,
                    problem, evaluated, reads.usage(user, prep.companyId(), attempt.questionId(), prep.active(), now));
        }, attempt.evaluationTaskId(), user);
        if (state.isEmpty()) throw InterviewPolicy.missing();
        return state.getFirst();
    }

    private Result result(long attempt, long user) {
        return jdbc.query("""
                select id, policy_version_id, summary_feedback, overall_feedback, strengths, improvements, total_score
                from interview_evaluations where attempt_id = ? and user_id = ?
                """, (rs, n) -> {
            long id = rs.getLong(1), policy = rs.getLong(2);
            var criteria = jdbc.query("""
                    select c.code, c.name, s.score, c.max_score, s.feedback, s.reasoning
                    from evaluation_scores s join evaluation_criteria c on c.id = s.criterion_id
                        and c.policy_version_id = s.policy_version_id
                    where s.evaluation_id = ? and s.policy_version_id = ? order by c.position
                    """, (row, index) -> new CriterionView(row.getString(1), row.getString(2), row.getInt(3),
                    row.getInt(4), row.getString(5), row.getString(6)), id, policy);
            int total = rs.getInt(7);
            if (criteria.size() != 4 || !criteria.stream().map(CriterionView::criterionCode).toList().equals(InterviewPolicy.CODES)
                    || criteria.stream().mapToInt(CriterionView::score).sum() != total) {
                throw new IllegalStateException("Invalid saved evaluation scores");
            }
            var links = jdbc.query("""
                    select m.id, v.version_no, v.title, l.excerpt, l.connection_reason
                    from evaluation_material_links l
                    join ai_context_materials x on x.context_id = l.context_id and x.material_version_id = l.material_version_id
                    join material_versions v on v.id = l.material_version_id and v.user_id = x.user_id
                    join personal_materials m on m.id = v.material_id and m.user_id = x.user_id
                    where l.evaluation_id = ? and x.user_id = ? and m.type = 'EXPERIENCE' and v.material_type = 'EXPERIENCE'
                    order by l.display_order
                    """, (row, index) -> new MaterialView("EXPERIENCE", row.getLong(1), row.getInt(2), row.getString(3),
                    row.getString(4), row.getString(5)), id, user);
            return new Result(rs.getString(3), rs.getString(4), criteria, strings(rs.getString(5)),
                    strings(rs.getString(6)), links, policy, total, 100, total, total >= 80,
                    total >= 80 ? "READY" : "NEEDS_IMPROVEMENT", total >= 80 ? "준비 완료" : "보완 필요");
        }, attempt, user).stream().findFirst().orElseThrow(() -> new IllegalStateException("Successful task has no evaluation"));
    }

    private List<String> strings(String json) {
        return mapper.readerForListOf(String.class).readValue(json);
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