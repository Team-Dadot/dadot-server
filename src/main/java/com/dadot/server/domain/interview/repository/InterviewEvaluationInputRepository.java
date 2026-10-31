package com.dadot.server.domain.interview.repository;

import com.dadot.server.domain.interview.model.InterviewEvaluation.*;
import com.dadot.server.domain.interview.model.InterviewGeneration.Job;
import com.dadot.server.domain.interview.model.InterviewGeneration.Material;
import com.dadot.server.domain.interview.model.InterviewGeneration.Failure;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.interview.support.InterviewEvaluationProperties;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.infrastructure.openai.OpenAiEvaluationClient;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

@Repository
@RequiredArgsConstructor
public class InterviewEvaluationInputRepository {
    private final JdbcTemplate jdbc;
    private final InterviewReadRepository reads;
    private final InterviewPolicyRepository policies;
    private final OpenAiEvaluationClient client;
    private final InterviewEvaluationProperties properties;

    public record Prepared(long userId, long companyId, long attemptId, long questionId,
                           String contextId, long policyId, String model, String promptHash,
                           String inputHash, String rules, Input input) {
        @Override public String toString() { return "PreparedEvaluation[redacted]"; }
    }
    private record Meta(long user, long company, long attempt, long question, String context, long policy) {}
    private record Content(Job job, String question, String answer) {}
    private record JobConfig(String model, String promptHash, String inputHash) {}

