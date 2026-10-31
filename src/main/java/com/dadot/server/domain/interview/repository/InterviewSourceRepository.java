package com.dadot.server.domain.interview.repository;

import com.dadot.server.domain.interview.model.InterviewGeneration;
import com.dadot.server.domain.interview.model.InterviewGeneration.*;
import com.dadot.server.domain.interview.support.InterviewGenerationProperties;
import com.dadot.server.domain.interview.support.InterviewSourcePlanner;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class InterviewSourceRepository {
    private final JdbcTemplate jdbc;
    private final InterviewSourcePlanner planner;
    private final InterviewGenerationProperties properties;

    public List<Material> materials(long userId, String contextId) {
        String filter = contextId == null
                ? " m.current_version_no = v.version_no"
                : """
                   exists(
                       select 1 from ai_context_materials x
                       where x.material_version_id = v.id and x.context_id = ?
                   )
                   """;

        Object[] args = contextId == null
                ? new Object[]{userId}
                : new Object[]{userId, contextId};

        String from = """
                from material_versions v
                join personal_materials m on m.id = v.material_id
                where m.user_id = ?
                  and m.type = 'EXPERIENCE'
                  and v.material_type = 'EXPERIENCE'
                  and
                """ + filter;

        Long length = jdbc.queryForObject(
                """
                select coalesce(sum(
                    char_length(v.content_text) + char_length(v.title)
                ), 0)
                """ + from,
                Long.class,
                args
        );

        if (length != null && length > properties.maxExperienceCharacters()) {
            throw new BusinessException(ErrorCode.INTERVIEW_INPUT_TOO_LARGE);
        }

        String selectSql = "select m.id, v.id, v.version_no, v.title, v.content_text";
        String sql = String.join("\n", selectSql, from, "order by m.id");

        return jdbc.query(
                sql,
                (rs, n) -> new Material(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getInt(3),
                        rs.getString(4),
                        rs.getString(5)
                ),
                args
        );
    }

    public Plan plan(long companyId, String job, List<Material> materials) {
        var pinned = new ArrayList<Source>();
        var reports = new ArrayList<List<Section>>();
        long characters = 0;
        int count = 0;

        for (String type : List.of("ANNUAL", "HALF_YEAR", "QUARTERLY")) {
            var rows = jdbc.query(
                    sourceSql() + """
                    where d.company_id = ?
                      and d.report_type = ?
                      and v.status = 'PUBLISHED'
                      and not exists(
                          select 1 from disclosures c
                          where c.corrects_disclosure_id = d.id
                      )
                    order by d.disclosed_on desc, d.receipt_no desc, v.version_no desc
                    limit 1
                    """,
                    (rs, n) -> source(rs),
                    companyId,
                    type
            );

            if (rows.isEmpty()) {
                throw new BusinessException(ErrorCode.CONTENT_UNAVAILABLE);
            }

            Source source = rows.getFirst();

            Long length = jdbc.queryForObject(
                    """
                    select coalesce(sum(char_length(content_text)), 0)
                    from disclosure_sections where disclosure_version_id = ?
                    """,
                    Long.class,
                    source.versionId()
            );

            characters += length == null ? 0 : length;

            if (characters > properties.maxSourceCharacters()) {
                throw new BusinessException(ErrorCode.INTERVIEW_INPUT_TOO_LARGE);
            }

            List<Section> sections = jdbc.query(
                    """
                    select id, heading, content_text
                    from disclosure_sections
                    where disclosure_version_id = ?
                    order by id
                    """,
                    (rs, n) -> new Section(
                            rs.getLong(1),
                            source.versionId(),
                            rs.getString(2),
                            rs.getString(3)
                    ),
                    source.versionId()
            );

            if (sections.isEmpty() || sections.stream().anyMatch(
                    s -> s.content().isBlank() || s.content().length() > 8192
            )) {
                throw new BusinessException(ErrorCode.CONTENT_UNAVAILABLE);
            }

            pinned.add(source);
            reports.add(sections);
            count += sections.size();
        }

        List<Section> selected = planner.select(reports, job, materials);

        if (pinned.stream().anyMatch(s -> selected.stream().noneMatch(
                x -> x.disclosureVersionId() == s.versionId()
        ))) {
            throw new BusinessException(ErrorCode.CONTENT_UNAVAILABLE);
        }

        return new Plan(List.copyOf(pinned), selected, count, characters);
    }

    // 호출자는 companies 행을 먼저 잠근다.
    public void validate(long companyId, List<Source> sources) {
        if (sources.size() != 3 || !sources.stream()
                .map(Source::reportType)
                .collect(java.util.stream.Collectors.toSet())
                .equals(InterviewGeneration.REPORT_TYPES)) {
            throw new Failure("CONTENT_UNAVAILABLE", false);
        }

        for (Source expected : sources.stream()
                .sorted(java.util.Comparator.comparingLong(Source::versionId))
                .toList()) {
            var rows = jdbc.query(
                    sourceSql() + """
                    where v.id = ?
                      and d.company_id = ?
                      and v.status = 'PUBLISHED'
                      and not exists(
                          select 1 from disclosures c
                          where c.corrects_disclosure_id = d.id
                      )
                    for update
                    """,
                    (rs, n) -> source(rs),
                    expected.versionId(),
                    companyId
            );

            if (rows.isEmpty() || !rows.getFirst().equals(expected)) {
                throw new Failure("CONTENT_UNAVAILABLE", false);
            }
        }
    }

    private static String sourceSql() {
        return """
                select d.id, v.id, d.report_type, d.report_name, d.disclosed_on,
                       d.period_start, d.period_end, v.content_hash
                from disclosures d
                join disclosure_versions v on v.disclosure_id = d.id
                """;
    }

    private static Source source(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new Source(
                rs.getLong(1),
                rs.getLong(2),
                rs.getString(3),
                rs.getString(4),
                rs.getObject(5, LocalDate.class),
                rs.getObject(6, LocalDate.class),
                rs.getObject(7, LocalDate.class),
                rs.getString(8)
        );
    }
}