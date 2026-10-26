package com.dadot.server.domain.disclosure.repository;

import com.dadot.server.domain.disclosure.model.DisclosureReview;
import com.dadot.server.domain.disclosure.model.DisclosureReview.Status;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Company;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Version;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

@Repository
@RequiredArgsConstructor
public class DisclosureReviewRepository {

    private final JdbcTemplate jdbc;

    public record Filing(
            long id,
            long companyId,
            long version,
            Long correctionOf
    ) {}

    public record Content(
            long id,
            Status status
    ) {}

    public long companyId(long disclosureId) {
        return jdbc.query(
                """
                select company_id
                from disclosures
                where id = ?
                """,
                (rs, n) -> rs.getLong(1),
                disclosureId
        ).stream().findFirst().orElseThrow(
                DisclosureReviewRepository::missing
        );
    }

    public void lockCompany(long companyId) {
        if (jdbc.query(
                """
                select id
                from companies
                where id = ?
                for update
                """,
                (rs, n) -> rs.getLong(1),
                companyId
        ).isEmpty()) {
            throw missing();
        }
    }

    public Filing lockFiling(long id) {
        return jdbc.query(
                """
                select id, company_id, version, corrects_disclosure_id
                from disclosures
                where id = ?
                for update
                """,
                (rs, n) -> new Filing(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getLong(3),
                        rs.getObject(4, Long.class)
                ),
                id
        ).stream().findFirst().orElseThrow(
                DisclosureReviewRepository::missing
        );
    }

    public Content lockContent(
            long disclosureId,
            long versionId
    ) {
        return jdbc.query(
                """
                select id, status
                from disclosure_versions
                where id = ?
                  and disclosure_id = ?
                for update
                """,
                (rs, n) -> new Content(
                        rs.getLong(1),
                        Status.valueOf(rs.getString(2))
                ),
                versionId,
                disclosureId
        ).stream().findFirst().orElseThrow(
                DisclosureReviewRepository::missing
        );
    }

    public boolean hasProtectedHistory(long disclosureId) {
        return count(
                """
                select count(*)
                from disclosure_versions
                where disclosure_id = ?
                  and status in (
                      'PUBLISHED', 'SUSPENDED', 'SUPERSEDED'
                  )
                """,
                disclosureId
        ) > 0;
    }

    public boolean hasCorrection(long disclosureId) {
        return count(
                """
                select count(*)
                from disclosures
                where corrects_disclosure_id = ?
                """,
                disclosureId
        ) > 0;
    }

    public boolean hasReplacement(long disclosureId) {
        return count(
                """
                select count(*)
                from disclosures d
                where d.corrects_disclosure_id = ?
                  and exists (
                      select 1
                      from disclosure_versions v
                      where v.disclosure_id = d.id
                        and v.status in (
                            'PUBLISHED', 'SUSPENDED', 'SUPERSEDED'
                        )
                  )
                """,
                disclosureId
        ) > 0;
    }

    public boolean hasOtherReplacement(
            long disclosureId,
            long sourceId
    ) {
        return count(
                """
                select count(*)
                from disclosures d
                where d.corrects_disclosure_id = ?
                  and d.id <> ?
                  and exists (
                      select 1
                      from disclosure_versions v
                      where v.disclosure_id = d.id
                        and v.status in (
                            'PUBLISHED', 'SUSPENDED', 'SUPERSEDED'
                        )
                  )
                """,
                disclosureId,
                sourceId
        ) > 0;
    }

    public boolean hasPublishedSibling(
            long disclosureId,
            long versionId
    ) {
        return count(
                """
                select count(*)
                from disclosure_versions
                where disclosure_id = ?
                  and id <> ?
                  and status = 'PUBLISHED'
                """,
                disclosureId,
                versionId
        ) > 0;
    }