    // users → 준비 기업/companies → 질문 키 → 정책 → task 순서. HTTP는 호출하지 않는다.
    public Prepared prepare(String task, Instant now) {
        Meta meta = jdbc.query("""
                select t.user_id, t.company_id, a.id, s.question_id, t.context_id, t.policy_version_id
                from ai_tasks t
                join interview_attempts a on a.id = t.source_attempt_id and a.user_id = t.user_id
                    and a.context_id = t.context_id and a.evaluation_policy_version_id = t.policy_version_id
                join interview_submissions s on s.id = a.id and s.company_id = t.company_id
                join interview_questions q on q.id = s.question_id and q.user_id = t.user_id
                where t.id = ? and t.type = 'INTERVIEW_EVALUATION'
                """, (rs, n) -> new Meta(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                rs.getString(5), rs.getLong(6)), task).stream().findFirst().orElseThrow(InterviewEvaluationInputRepository::unavailable);
        String owner = jdbc.query("select status from users where id = ? for update",
                (rs, n) -> rs.getString(1), meta.user()).stream().findFirst().orElse(null);
        if (!"ACTIVE".equals(owner)) throw unavailable();
        var prep = reads.preparation(meta.user(), meta.company(), true);
        // 이미 제출한 답변의 평가이므로 기업 제외만으로 과거 답변을 무효화하지 않는다.
        if (jdbc.query("""
                select id from interview_question_keys
                where id = ? and user_id = ? and deleted_at is null for update
                """, (rs, n) -> rs.getLong(1), meta.question(), meta.user()).isEmpty()) throw unavailable();

        Long parent = jdbc.queryForObject("select policy_id from interview_policy_versions where id = ?", Long.class, meta.policy());
        var policyParent = policies.lockPolicy(Objects.requireNonNull(parent));
        var policyVersion = policies.lockVersion(parent, meta.policy());
        if (policyParent.type() != InterviewPolicy.Type.EVALUATION
                || !List.of(InterviewPolicy.Status.PUBLISHED, InterviewPolicy.Status.SUPERSEDED).contains(policyVersion.status())) throw unavailable();
        try { policies.requireReady(meta.policy()); }
        catch (BusinessException ex) { throw unavailable(); }
        var policy = policies.view(meta.policy());

        Content content = jdbc.query("""
                select x.job_id_snapshot, x.job_name_snapshot, x.job_is_custom, q.question_text, a.answer_text
                from interview_attempts a
                join interview_submissions s on s.id = a.id
                join interview_questions q on q.id = s.question_id
                join ai_contexts x on x.id = a.context_id and x.user_id = a.user_id and x.company_id = a.company_id
                where a.id = ? and x.invalidated_at is null
                """, (rs, n) -> new Content(new Job(rs.getObject(1, Long.class), rs.getString(2), rs.getBoolean(3)),
                        rs.getString(4), rs.getString(5)), meta.attempt()).stream().findFirst()
                .orElseThrow(InterviewEvaluationInputRepository::unavailable);

        List<EvidenceInput> evidence = jdbc.query("""
                select s.id, v.id, d.report_name, s.heading, s.content_text, e.quotation,
                       v.status, exists(select 1 from disclosures c where c.corrects_disclosure_id = d.id),
                       d.report_type, d.disclosed_on, d.period_start, d.period_end
                from interview_question_evidence e
                join disclosure_sections s on s.id = e.section_id and s.company_id = e.company_id
                join disclosure_versions v on v.id = s.disclosure_version_id
                join disclosures d on d.id = v.disclosure_id
                where e.question_id = ? and e.company_id = ? order by s.id
                """, (rs, n) -> {
            if (!"PUBLISHED".equals(rs.getString(7)) || rs.getBoolean(8)
                    || rs.getString(5).isBlank() || rs.getString(5).length() > 8192
                    || !rs.getString(5).contains(rs.getString(6))) throw new Failure("CONTENT_UNAVAILABLE", false);
            return new EvidenceInput(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(9),
                    rs.getObject(10, java.time.LocalDate.class), rs.getObject(11, java.time.LocalDate.class),
                    rs.getObject(12, java.time.LocalDate.class), rs.getString(4), rs.getString(5), rs.getString(6));
        }, meta.question(), meta.company());
        if (evidence.isEmpty() || evidence.size() > 3) throw new Failure("CONTENT_UNAVAILABLE", false);

        long length = Objects.requireNonNull(jdbc.queryForObject("""
                select coalesce(sum(char_length(v.title) + char_length(v.content_text)), 0)
                from ai_context_materials x join material_versions v on v.id = x.material_version_id
                where x.context_id = ?
                """, Long.class, meta.context()));
        if (length > properties.maxExperienceCharacters()) throw new Failure("INTERVIEW_INPUT_TOO_LARGE", false);
        List<Material> materials = jdbc.query("""
                select m.id, v.id, v.version_no, v.title, v.content_text, m.type, v.material_type,
                       m.user_id, v.user_id, x.user_id
                from ai_context_materials x
                join material_versions v on v.id = x.material_version_id
                join personal_materials m on m.id = v.material_id
                where x.context_id = ? order by m.id, v.id
                """, (rs, n) -> {
            if (!"EXPERIENCE".equals(rs.getString(6)) || !"EXPERIENCE".equals(rs.getString(7))
                    || rs.getLong(8) != meta.user() || rs.getLong(9) != meta.user() || rs.getLong(10) != meta.user()) throw unavailable();
            return new Material(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getString(5));
        }, meta.context());
        if (materials.size() != Objects.requireNonNull(jdbc.queryForObject(
                "select count(*) from ai_context_materials where context_id = ?", Long.class, meta.context()))
                || materials.stream().map(Material::materialId).distinct().count() != materials.size()) throw unavailable();

        Input input = new Input(prep.companyName(), content.job(), content.question(), content.answer(), evidence, materials, policy.criteria());
        String status = jdbc.queryForObject("select status from ai_tasks where id = ? for update", String.class, task);
        if (!List.of("PROCESSING", "FAILED").contains(status)) throw unavailable();
        var jobs = jdbc.query("select model, prompt_hash, input_hash from interview_evaluation_jobs where task_id = ?",
                (rs, n) -> new JobConfig(rs.getString(1), rs.getString(2), rs.getString(3)), task);
        String model = jobs.isEmpty() ? properties.model() : jobs.getFirst().model();
        String promptHash = client.policyHash(policy.rules());
        String inputHash = client.inputHash(model, policy.rules(), input);
        if (jobs.isEmpty()) {
            jdbc.update("""
                    insert into interview_evaluation_jobs(task_id, model, prompt_hash, input_hash, created_at)
                    values (?, ?, ?, ?, ?)
                    """, task, model, promptHash, inputHash, LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        } else if (!promptHash.equals(jobs.getFirst().promptHash()) || !inputHash.equals(jobs.getFirst().inputHash())) {
            throw new Failure("AI_POLICY_CHANGED", false);
        }
        return new Prepared(meta.user(), meta.company(), meta.attempt(), meta.question(), meta.context(),
                meta.policy(), model, promptHash, inputHash, policy.rules(), input);
    }

    // #50에서 접수된 작업은 최초 워커 실행 전에 입력을 고정한다. 새 제출은 접수 트랜잭션에서 고정한다.
    public void prepareNext(Instant now) {
        var tasks = jdbc.query("""
                select t.id from ai_tasks t left join interview_evaluation_jobs j on j.task_id = t.id
                where t.type = 'INTERVIEW_EVALUATION' and t.status = 'PROCESSING' and j.task_id is null
                order by t.created_at, t.id limit 1
                """, (rs, n) -> rs.getString(1));
        if (tasks.isEmpty()) return;
        String task = tasks.getFirst();
        try { prepare(task, now); }
        catch (Failure ex) {
            jdbc.update("""
                    update ai_tasks set status = ?, last_error_code = ?, completed_at = ?, lease_owner = null, lease_until = null
                    where id = ? and status = 'PROCESSING'
                        and not exists(select 1 from interview_evaluation_jobs j where j.task_id = ai_tasks.id)
                    """, "CONTEXT_UNAVAILABLE".equals(ex.code()) ? "CANCELLED" : "FAILED", ex.code(),
                    LocalDateTime.ofInstant(now, ZoneOffset.UTC), task);
        }
    }

    private static Failure unavailable() { return new Failure("CONTEXT_UNAVAILABLE", false); }
}