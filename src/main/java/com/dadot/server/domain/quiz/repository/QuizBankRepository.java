package com.dadot.server.domain.quiz.repository;

import com.dadot.server.domain.quiz.model.QuizBank;
import com.dadot.server.domain.quiz.model.QuizBank.*;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class QuizBankRepository {

    private final JdbcTemplate jdbc;

    public record Question(
            long id,
            long companyId,
            long revision,
            Integer publishedNo
    ) {}

    public record Version(
            long id,
            long questionId,
            int number,
            Status status
    ) {}

    private record Source(
            long id,
            long versionId,
            long companyId,
            String text
    ) {}

    private record Row(
            long id,
            long questionId,
            long companyId,
            long revision,
            Status status,
            String question,
            String explanation,
            String note,
            Instant updated
    ) {}

    private record OptionRow(
            long versionId,
            Option option,
            boolean correct
    ) {}

    private record EvidenceRow(
            long versionId,
            Evidence evidence
    ) {}

    public void lockCompany(long companyId) {
        if (jdbc.query(
                "select id from companies where id = ? for update",
                (rs, n) -> rs.getLong(1),
                companyId
        ).isEmpty()) {
            throw QuizBank.missing();
        }
    }

    public long companyId(long questionId) {
        return jdbc.query(
                "select company_id from quiz_questions where id = ?",
                (rs, n) -> rs.getLong(1),
                questionId
        ).stream().findFirst().orElseThrow(QuizBank::missing);
    }

    public Question lockQuestion(long questionId) {
        return jdbc.query(
                """
                select id, company_id, version, published_version_no
                from quiz_questions
                where id = ?
                for update
                """,
                (rs, n) -> new Question(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getLong(3),
                        rs.getObject(4, Integer.class)
                ),
                questionId
        ).stream().findFirst().orElseThrow(QuizBank::missing);
    }

    public Version lockVersion(
            long questionId,
            long versionId
    ) {
        return jdbc.query(
                """
                select id, question_id, version_no, status
                from quiz_question_versions
                where id = ?
                  and question_id = ?
                for update
                """,
                (rs, n) -> version(rs),
                versionId,
                questionId
        ).stream().findFirst().orElseThrow(QuizBank::missing);
    }

    public void validateReferences(Content content) {
        var ids = content.evidence().stream()
                .map(InputEvidence::sectionId)
                .toList();

        var rows = jdbc.query(
                """
                select s.id,
                       s.disclosure_version_id,
                       s.company_id,
                       s.content_text
                from disclosure_sections s
                where s.id in (
                """ + marks(ids.size()) + ")",
                (rs, n) -> new Source(
                        rs.getLong(1),
                        rs.getLong(2),
                        rs.getLong(3),
                        rs.getString(4)
                ),
                ids.toArray()
        );

        var sources = rows.stream().collect(
                Collectors.toMap(
                        Source::id,
                        source -> source
                )
        );

        for (var item : content.evidence()) {
            var source = sources.get(item.sectionId());

            if (source == null
                    || source.companyId() != content.companyId()
                    || source.versionId() != item.disclosureVersionId()) {
                throw QuizBank.missing();
            }

            if (!source.text().contains(item.quote())) {
                throw QuizBank.invalid();
            }
        }
    }

    public long createQuestion(
            long companyId,
            Instant now
    ) {
        return insert(
                """
                insert into quiz_questions(
                    company_id, created_at, updated_at
                )
                values (?, ?, ?)
                """,
                companyId,
                utc(now),
                utc(now)
        );
    }

    public long createVersion(
            long questionId,
            Content content,
            String note,
            Instant now
    ) {
        int maximum = Objects.requireNonNull(jdbc.queryForObject(
                """
                select coalesce(max(version_no), 0)
                from quiz_question_versions
                where question_id = ?
                """,
                Integer.class,
                questionId
        ));

        if (maximum == Integer.MAX_VALUE) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }

        long id = insert(
                """
                insert into quiz_question_versions(
                    question_id,
                    company_id,
                    version_no,
                    status,
                    question_text,
                    explanation,
                    review_note,
                    created_at
                )
                values (?, ?, ?, 'DRAFT', ?, ?, ?, ?)
                """,
                questionId,
                content.companyId(),
                maximum + 1,
                content.question(),
                content.explanation(),
                note,
                utc(now)
        );

        jdbc.batchUpdate(
                """
                insert into quiz_options(
                    quiz_version_id, position, option_text, is_correct
                )
                values (?, ?, ?, ?)
                """,
                content.options(),
                4,
                (s, option) -> {
                    s.setLong(1, id);
                    s.setInt(2, option.position());
                    s.setString(3, option.text());
                    s.setBoolean(
                            4,
                            option.position() == content.correctOptionPosition()
                    );
                }
        );

        jdbc.batchUpdate(
                """
                insert into quiz_evidence(
                    quiz_version_id, section_id, company_id, quotation
                )
                values (?, ?, ?, ?)
                """,
                content.evidence(),
                QuizBank.MAX_EVIDENCE,
                (s, item) -> {
                    s.setLong(1, id);
                    s.setLong(2, item.sectionId());
                    s.setLong(3, content.companyId());
                    s.setString(4, item.quote());
                }
        );

        return id;
    }

    public void requireReady(long versionId) {
        var content = view(versionId);

        if (content.options().size() != 4
                || content.evidence().isEmpty()
                || count(
                """
                select count(*)
                from quiz_options
                where quiz_version_id = ?
                  and is_correct = true
                """,
                versionId
        ) != 1) {
            throw QuizBank.unavailable();
        }

        List<InputEvidence> evidence = content.evidence().stream()
                .map(e -> new InputEvidence(
                        e.disclosureVersionId(),
                        e.sectionId(),
                        e.quote()
                ))
                .toList();

        int correct = content.options().stream()
                .filter(o -> o.optionId() == content.correctOptionId())
                .mapToInt(Option::position)
                .findFirst()
                .orElseThrow(QuizBank::unavailable);

        try {
            validateReferences(new Content(
                    content.companyId(),
                    content.question(),
                    content.options().stream()
                            .map(o -> new InputOption(
                                    o.position(),
                                    o.text()
                            ))
                            .toList(),
                    correct,
                    content.explanation(),
                    evidence
            ));

        } catch (BusinessException ex) {
            throw QuizBank.unavailable();
        }

        if (count(
                """
                select count(*)
                from quiz_evidence e
                join disclosure_sections s
                  on s.id = e.section_id
                join disclosure_versions v
                  on v.id = s.disclosure_version_id
                where e.quiz_version_id = ?
                  and (
                      v.status <> 'PUBLISHED'
                      or exists (
                          select 1
                          from disclosures correction
                          where correction.corrects_disclosure_id
                              = v.disclosure_id
                      )
                  )
                """,
                versionId
        ) > 0) {
            throw QuizBank.unavailable();
        }
    }

    public boolean hasNewerPublication(Version target) {
        return count(
                """
                select count(*)
                from quiz_question_versions
                where question_id = ?
                  and version_no > ?
                  and (
                      published_at is not null
                      or status = 'SUPERSEDED'
                  )
                """,
                target.questionId(),
                target.number()
        ) > 0;
    }

    public void supersedeOlder(
            Version target,
            long actor,
            String reason,
            Instant now
    ) {
        var rows = jdbc.query(
                """
                select id, question_id, version_no, status
                from quiz_question_versions
                where question_id = ?
                  and id <> ?
                  and status <> 'SUPERSEDED'
                  and (
                      version_no < ?
                      or status in ('PUBLISHED', 'SUSPENDED')
                  )
                order by id
                for update
                """,
                (rs, n) -> version(rs),
                target.questionId(),
                target.id(),
                target.number()
        );

        for (var row : rows) {
            changeStatus(
                    row,
                    Status.SUPERSEDED,
                    actor,
                    reason,
                    now
            );
        }
    }

    public void changeStatus(
            Version target,
            Status next,
            long actor,
            String note,
            Instant now
    ) {
        if (jdbc.update(
                """
                update quiz_question_versions
                set status = ?,
                    review_note = ?,
                    reviewed_by = ?,
                    reviewed_at = ?,
                    published_at = case
                        when ? = 'PUBLISHED'
                        then coalesce(published_at, ?)
                        else published_at
                    end
                where id = ?
                  and status = ?
                """,
                next.name(),
                note,
                actor,
                utc(now),
                next.name(),
                utc(now),
                target.id(),
                target.status().name()
        ) != 1) {
            throw new BusinessException(
                    ErrorCode.VERSION_CONFLICT
            );
        }

        audit(
                actor,
                "QUIZ_" + target.status() + "_TO_" + next,
                target.id(),
                note,
                now
        );
    }

    public void publication(
            long questionId,
            Integer number
    ) {
        jdbc.update(
                """
                update quiz_questions
                set published_version_no = ?
                where id = ?
                """,
                number,
                questionId
        );
    }

    public void touch(
            long questionId,
            Instant now
    ) {
        if (jdbc.update(
                """
                update quiz_questions
                set version = version + 1,
                    updated_at = ?
                where id = ?
                  and version < ?
                """,
                utc(now),
                questionId,
                QuizBank.MAX_ID
        ) != 1) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }

    public void audit(
            long actor,
            String action,
            long versionId,
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
                values (?, ?, 'QUIZ_VERSION', ?, ?, ?)
                """,
                actor,
                action,
                Long.toString(versionId),
                reason,
                utc(now)
        );
    }

    public View view(long versionId) {
        var rows = jdbc.query(
                base() + " where v.id = ?",
                (rs, n) -> row(rs),
                versionId
        );

        if (rows.isEmpty()) {
            throw QuizBank.missing();
        }

        return assemble(rows).getFirst();
    }

    public Page search(Search search) {
        String filter =
                " where (? is null or q.company_id = ?)"
                        + " and (? is null or v.status = ?)";

        String state = search.status() == null
                ? null
                : search.status().name();

        long total = count(
                """
                select count(*)
                from quiz_question_versions v
                join quiz_questions q
                  on q.id = v.question_id
                """ + filter,
                search.companyId(),
                search.companyId(),
                state,
                state
        );

        var rows = jdbc.query(
                base() + filter
                        + " order by q.updated_at desc, q.id desc, v.id desc"
                        + " limit ? offset ?",
                (rs, n) -> row(rs),
                search.companyId(),
                search.companyId(),
                state,
                state,
                search.size(),
                (long) search.page() * search.size()
        );

        return new Page(
                assemble(rows),
                search.page(),
                search.size(),
                total,
                (total + search.size() - 1) / search.size(),
                ((long) search.page() + 1) * search.size() < total
        );
    }

    private List<View> assemble(List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }

        Object[] ids = rows.stream()
                .map(Row::id)
                .toArray();

        var options = jdbc.query(
                """
                select id,
                       quiz_version_id,
                       position,
                       option_text,
                       is_correct
                from quiz_options
                where quiz_version_id in (
                """ + marks(ids.length)
                        + ") order by quiz_version_id, position",
                (rs, n) -> new OptionRow(
                        rs.getLong(2),
                        new Option(
                                rs.getLong(1),
                                rs.getInt(3),
                                rs.getString(4)
                        ),
                        rs.getBoolean(5)
                ),
                ids
        );

        var evidence = jdbc.query(
                """
                select e.quiz_version_id,
                       d.id,
                       v.id,
                       s.id,
                       d.report_name,
                       d.source_url,
                       s.heading,
                       e.quotation,
                       v.status = 'SUPERSEDED'
                from quiz_evidence e
                join disclosure_sections s
                  on s.id = e.section_id
                join disclosure_versions v
                  on v.id = s.disclosure_version_id
                join disclosures d
                  on d.id = v.disclosure_id
                where e.quiz_version_id in (
                """ + marks(ids.length)
                        + ") order by e.quiz_version_id, e.section_id",
                (rs, n) -> new EvidenceRow(
                        rs.getLong(1),
                        new Evidence(
                                rs.getLong(2),
                                rs.getLong(3),
                                rs.getLong(4),
                                rs.getString(5),
                                rs.getString(6),
                                rs.getString(7),
                                rs.getString(8),
                                rs.getBoolean(9)
                        )
                ),
                ids
        );

        Map<Long, List<Option>> optionMap = new HashMap<>();
        Map<Long, Long> correctMap = new HashMap<>();

        for (var item : options) {
            optionMap.computeIfAbsent(
                    item.versionId(),
                    ignored -> new ArrayList<>()
            ).add(item.option());

            if (item.correct()) {
                correctMap.put(
                        item.versionId(),
                        item.option().optionId()
                );
            }
        }

        Map<Long, List<Evidence>> evidenceMap = new HashMap<>();

        for (var item : evidence) {
            evidenceMap.computeIfAbsent(
                    item.versionId(),
                    ignored -> new ArrayList<>()
            ).add(item.evidence());
        }

        return rows.stream()
                .map(row -> new View(
                        row.questionId(),
                        row.id(),
                        row.companyId(),
                        row.revision(),
                        row.status(),
                        row.question(),
                        List.copyOf(
                                optionMap.getOrDefault(
                                        row.id(),
                                        List.of()
                                )
                        ),
                        correctMap.getOrDefault(
                                row.id(),
                                0L
                        ),
                        row.explanation(),
                        List.copyOf(
                                evidenceMap.getOrDefault(
                                        row.id(),
                                        List.of()
                                )
                        ),
                        row.note(),
                        row.updated()
                ))
                .toList();
    }

    private static String base() {
        return """
                select v.id,
                       v.question_id,
                       q.company_id,
                       q.version,
                       v.status,
                       v.question_text,
                       v.explanation,
                       v.review_note,
                       q.updated_at
                from quiz_question_versions v
                join quiz_questions q
                  on q.id = v.question_id
                """;
    }

    private static Row row(ResultSet rs) throws SQLException {
        return new Row(
                rs.getLong(1),
                rs.getLong(2),
                rs.getLong(3),
                rs.getLong(4),
                Status.valueOf(rs.getString(5)),
                rs.getString(6),
                rs.getString(7),
                rs.getString(8),
                rs.getObject(
                        9,
                        LocalDateTime.class
                ).toInstant(ZoneOffset.UTC)
        );
    }

    private static Version version(ResultSet rs) throws SQLException {
        return new Version(
                rs.getLong(1),
                rs.getLong(2),
                rs.getInt(3),
                Status.valueOf(rs.getString(4))
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

    private long insert(
            String sql,
            Object... args
    ) {
        var key = new GeneratedKeyHolder();

        jdbc.update(connection -> {
            var s = connection.prepareStatement(
                    sql,
                    Statement.RETURN_GENERATED_KEYS
            );

            for (int i = 0; i < args.length; i++) {
                s.setObject(i + 1, args[i]);
            }

            return s;
        }, key);

        return QuizBank.id(
                Objects.requireNonNull(key.getKey()).longValue()
        );
    }

    private static String marks(int count) {
        return String.join(
                ",",
                java.util.Collections.nCopies(count, "?")
        );
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(
                value,
                ZoneOffset.UTC
        );
    }
}