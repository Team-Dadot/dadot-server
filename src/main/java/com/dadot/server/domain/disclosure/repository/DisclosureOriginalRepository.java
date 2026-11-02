package com.dadot.server.domain.disclosure.repository;

import com.dadot.server.domain.disclosure.model.DisclosureOriginal.Extracted;
import com.dadot.server.domain.disclosure.model.DisclosureOriginal.Stored;
import com.dadot.server.domain.disclosure.model.DisclosureOriginal.Work;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Lease;
import com.dadot.server.infrastructure.dart.DartArchiveExtractor;
import com.dadot.server.infrastructure.dart.DartFailure;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;

import static com.dadot.server.domain.disclosure.repository.DisclosureRepository.utc;

@Repository
@RequiredArgsConstructor
public class DisclosureOriginalRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    // 부모 작업과 control 행을 잠근 트랜잭션에서 호출한다.
    public Work claim(String taskId, Instant now) {
        var rows = jdbc.query("""
                select i.id, i.receipt_no, d.report_type, i.attempts
                from disclosure_sync_items i
                join ai_tasks t on t.id = i.task_id
                left join disclosures d
                  on d.receipt_no = i.receipt_no
                 and d.company_id = t.company_id
                where i.task_id = ?
                  and i.status = 'PROCESSING'
                  and i.available_at <= ?
                order by i.available_at, i.id
                limit 1 for update
                """,
                (rs, n) -> new Work(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getInt(4) + 1
                ),
                taskId,
                utc(now)
        );

        if (rows.isEmpty()) {
            return null;
        }

        var work = rows.getFirst();

        jdbc.update("""
                update disclosure_sync_items
                set attempts = least(attempts + 1, 3)
                where id = ?
                """, work.itemId());

        return work;
    }

    public void complete(
            Lease lease,
            Stored stored,
            Extracted document,
            Instant now
    ) {
        if (!fence(lease, now)) {
            return;
        }

        var work = Objects.requireNonNull(lease.document());

        var filings = jdbc.query("""
                select id
                from disclosures
                where company_id = ? and receipt_no = ?
                for update
                """,
                (rs, n) -> rs.getLong(1),
                lease.companyId(),
                work.receiptNo()
        );

        if (filings.isEmpty()) {
            throw new IllegalStateException(
                    "Original filing disappeared"
            );
        }

        long disclosure = filings.getFirst();

        var existing = jdbc.query("""
                select v.id, v.original_object_key,
                    (
                        select count(*)
                        from disclosure_sections s
                        where s.disclosure_version_id = v.id
                    ) as sections
                from disclosure_versions v
                where v.disclosure_id = ?
                  and v.content_hash = ?
                  and v.extractor_version = ?
                """,
                (rs, n) -> new Existing(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getLong(3)
                ),
                disclosure,
                document.contentHash(),
                DartArchiveExtractor.VERSION
        );

        long version;
        String result;

        if (!existing.isEmpty()) {
            var previous = existing.getFirst();

            if (previous.sections() == 0) {
                throw new DartFailure(
                        "DART_EXISTING_CONTENT_INVALID",
                        false
                );
            }

            version = previous.id();
            result = "UNCHANGED";

            if (previous.objectKey() == null) {
                // 본문·검수 상태·근거 ID는 유지하고 보관 위치만 보완한다.
                jdbc.update("""
                        update disclosure_versions
                        set original_object_key = ?
                        where id = ?
                          and original_object_key is null
                        """,
                        stored.key(),
                        version
                );

                jdbc.update("""
                        update disclosures
                        set version = version + 1,
                            updated_at = ?
                        where id = ?
                        """,
                        utc(now),
                        disclosure
                );
            }

        } else {
            int number = Math.addExact(
                    Objects.requireNonNull(
                            jdbc.queryForObject("""
                                    select coalesce(max(version_no), 0)
                                    from disclosure_versions
                                    where disclosure_id = ?
                                    """,
                                    Integer.class,
                                    disclosure
                            )
                    ),
                    1
            );

            var key = new GeneratedKeyHolder();

            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                        insert into disclosure_versions(
                            disclosure_id,
                            company_id,
                            version_no,
                            original_object_key,
                            content_hash,
                            extractor_version,
                            created_at)
                        values (?, ?, ?, ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);

                statement.setLong(1, disclosure);
                statement.setLong(2, lease.companyId());
                statement.setInt(3, number);
                statement.setString(4, stored.key());
                statement.setString(5, document.contentHash());
                statement.setString(
                        6,
                        DartArchiveExtractor.VERSION
                );
                statement.setObject(7, utc(now));

                return statement;
            }, key);

            version = Objects.requireNonNull(key.getKey())
                    .longValue();

            result = number == 1 ? "CREATED" : "UPDATED";

            long versionId = version;

            jdbc.batchUpdate("""
                    insert into disclosure_sections(
                        disclosure_version_id,
                        company_id,
                        section_key,
                        heading,
                        content_text,
                        locator_json)
                    values (?, ?, ?, ?, ?, ?)
                    """,
                    document.blocks(),
                    10,
                    (statement, block) -> {
                        statement.setLong(1, versionId);
                        statement.setLong(2, lease.companyId());
                        statement.setString(3, block.key());
                        statement.setString(4, block.heading());
                        statement.setString(5, block.text());
                        statement.setString(
                                6,
                                mapper.writeValueAsString(
                                        block.locator()
                                )
                        );
                    }
            );

            jdbc.update("""
                    update disclosures
                    set version = version + 1,
                        updated_at = ?
                    where id = ?
                    """,
                    utc(now),
                    disclosure
            );
        }

        int changed = jdbc.update("""
                update disclosure_sync_items
                set status = 'SUCCEEDED',
                    content_result = ?,
                    disclosure_version_id = ?,
                    error_code = null,
                    processed_at = ?
                where id = ?
                  and task_id = ?
                  and status = 'PROCESSING'
                """,
                result,
                version,
                utc(now),
                work.itemId(),
                lease.taskId()
        );

        if (changed != 1) {
            throw new IllegalStateException(
                    "Original work state changed"
            );
        }

        settle(lease, null, now);
        finishRun(lease, "SUCCEEDED", null, now);
    }

    public void fail(
            Lease lease,
            String code,
            boolean retryable,
            Instant now
    ) {
        if (!fence(lease, now)) {
            return;
        }

        var work = Objects.requireNonNull(lease.document());

        boolean retry = retryable && work.attempt() < 3;

        int changed = jdbc.update("""
                update disclosure_sync_items
                set status = ?,
                    error_code = ?,
                    processed_at = ?,
                    available_at = ?
                where id = ?
                  and task_id = ?
                  and status = 'PROCESSING'
                """,
                retry ? "PROCESSING" : "FAILED",
                code,
                retry ? null : utc(now),
                utc(now.plusSeconds(
                        work.attempt() == 1 ? 5 : 10
                )),
                work.itemId(),
                lease.taskId()
        );

        if (changed != 1) {
            throw new IllegalStateException(
                    "Original work state changed"
            );
        }

        if (code.equals("DART_RATE_LIMIT")
                || code.equals("DART_AUTH_ERROR")) {
            jdbc.update("""
                    update disclosure_sync_control
                    set next_call_at = greatest(next_call_at, ?)
                    where id = 1
                    """, utc(now.plusSeconds(3600)));
        }

        settle(lease, retry ? code : null, now);
        finishRun(lease, "FAILED", code, now);
    }

    public void idle(Lease lease, Instant now) {
        if (!fence(lease, now)) {
            return;
        }

        settle(lease, null, now);
        finishRun(lease, "SUCCEEDED", null, now);
    }

    private void settle(
            Lease lease,
            String retryCode,
            Instant now
    ) {
        var next = jdbc.queryForObject("""
                select min(available_at)
                from disclosure_sync_items
                where task_id = ? and status = 'PROCESSING'
                """,
                LocalDateTime.class,
                lease.taskId()
        );

        boolean failed = Objects.requireNonNull(
                jdbc.queryForObject("""
                        select count(*)
                        from disclosure_sync_items
                        where task_id = ? and status = 'FAILED'
                        """,
                        Long.class,
                        lease.taskId()
                )
        ) > 0;

        jdbc.update("""
                update ai_tasks
                set status = ?,
                    last_error_code = ?,
                    completed_at = ?,
                    available_at = ?,
                    lease_owner = null,
                    lease_until = null
                where id = ?
                """,
                next == null
                        ? (failed ? "FAILED" : "SUCCEEDED")
                        : "PROCESSING",
                next == null && failed
                        ? "DART_ITEM_FAILED"
                        : retryCode,
                next == null ? utc(now) : null,
                next == null ? utc(now) : next,
                lease.taskId()
        );
    }

    private boolean fence(Lease lease, Instant now) {
        jdbc.queryForObject("""
                select id
                from disclosure_sync_control
                where id = 1
                for update
                """, Integer.class);

        return !jdbc.query("""
                select id
                from ai_tasks
                where id = ?
                  and status = 'PROCESSING'
                  and type = 'DISCLOSURE_SYNC'
                  and lease_owner = ?
                  and lease_version = ?
                  and lease_until > ?
                for update
                """,
                (rs, n) -> rs.getString(1),
                lease.taskId(),
                lease.owner(),
                lease.version(),
                utc(now)
        ).isEmpty();
    }

    private void finishRun(
            Lease lease,
            String state,
            String code,
            Instant now
    ) {
        jdbc.update("""
                update ai_task_runs
                set status = ?,
                    error_code = ?,
                    finished_at = ?
                where task_id = ? and lease_version = ?
                """,
                state,
                code,
                utc(now),
                lease.taskId(),
                lease.version()
        );
    }

    private record Existing(
            long id,
            String objectKey,
            long sections
    ) {}
}