    public void validateCorrection(
            Filing source,
            Long targetId
    ) {
        if (targetId == null) {
            return;
        }

        var seen = new HashSet<Long>();
        seen.add(source.id());

        Long cursor = targetId;

        while (cursor != null) {
            if (!seen.add(cursor)) {
                throw DisclosureReview.transition();
            }

            var rows = jdbc.query(
                    """
                    select id, company_id, version, corrects_disclosure_id
                    from disclosures
                    where id = ?
                    """,
                    (rs, n) -> new Filing(
                            rs.getLong(1),
                            rs.getLong(2),
                            rs.getLong(3),
                            rs.getObject(4, Long.class)
                    ),
                    cursor
            );

            if (rows.isEmpty()
                    || rows.getFirst().companyId() != source.companyId()) {
                throw missing();
            }

            cursor = rows.getFirst().correctionOf();
        }
    }

    public void requireOriginal(long versionId) {
        if (count(
                """
                select count(*)
                from disclosure_versions v
                where v.id = ?
                  and v.original_object_key is not null
                  and v.original_object_key <> ''
                  and exists (
                      select 1
                      from disclosure_sections s
                      where s.disclosure_version_id = v.id
                  )
                """,
                versionId
        ) != 1) {
            throw DisclosureReview.transition();
        }
    }

    public void setCorrection(
            Filing filing,
            Long target,
            long actor,
            String reason,
            Instant now
    ) {
        jdbc.update(
                """
                update disclosures
                set corrects_disclosure_id = ?
                where id = ?
                """,
                target,
                filing.id()
        );

        audit(
                actor,
                "DISCLOSURE_CORRECTION_CHANGED",
                "DISCLOSURE",
                filing.id(),
                "correctionOf: "
                        + filing.correctionOf()
                        + " -> "
                        + target
                        + "; "
                        + reason,
                now
        );
    }

    public void change(
            Content content,
            Status next,
            long actor,
            String reason,
            Instant now
    ) {
        int changed = jdbc.update(
                """
                update disclosure_versions
                set status = ?,
                    review_note = ?,
                    reviewed_by = ?,
                    reviewed_at = ?,
                    revision_notice = case
                        when ? in ('SUSPENDED', 'SUPERSEDED')
                        then ?
                        else revision_notice
                    end
                where id = ?
                  and status = ?
                """,
                next.name(),
                reason,
                actor,
                utc(now),
                next.name(),
                reason,
                content.id(),
                content.status().name()
        );

        if (changed != 1) {
            throw new BusinessException(
                    ErrorCode.VERSION_CONFLICT
            );
        }

        audit(
                actor,
                "DISCLOSURE_" + content.status() + "_TO_" + next,
                "DISCLOSURE_VERSION",
                content.id(),
                reason,
                now
        );
    }

    public boolean hasNewerPublication(
            long disclosureId,
            long versionId
    ) {
        return count(
                """
                select count(*)
                from disclosure_versions
                where disclosure_id = ?
                  and version_no > (
                      select version_no
                      from disclosure_versions
                      where id = ?
                  )
                  and status in (
                      'PUBLISHED', 'SUSPENDED', 'SUPERSEDED'
                  )
                """,
                disclosureId,
                versionId
        ) > 0;
    }

    public void supersedeOlder(
            long disclosureId,
            long versionId,
            long actor,
            String reason,
            Instant now
    ) {
        var rows = jdbc.query(
                """
                select id, status
                from disclosure_versions
                where disclosure_id = ?
                  and id <> ?
                  and status <> 'SUPERSEDED'
                  and (
                      status in ('PUBLISHED', 'SUSPENDED')
                      or version_no < (
                          select version_no
                          from disclosure_versions
                          where id = ?
                      )
                  )
                order by id
                for update
                """,
                (rs, n) -> new Content(
                        rs.getLong(1),
                        Status.valueOf(rs.getString(2))
                ),
                disclosureId,
                versionId,
                versionId
        );

        for (var row : rows) {
            change(
                    row,
                    Status.SUPERSEDED,
                    actor,
                    reason,
                    now
            );
        }
    }

