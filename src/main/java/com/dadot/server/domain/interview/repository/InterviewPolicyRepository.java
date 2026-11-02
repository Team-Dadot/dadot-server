package com.dadot.server.domain.interview.repository;

import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.interview.model.InterviewPolicy.*;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Repository
@RequiredArgsConstructor
public class InterviewPolicyRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public record Policy(long id, long revision, Type type, Scope scope, Integer activeNo) {}
    public record Version(long id, long policyId, int number, Status status) {}
    private record Row(long id, long policyId, long revision, Type type, Scope scope,
                       Status status, String rules, String config, String note, Instant createdAt) {}
    private record CriterionRow(long versionId, Criterion criterion) {}

    public void validateScope(Scope scope) {
        if (scope.companyId() != null
                && count("select count(*) from companies where id = ?", scope.companyId()) != 1) {
            throw InterviewPolicy.missing();
        }
        if (scope.jobId() != null
                && count("select count(*) from jobs where id = ?", scope.jobId()) != 1) {
            throw InterviewPolicy.missing();
        }
    }

    public Policy createPolicy(Create content, Instant now) {
        try {
            long id = insert("""
                    insert into interview_policies(policy_key, type, company_id, job_id, updated_at)
                    values (?, ?, ?, ?, ?)
                    """, content.scope().key(content.type()), content.type().name(),
                    content.scope().companyId(), content.scope().jobId(), utc(now));
            return lockPolicy(id);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.POLICY_ALREADY_EXISTS);
        }
    }

    public Policy lockPolicy(long id) {
        return jdbc.query("""
                select id, version, type, company_id, job_id, active_version_no
                from interview_policies where id = ? for update
                """, (rs, n) -> new Policy(rs.getLong(1), rs.getLong(2),
                        Type.valueOf(rs.getString(3)), new Scope(rs.getObject(4, Long.class),
                        rs.getObject(5, Long.class)), rs.getObject(6, Integer.class)), id)
                .stream().findFirst().orElseThrow(InterviewPolicy::missing);
    }

    public Version lockVersion(long policyId, long versionId) {
        return jdbc.query("""
                select id, policy_id, version_no, status
                from interview_policy_versions where policy_id = ? and id = ? for update
                """, (rs, n) -> version(rs), policyId, versionId)
                .stream().findFirst().orElseThrow(InterviewPolicy::missing);
    }

    // 호출자는 논리 정책의 부모 행을 잠근다.
    public long createVersion(Policy policy, Create content, Instant now) {
        long number = count("""
                select coalesce(max(version_no), 0) + 1
                from interview_policy_versions where policy_id = ?
                """, policy.id());
        if (number > Integer.MAX_VALUE) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);

        long id = insert("""
                insert into interview_policy_versions(
                    policy_id, version_no, prompt_template, rule_config, created_at)
                values (?, ?, ?, ?, ?)
                """, policy.id(), number, content.rules(), config(content.aggregationRule()), utc(now));

        for (int i = 0; i < content.criteria().size(); i++) {
            Criterion item = content.criteria().get(i);
            jdbc.update("""
                    insert into evaluation_criteria(
                        policy_version_id, position, code, name, description, max_score)
                    values (?, ?, ?, ?, ?, ?)
                    """, id, i + 1, item.code(), item.label(), item.description(), item.maxScore());
        }
        return id;
    }

    public void requireReady(long versionId) {
        try {
            View value = view(versionId);
            new Create(value.type(), value.scope(), value.rules(), value.criteria(),
                    value.aggregationRule(), value.policyId());
        } catch (BusinessException ex) {
            throw InterviewPolicy.notConfigured();
        }
    }

    public void requirePublishable(Version target) {
        if (count("""
                select count(*) from interview_policy_versions
                where policy_id = ? and version_no > ? and published_at is not null
                """, target.policyId(), target.number()) != 0) {
            throw InterviewPolicy.transition();
        }
    }

    public void supersedePublished(Version target, long actor, String note, Instant now) {
        var previous = jdbc.query("""
                select id, policy_id, version_no, status from interview_policy_versions
                where policy_id = ? and id <> ? and status <> 'SUPERSEDED'
                  and published_at is not null
                order by id for update
                """, (rs, n) -> version(rs), target.policyId(), target.id());
        for (Version item : previous) changeStatus(item, Status.SUPERSEDED, actor, note, now);
    }

    public void changeStatus(Version target, Status next, long actor, String note, Instant now) {
        int changed = jdbc.update("""
                update interview_policy_versions
                set status = ?, review_note = ?, reviewed_by = ?, reviewed_at = ?,
                    published_at = case when ? = 'PUBLISHED'
                        then coalesce(published_at, ?) else published_at end
                where id = ? and status = ?
                """, next.name(), note, actor, utc(now), next.name(), utc(now),
                target.id(), target.status().name());
        if (changed != 1) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        audit(actor, "POLICY_" + target.status() + "_TO_" + next, target.id(), note, now);
    }

    public void activate(long policyId, Integer number) {
        jdbc.update("update interview_policies set active_version_no = ? where id = ?", number, policyId);
    }

    public void touch(long policyId, Instant now) {
        if (jdbc.update("""
                update interview_policies set version = version + 1, updated_at = ?
                where id = ? and version < ?
                """, utc(now), policyId, InterviewPolicy.MAX_ID) != 1) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    public void audit(long actor, String action, long versionId, String note, Instant now) {
        jdbc.update("""
                insert into admin_audit_events(
                    actor_user_id, action, target_type, target_id, reason, occurred_at)
                values (?, ?, 'INTERVIEW_POLICY_VERSION', ?, ?, ?)
                """, actor, action, Long.toString(versionId), note, utc(now));
    }

    public View view(long versionId) {
        List<Row> rows = jdbc.query(base() + " where v.id = ?", (rs, n) -> row(rs), versionId);
        if (rows.isEmpty()) throw InterviewPolicy.missing();
        return assemble(rows).getFirst();
    }

    public Page search(Search search) {
        String filter = " where (? is null or p.company_id = ?)"
                + " and (? is null or p.job_id = ?) and (? is null or p.type = ?)";
        String type = search.type() == null ? null : search.type().name();
        long total = count("""
                select count(*) from interview_policy_versions v
                join interview_policies p on p.id = v.policy_id
                """ + filter, search.companyId(), search.companyId(), search.jobId(), search.jobId(), type, type);
        List<Row> rows = jdbc.query(base() + filter + " order by v.created_at desc, v.id desc limit ? offset ?",
                (rs, n) -> row(rs), search.companyId(), search.companyId(), search.jobId(), search.jobId(),
                type, type, search.size(), (long) search.page() * search.size());
        return new Page(assemble(rows), search.page(), search.size(), total,
                (total + search.size() - 1) / search.size(), ((long) search.page() + 1) * search.size() < total);
    }

    // 페이지의 평가 항목을 한 번에 읽어 버전 수에 비례하는 추가 쿼리를 피한다.
    private List<View> assemble(List<Row> rows) {
        if (rows.isEmpty()) return List.of();
        Object[] ids = rows.stream().map(Row::id).toArray();
        String marks = String.join(",", Collections.nCopies(ids.length, "?"));
        var items = jdbc.query("""
                select policy_version_id, code, name, max_score, description
                from evaluation_criteria where policy_version_id in (
                """ + marks + ") order by policy_version_id, position", (rs, n) -> new CriterionRow(
                rs.getLong(1), new Criterion(rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5))), ids);
        Map<Long, List<Criterion>> criteria = new HashMap<>();
        for (CriterionRow item : items) {
            criteria.computeIfAbsent(item.versionId(), ignored -> new ArrayList<>()).add(item.criterion());
        }
        return rows.stream().map(r -> new View(r.policyId(), r.id(), r.revision(), r.type(), r.scope(),
                r.status(), r.rules(), List.copyOf(criteria.getOrDefault(r.id(), List.of())),
                aggregation(r.type(), r.config()), r.note(), r.createdAt())).toList();
    }

    private String aggregation(Type type, String value) {
        var expected = mapper.readTree(config(type == Type.EVALUATION ? InterviewPolicy.AGGREGATION : null));
        if (!expected.equals(mapper.readTree(value))) throw InterviewPolicy.notConfigured();
        return type == Type.EVALUATION ? InterviewPolicy.AGGREGATION : null;
    }

    private static String config(String aggregation) {
        return "{\"materialTypes\":[\"EXPERIENCE\"],\"aggregationRule\":"
                + (aggregation == null ? "null" : "\"" + InterviewPolicy.AGGREGATION + "\"") + "}";
    }

    private static String base() {
        return """
                select v.id, p.id, p.version, p.type, p.company_id, p.job_id,
                       v.status, v.prompt_template, v.rule_config, v.review_note, v.created_at
                from interview_policy_versions v join interview_policies p on p.id = v.policy_id
                """;
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(rs.getLong(1), rs.getLong(2), rs.getLong(3), Type.valueOf(rs.getString(4)),
                new Scope(rs.getObject(5, Long.class), rs.getObject(6, Long.class)),
                Status.valueOf(rs.getString(7)), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getObject(11, LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }

    private static Version version(ResultSet rs) throws SQLException {
        return new Version(rs.getLong(1), rs.getLong(2), rs.getInt(3), Status.valueOf(rs.getString(4)));
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

    private static LocalDateTime utc(Instant now) { return LocalDateTime.ofInstant(now, ZoneOffset.UTC); }
}