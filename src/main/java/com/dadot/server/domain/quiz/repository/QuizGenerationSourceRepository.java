package com.dadot.server.domain.quiz.repository;

import com.dadot.server.domain.quiz.model.QuizBank;
import com.dadot.server.domain.quiz.model.QuizGeneration.Plan;
import com.dadot.server.domain.quiz.model.QuizGeneration.Request;
import com.dadot.server.domain.quiz.model.QuizGeneration.Section;
import com.dadot.server.domain.quiz.model.QuizGeneration.Source;
import com.dadot.server.domain.quiz.support.QuizGenerationProperties;
import com.dadot.server.domain.quiz.support.QuizSourcePlanner;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class QuizGenerationSourceRepository {
    private final JdbcTemplate jdbc;
    private final QuizSourcePlanner planner;
    private final QuizGenerationProperties properties;

    public Plan plan(long companyId, Request request) {
        var sources = request.disclosureVersionIds().stream().map(id -> source(companyId, id)).toList();
        var types = new HashSet<String>();
        for (var source : sources) if (!types.add(source.reportType())) throw QuizBank.invalid();
        long characters = 0;
        var reports = new ArrayList<List<Section>>();
        int count = 0;
        for (var source : sources) {
            // 먼저 합계를 검사하여 과대한 본문을 메모리로 읽거나 유료 API로 보내지 않는다.
            Long length = jdbc.queryForObject("""
                    select coalesce(sum(char_length(content_text)), 0)
                    from disclosure_sections where disclosure_version_id = ?
                    """, Long.class, source.disclosureVersionId());
            characters += length == null ? 0 : length;
            if (characters > properties.maxSourceCharacters()) throw QuizBank.invalid();
            var sections = jdbc.query("""
                    select id, heading, content_text,
                           json_unquote(json_extract(locator_json, '$.kind')) as kind
                    from disclosure_sections where disclosure_version_id = ? order by id
                    """, (rs, n) -> new Section(rs.getLong("id"), source.disclosureVersionId(),
                    source.reportTitle(), source.reportType(), source.submittedDate(), rs.getString("heading"),
                    rs.getString("kind"), rs.getString("content_text")), source.disclosureVersionId());
            reports.add(sections);
            count += sections.size();
        }
        return new Plan(sources, planner.plan(reports, properties.maxBatches()), count);
    }

    public void validate(long companyId, List<Source> pinned) {
        for (var expected : pinned) {
            var actual = source(companyId, expected.disclosureVersionId());
            if (!actual.equals(expected)) throw QuizBank.unavailable();
        }
    }

    private Source source(long companyId, long id) {
        var rows = jdbc.query("""
                select v.id, d.id as disclosure_id, d.report_name, d.report_type,
                       d.disclosed_on, v.content_hash, v.status,
                       exists(select 1 from disclosures c where c.corrects_disclosure_id = d.id) as corrected
                from disclosure_versions v join disclosures d on d.id = v.disclosure_id
                where v.id = ? and v.company_id = ?
                """, (rs, n) -> {
            String state = rs.getString("status");
            if (!List.of("DRAFT", "IN_REVIEW", "APPROVED", "PUBLISHED").contains(state)
                    || rs.getBoolean("corrected")) throw QuizBank.unavailable();
            return new Source(rs.getLong("id"), rs.getLong("disclosure_id"), rs.getString("report_name"),
                    rs.getString("report_type"), rs.getObject("disclosed_on", java.time.LocalDate.class),
                    rs.getString("content_hash"));
        }, id, companyId);
        if (rows.isEmpty()) throw QuizBank.missing();
        return rows.getFirst();
    }
}