    public boolean retireTarget(
            long disclosureId,
            Status next,
            long actor,
            String reason,
            Instant now
    ) {
        // 정정본 게시 시 대상 공시 전체를 대체한다.
        // 연결 단계에서는 게시본만 중단한다.
        List<Content> rows = jdbc.query(
                """
                select id, status
                from disclosure_versions
                where disclosure_id = ?
                  and status <> 'SUPERSEDED'
                  and (
                      ? = 'SUPERSEDED'
                      or status = 'PUBLISHED'
                  )
                order by id
                for update
                """,
                (rs, n) -> new Content(
                        rs.getLong(1),
                        Status.valueOf(rs.getString(2))
                ),
                disclosureId,
                next.name()
        );

        for (var row : rows) {
            change(
                    row,
                    next,
                    actor,
                    reason,
                    now
            );
        }

        return !rows.isEmpty();
    }

    public void touch(
            long disclosureId,
            Instant now
    ) {
        if (jdbc.update(
                """
                update disclosures
                set version = version + 1,
                    updated_at = ?
                where id = ?
                  and version < ?
                """,
                utc(now),
                disclosureId,
                DisclosureReview.MAX_ID
        ) != 1) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }

    public Version summary(
            long disclosureId,
            long versionId,
            Function<String, String> logo
    ) {
        return jdbc.query(
                """
                select d.id,
                       d.version,
                       d.receipt_no,
                       d.report_name,
                       d.report_type,
                       d.disclosed_on,
                       d.source_url,
                       d.corrects_disclosure_id,
                       d.updated_at,
                       c.id company_id,
                       c.name,
                       c.industry,
                       c.logo_key,
                       v.id version_id,
                       v.status,
                       v.review_note,
                       v.reviewed_by,
                       v.reviewed_at,
                       (
                           select count(*)
                           from disclosure_sections s
                           where s.disclosure_version_id = v.id
                       ) section_count
                from disclosures d
                join companies c
                  on c.id = d.company_id
                join disclosure_versions v
                  on v.disclosure_id = d.id
                where d.id = ?
                  and v.id = ?
                """,
                (rs, n) -> new Version(
                        rs.getLong("id"),
                        rs.getLong("version_id"),
                        new Company(
                                rs.getLong("company_id"),
                                rs.getString("name"),
                                rs.getString("industry"),
                                logo.apply(rs.getString("logo_key"))
                        ),
                        rs.getString("receipt_no"),
                        rs.getString("report_name"),
                        rs.getString("report_type"),
                        rs.getObject("disclosed_on", LocalDate.class),
                        rs.getString("source_url"),
                        rs.getString("status"),
                        rs.getLong("version"),
                        rs.getLong("section_count"),
                        rs.getObject("corrects_disclosure_id", Long.class),
                        rs.getString("review_note"),
                        rs.getObject("reviewed_by", Long.class),
                        instant(rs.getObject("reviewed_at", LocalDateTime.class)),
                        instant(rs.getObject("updated_at", LocalDateTime.class))
                ),
                disclosureId,
                versionId
        ).stream().findFirst().orElseThrow(
                DisclosureReviewRepository::missing
        );
    }

    private void audit(
            long actor,
            String action,
            String type,
            long target,
            String reason,
            Instant now
    ) {
        jdbc.update(
                """
                insert into admin_audit_events(
                    actor_user_id,
                    action,
                    target_type,
                    target_id,
                    reason,
                    occurred_at
                )
                values (?, ?, ?, ?, ?, ?)
                """,
                actor,
                action,
                type,
                Long.toString(target),
                reason,
                utc(now)
        );
    }

    private long count(
            String sql,
            Object... args
    ) {
        return Objects.requireNonNull(
                jdbc.queryForObject(sql, Long.class, args)
        );
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(
                value,
                ZoneOffset.UTC
        );
    }

    private static Instant instant(LocalDateTime value) {
        return value == null
                ? null
                : value.toInstant(ZoneOffset.UTC);
    }

    private static BusinessException missing() {
        return new BusinessException(
                ErrorCode.RESOURCE_NOT_FOUND
        );
    }
}