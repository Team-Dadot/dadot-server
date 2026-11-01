package com.dadot.server.domain.interview.repository;

import com.dadot.server.domain.interview.model.InterviewGeneration.Source;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

@Repository
@RequiredArgsConstructor
public class InterviewDisclosureImpactRepository {
    private static final Logger log = LoggerFactory.getLogger(InterviewDisclosureImpactRepository.class);
    private static final int BATCH_SIZE = 200;
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    // 공시 변경 서비스가 같은 companies 행을 잠근 트랜잭션에서 호출한다.
    // 여기서 users·개인 컨텍스트를 잠그지 않아 사용자 -> 기업 잠금 순서를 역전시키지 않는다.
    public long invalidate(long company, Set<Long> disclosures, Instant now) {
        if (disclosures.isEmpty()) return 0;
        Object[] args = parameters(company, disclosures);
        String invalid = """
                v.company_id = ? and v.disclosure_id in (
                """ + marks(disclosures.size()) + """
                ) and (
                    v.status <> 'PUBLISHED'
                    or exists(select 1 from disclosures c where c.corrects_disclosure_id = v.disclosure_id)
                )
                """;
        var versions = new HashSet<>(jdbc.query(
                "select v.id from disclosure_versions v where " + invalid,
                (rs, n) -> rs.getLong(1), args));
        if (versions.isEmpty()) return 0;

        Long affected = jdbc.queryForObject("""
                select count(distinct e.question_id)
                from interview_question_evidence e
                join interview_questions q on q.id = e.question_id
                    and q.user_id = e.user_id and q.company_id = e.company_id
                join disclosure_sections s on s.id = e.section_id and s.company_id = e.company_id
                join disclosure_versions v on v.id = s.disclosure_version_id
                where
                """ + invalid, Long.class, args);

        var tasks = new TreeSet<>(jdbc.query("""
                select distinct t.id
                from ai_tasks t
                join interview_submissions a on a.id = t.source_attempt_id
                    and a.user_id = t.user_id and a.company_id = t.company_id
                join interview_question_evidence e on e.question_id = a.question_id
                    and e.user_id = a.user_id and e.company_id = a.company_id
                join disclosure_sections s on s.id = e.section_id and s.company_id = e.company_id
                join disclosure_versions v on v.id = s.disclosure_version_id
                where t.type = 'INTERVIEW_EVALUATION' and t.status = 'PROCESSING' and
                """ + invalid, (rs, n) -> rs.getString(1), args));

        // 생성 작업은 아직 질문/근거 행이 없을 수 있으므로 고정된 보고서 3개로 판정한다.
        // 입장 제어상 진행 중 생성 작업은 전체 최대 20개다.
        jdbc.query("""
                select t.id, j.sources_json
                from ai_tasks t join interview_generation_jobs j on j.task_id = t.id
                    and j.user_id = t.user_id and j.company_id = t.company_id
                where t.company_id = ? and t.type = 'INTERVIEW_GENERATION' and t.status = 'PROCESSING'
                order by t.id
                """, rs -> {
            if (references(rs.getString(2), versions)) tasks.add(rs.getString(1));
        }, company);

        List<String> ordered = new ArrayList<>(tasks);
        LocalDateTime timestamp = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        for (int offset = 0; offset < ordered.size(); offset += BATCH_SIZE) {
            List<String> batch = ordered.subList(offset, Math.min(offset + BATCH_SIZE, ordered.size()));
            // 실패 처리 워커와 경합하면 이미 끝난 작업은 건드리지 않는다.
            List<String> locked = jdbc.query(
                    "select id from ai_tasks where id in (" + marks(batch.size())
                            + ") and status = 'PROCESSING' order by id for update",
                    (rs, n) -> rs.getString(1), batch.toArray());
            if (locked.isEmpty()) continue;
            cancel(locked, timestamp);
        }
        return Objects.requireNonNull(affected);
    }

    @SuppressWarnings("PMD.PreserveStackTrace")
    private boolean references(String json, Set<Long> versions) {
        List<Source> sources;
        try {
            sources = mapper.readerForListOf(Source.class).readValue(json);
        } catch (JacksonException ex) {
            log.warn("Invalid interview source snapshot. exceptionClass={}", ex.getClass().getName());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        if (sources == null || sources.size() != 3 || sources.stream().anyMatch(s -> s == null)) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return sources.stream().anyMatch(source -> versions.contains(source.versionId()));
    }

    private void cancel(List<String> tasks, LocalDateTime now) {
        String ids = marks(tasks.size());
        var args = new ArrayList<Object>();
        args.add(now);
        args.addAll(tasks);
        jdbc.update("""
                update ai_task_runs set status = 'CANCELLED', error_code = 'CONTENT_UNAVAILABLE', finished_at = ?
                where status = 'PROCESSING' and task_id in (
                """ + ids + ")", args.toArray());
        if (jdbc.update("""
                update ai_tasks set status = 'CANCELLED', last_error_code = 'CONTENT_UNAVAILABLE',
                    completed_at = ?, lease_owner = null, lease_until = null
                where status = 'PROCESSING' and id in (
                """ + ids + ")", args.toArray()) != tasks.size()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        jdbc.update("""
                update interview_generation_jobs set active_user_id = null, active_company_id = null
                where task_id in (
                """ + ids + ")", tasks.toArray());
    }

    private static Object[] parameters(long company, Set<Long> disclosures) {
        var args = new ArrayList<Object>();
        args.add(company);
        args.addAll(disclosures.stream().sorted().toList());
        return args.toArray();
    }

    private static String marks(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
}