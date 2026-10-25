package com.dadot.server.domain.disclosure.repository;

import com.dadot.server.domain.disclosure.model.DisclosureSync.Accepted;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Company;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Cursor;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Failure;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Filing;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Lease;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Page;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Parameters;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Request;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Search;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Section;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Task;
import com.dadot.server.domain.disclosure.model.DisclosureSync.TaskError;
import com.dadot.server.domain.disclosure.model.DisclosureSync.TaskResult;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Version;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.infrastructure.dart.DartFailure;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class DisclosureRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public String companyCode(long companyId) {
        return jdbc.query("select dart_corp_code from companies where id = ?",
                        (rs, n) -> rs.getString(1), companyId).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    // 호출자가 먼저 users 행을 잠근다. 빈 결과에 gap lock을 만들지 않는다.
    public Accepted replay(String scope, String operation, String key, String body, Instant now) {
        var rows = jdbc.query("""
                select task_id, body_hmac, expires_at from api_idempotency_requests
                where scope_hash = ? and operation = ? and key_hash = ?
                """, (rs, n) -> {
            if (!body.equals(rs.getString("body_hmac")))
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
            if (!instant(rs, "expires_at").isAfter(now))
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_EXPIRED);
            return new Accepted(Objects.requireNonNull(rs.getString("task_id")), "PROCESSING");
        }, scope, operation, key);

        return rows.isEmpty() ? null : rows.getFirst();
    }

    public Accepted create(long userId, long companyId, String scope, String operation,
                           String key, String body, Request request, Instant now) {
        String id = UUID.randomUUID().toString();

        jdbc.update("""
                insert into ai_tasks(id, user_id, company_id, type, request_key_hash, request_body_hmac,
                    request_parameters, available_at, created_at)
                values (?, ?, ?, 'DISCLOSURE_SYNC', ?, ?, ?, ?, ?)
                """, id, userId, companyId, key, body,
                json(Parameters.initial(request)), utc(now), utc(now));

        var accepted = new Accepted(id, "PROCESSING");

        jdbc.update("""
                insert into api_idempotency_requests(user_id, task_id, scope_hash, operation, key_hash,
                    body_hmac, status, response_http_status, response_payload, created_at, expires_at)
                values (?, ?, ?, ?, ?, ?, 'SUCCEEDED', 202, ?, ?, ?)
                """, userId, id, scope, operation, key, body,
                json(accepted), utc(now), utc(now.plusSeconds(86400)));

        return accepted;
    }

    public Lease claim(Instant now) {
        var current = utc(now);

        var next = jdbc.queryForObject(
                "select next_call_at from disclosure_sync_control where id = 1 for update",
                LocalDateTime.class
        );
        if (Objects.requireNonNull(next).isAfter(current)) return null;

        if (Objects.requireNonNull(jdbc.queryForObject("""
                select count(*) from ai_tasks
                where type = 'DISCLOSURE_SYNC' and status = 'PROCESSING' and lease_until > ?
                """, Long.class, current)) != 0) return null;

        var tasks = jdbc.query("""
                select t.id, t.company_id, c.dart_corp_code, t.lease_version,
                    t.request_parameters, t.lease_owner
                from ai_tasks t join companies c on c.id = t.company_id
                where t.type = 'DISCLOSURE_SYNC' and t.status = 'PROCESSING' and t.available_at <= ?
                  and (t.lease_until is null or t.lease_until <= ?)
                order by t.available_at, t.created_at, t.id limit 1 for update
                """, (rs, n) -> new Object[]{
                rs.getString(1), rs.getLong(2), rs.getString(3),
                rs.getLong(4), rs.getString(5), rs.getString(6)
        }, current, current);

        if (tasks.isEmpty()) return null;

        var row = tasks.getFirst();
        String id = (String) row[0];
        String owner = UUID.randomUUID().toString();
        long version = (long) row[3] + 1;
        Parameters parameters = parameters((String) row[4]);

        // 프로세스가 결과를 커밋하지 못하고 종료한 실행도 이력에 남긴다.
        jdbc.update("""
                update ai_task_runs
                set status = 'FAILED', error_code = 'LEASE_EXPIRED', finished_at = ?
                where task_id = ? and status = 'PROCESSING'
                """, current, id);

        if (row[5] != null && parameters != null) {
            var c = parameters.cursor();
            parameters = new Parameters(parameters.request(),
                    new Cursor(c.fromDate(), c.page(), c.totalCount(),
                            c.previousHash(), c.retries() + 1));
        }

        jdbc.update("""
                update ai_tasks
                set lease_owner = ?, lease_until = ?, lease_version = ?, request_parameters = ?
                where id = ?
                """, owner, utc(now.plusSeconds(90)), version, json(parameters), id);

        jdbc.update("""
                insert into ai_task_runs(task_id, run_no, lease_version, provider, started_at)
                values (?, ?, ?, 'OpenDART', ?)
                """, id, version, version, current);

        jdbc.update(
                "update disclosure_sync_control set next_call_at = ? where id = 1",
                utc(now.plusSeconds(1))
        );

        return new Lease(id, (long) row[1], (String) row[2], owner, version, parameters);
    }

    public void savePage(Lease lease, Page page, Instant now) {
        if (!lockLease(lease, now)) return;

        var parameters = lease.parameters();
        var cursor = parameters.cursor();

        if ((cursor.totalCount() != null && cursor.totalCount() != page.totalCount())
                || (cursor.previousHash() != null && cursor.previousHash().equals(page.hash()))) {
            throw new DartFailure("DART_PAGE_CHANGED", true);
        }

        for (var filing : page.filings()) {
            if (!parameters.request().reportTypes().contains(filing.reportType())) continue;

            var done = jdbc.queryForObject("""
                    select count(*) from disclosure_sync_items
                    where task_id = ? and receipt_no = ?
                    """, Long.class, lease.taskId(), filing.receiptNo());

            if (Objects.requireNonNull(done) != 0)
                throw new DartFailure("DART_PAGE_CHANGED", true);

            var existing = jdbc.query("""
                    select company_id, report_name, report_type, disclosed_on
                    from disclosures where receipt_no = ? for update
                    """, (rs, n) ->
                            rs.getLong(1) == lease.companyId()
                                    && rs.getString(2).equals(filing.reportName())
                                    && rs.getString(3).equals(filing.reportType().name())
                                    && rs.getObject(4, LocalDate.class).equals(filing.disclosedOn()),
                    filing.receiptNo());

            String disposition = existing.isEmpty() ? "CREATED" : "EXISTING";
            String error = !existing.isEmpty() && !existing.getFirst()
                    ? "DART_METADATA_CHANGED" : null;

            if (existing.isEmpty()) jdbc.update("""
                    insert into disclosures(
                        company_id, receipt_no, report_name, report_type,
                        disclosed_on, source_url, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """, lease.companyId(), filing.receiptNo(), filing.reportName(),
                    filing.reportType().name(), filing.disclosedOn(),
                    "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=" + filing.receiptNo(),
                    utc(now), utc(now));

            jdbc.update("""
                    insert into disclosure_sync_items(
                        task_id, receipt_no, status, error_code, disposition, processed_at)
                    values (?, ?, ?, ?, ?, ?)
                    """, lease.taskId(), filing.receiptNo(),
                    error == null ? "SUCCEEDED" : "FAILED", error,
                    error == null ? disposition : null, utc(now));
        }

        LocalDate windowEnd = cursor.end(parameters.request().toDate());
        boolean lastPage = cursor.page() >= page.totalPages();
        boolean complete = lastPage && windowEnd.equals(parameters.request().toDate());

        Cursor next = lastPage
                ? new Cursor(windowEnd.plusDays(1), 1, null, null, 0)
                : new Cursor(cursor.fromDate(), cursor.page() + 1,
                page.totalCount(), page.hash(), 0);

        long failures = Objects.requireNonNull(jdbc.queryForObject("""
                select count(*) from disclosure_sync_items
                where task_id = ? and status = 'FAILED'
                """, Long.class, lease.taskId()));

        jdbc.update("""
                update ai_tasks
                set request_parameters = ?, status = ?, completed_at = ?, last_error_code = ?,
                    lease_owner = null, lease_until = null, available_at = ?
                where id = ?
                """, json(complete ? parameters : new Parameters(parameters.request(), next)),
                complete ? (failures == 0 ? "SUCCEEDED" : "FAILED") : "PROCESSING",
                complete ? utc(now) : null,
                complete && failures != 0 ? "DART_ITEM_FAILED" : null,
                utc(now.plusSeconds(1)), lease.taskId());

        finishRun(lease, "SUCCEEDED", null, now);
    }

    public void fail(Lease lease, String code, boolean retryable, Instant now) {
        if (!lockLease(lease, now)) return;

        var p = lease.parameters();
        boolean retry = retryable && p != null && p.cursor().retries() < 2;
        Parameters next = p;

        if (retry) {
            var c = p.cursor();
            next = new Parameters(p.request(),
                    new Cursor(c.fromDate(), c.page(), c.totalCount(),
                            c.previousHash(), c.retries() + 1));
        }

        // 키/IP/일일 한도 오류는 다른 작업에서도 연속 호출하지 않도록 1시간 중지한다.
        if (code.equals("DART_RATE_LIMIT") || code.equals("DART_AUTH_ERROR"))
            jdbc.update("""
                    update disclosure_sync_control
                    set next_call_at = greatest(next_call_at, ?) where id = 1
                    """, utc(now.plusSeconds(3600)));

        jdbc.update("""
                update ai_tasks
                set status = ?, request_parameters = ?, last_error_code = ?, completed_at = ?,
                    available_at = ?, lease_owner = null, lease_until = null
                where id = ?
                """, retry ? "PROCESSING" : "FAILED", json(next), code,
                retry ? null : utc(now),
                utc(now.plusSeconds(retry && p.cursor().retries() == 0 ? 5 : 10)),
                lease.taskId());

        finishRun(lease, "FAILED", code, now);
    }

    private boolean lockLease(Lease lease, Instant now) {
        // claim과 동일한 잠금 순서: control → task. 만료 워커의 뒤늦은 결과를 차단한다.
        jdbc.queryForObject(
                "select id from disclosure_sync_control where id = 1 for update",
                Integer.class
        );

        return !jdbc.query("""
                select id from ai_tasks
                where id = ? and type = 'DISCLOSURE_SYNC' and status = 'PROCESSING'
                  and lease_owner = ? and lease_version = ? and lease_until > ?
                for update
                """, (rs, n) -> rs.getString(1),
                lease.taskId(), lease.owner(), lease.version(), utc(now)).isEmpty();
    }

    private void finishRun(Lease lease, String status, String error, Instant now) {
        jdbc.update("""
                update ai_task_runs set status = ?, error_code = ?, finished_at = ?
                where task_id = ? and lease_version = ?
                """, status, error, utc(now), lease.taskId(), lease.version());
    }

    public TaskResult task(String id, Long companyId) {
        var rows = jdbc.query("""
                select * from ai_tasks where id = ? and type = 'DISCLOSURE_SYNC'
                """, (rs, n) -> {
            long company = rs.getLong("company_id");
            if (companyId != null && companyId != company)
                throw new BusinessException(ErrorCode.INVALID_INPUT);

            String error = rs.getString("last_error_code");
            var task = new Task(
                    id, rs.getString("status"),
                    error == null ? null : new TaskError(error, errorMessage(error)),
                    instant(rs, "created_at"), instant(rs, "completed_at")
            );

            return new TaskResult(task, company, 0, 0, 0, List.of());
        }, id);

        if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);

        var failures = jdbc.query("""
                select receipt_no, error_code from disclosure_sync_items
                where task_id = ? and status = 'FAILED' order by id
                """, (rs, n) ->
                        new Failure(rs.getString(1), rs.getString(2), errorMessage(rs.getString(2))),
                id);

        long collected = Objects.requireNonNull(jdbc.queryForObject("""
                select count(*) from disclosure_sync_items
                where task_id = ? and status = 'SUCCEEDED' and disposition = 'CREATED'
                """, Long.class, id));

        var result = rows.getFirst();
        return new TaskResult(
                result.task(), result.companyId(), collected, 0, failures.size(), failures
        );
    }

    public long countVersions(Search search) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                select count(*) from disclosure_versions v
                join disclosures d on d.id = v.disclosure_id
                where (? is null or d.company_id = ?) and (? is null or v.status = ?)
                """, Long.class,
                search.companyId(), search.companyId(), search.status(), search.status()));
    }

    public List<Version> versions(Search search, java.util.function.Function<String, String> logo) {
        var rows = jdbc.query("""
                select d.id as disclosure_id, d.version, d.receipt_no,
                    d.report_name, d.report_type, d.disclosed_on,
                    d.source_url, d.corrects_disclosure_id, d.updated_at,
                    c.id as company_id, c.name, c.industry, c.logo_key,
                    v.id as version_id, v.status, v.review_note, v.reviewed_by, v.reviewed_at
                from disclosure_versions v
                join disclosures d on d.id = v.disclosure_id
                join companies c on c.id = d.company_id
                where (? is null or d.company_id = ?) and (? is null or v.status = ?)
                order by d.disclosed_on desc, d.id desc, v.id desc limit ? offset ?
                """, (rs, n) -> {
                    return new Version(
                            rs.getLong("disclosure_id"), rs.getLong("version_id"),
                            new Company(
                                    rs.getLong("company_id"), rs.getString("name"),
                                    rs.getString("industry"), logo.apply(rs.getString("logo_key"))
                            ),
                            rs.getString("receipt_no"), rs.getString("report_name"),
                            rs.getString("report_type"), rs.getObject("disclosed_on", LocalDate.class),
                            rs.getString("source_url"), rs.getString("status"), rs.getLong("version"),
                            List.of(), rs.getObject("corrects_disclosure_id", Long.class),
                            rs.getString("review_note"), rs.getObject("reviewed_by", Long.class),
                            instant(rs, "reviewed_at"), instant(rs, "updated_at")
                    );
                }, search.companyId(), search.companyId(), search.status(), search.status(),
                search.size(), (long) search.page() * search.size());

        if (rows.isEmpty()) return rows;

        Map<Long, List<Section>> sections = new LinkedHashMap<>();
        for (var row : rows) sections.put(row.disclosureVersionId(), new ArrayList<>());

        String marks = String.join(",", java.util.Collections.nCopies(rows.size(), "?"));

        jdbc.query("""
                select * from disclosure_sections where disclosure_version_id in (
                """ + marks + ") order by id", rs -> {
            sections.get(rs.getLong("disclosure_version_id")).add(
                    new Section(
                            rs.getLong("id"), rs.getString("heading"), rs.getString("content_text")
                    )
            );
        }, sections.keySet().toArray());

        return rows.stream()
                .map(row -> row.withSections(sections.get(row.disclosureVersionId())))
                .toList();
    }

    public void clearExpiredResponses(Instant now) {
        jdbc.update("""
                update api_idempotency_requests set response_payload = null
                where operation like 'POST /v1/admin/companies/%/disclosure-sync-tasks'
                  and expires_at <= ? and response_payload is not null limit 1000
                """, utc(now));
    }

    private Parameters parameters(String value) {
        try {
            var p = mapper.readValue(value, Parameters.class);
            if (p == null || p.request() == null || p.cursor() == null
                    || p.cursor().fromDate() == null
                    || p.cursor().fromDate().isBefore(p.request().fromDate())
                    || p.cursor().fromDate().isAfter(p.request().toDate())
                    || p.cursor().page() < 1 || p.cursor().retries() < 0) return null;
            return p;
        } catch (Exception ex) {
            return null;
        }
    }

    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) {
            throw new IllegalStateException("Disclosure state serialization failed");
        }
    }

    public static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static String errorMessage(String code) {
        return switch (code) {
            case "DART_AUTH_ERROR", "DART_NOT_CONFIGURED" ->
                    "OpenDART 인증 설정을 확인해 주세요.";
            case "DART_RATE_LIMIT" ->
                    "OpenDART 호출 한도를 확인한 후 다시 요청해 주세요.";
            case "DART_METADATA_CHANGED" ->
                    "기존 공시와 조회 정보가 달라 확인이 필요합니다.";
            default ->
                    "공시 수집을 완료하지 못했습니다. 작업 내용을 확인하고 다시 요청해 주세요.";
        };
    }
}