package com.dadot.server.domain.quiz.repository;

import com.dadot.server.domain.quiz.model.QuizBank;
import com.dadot.server.domain.quiz.model.QuizBank.Status;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class QuizDisclosureImpactRepository {

    private final JdbcTemplate jdbc;

    // 호출자는 동일 기업 행을 잠근 상태이며
    // 공시 변경과 같은 트랜잭션에서 실행한다.
    public long suspendUnavailable(
            Set<Long> disclosureIds,
            long actor,
            String reason,
            Instant now
    ) {
        if (disclosureIds.isEmpty()) {
            return 0;
        }

        var affected = jdbc.query(
                """
                select distinct q.id, v.id, v.version_no, v.status
                from quiz_evidence e
                join quiz_question_versions v
                  on v.id = e.quiz_version_id
                join quiz_questions q
                  on q.id = v.question_id
                join disclosure_sections s
                  on s.id = e.section_id
                join disclosure_versions source
                  on source.id = s.disclosure_version_id
                where source.disclosure_id in (
                """ + marks(disclosureIds.size()) + """
                )
                  and (
                      source.status <> 'PUBLISHED'
                      or exists (
                          select 1
                          from disclosures correction
                          where correction.corrects_disclosure_id
                              = source.disclosure_id
                      )
                  )
                order by q.id, v.id
                """,
                (rs, n) -> new QuizBankRepository.Version(
                        rs.getLong(2),
                        rs.getLong(1),
                        rs.getInt(3),
                        Status.valueOf(rs.getString(4))
                ),
                disclosureIds.toArray()
        );

        long total = affected.stream()
                .map(QuizBankRepository.Version::questionId)
                .distinct()
                .count();

        var changed = affected.stream()
                .filter(v ->
                        v.status() == Status.PUBLISHED
                                || v.status() == Status.IN_REVIEW
                                || v.status() == Status.APPROVED
                )
                .toList();

        if (changed.isEmpty()) {
            return total;
        }

        Object[] versions = changed.stream()
                .map(QuizBankRepository.Version::id)
                .toArray();

        Object[] questions = changed.stream()
                .map(QuizBankRepository.Version::questionId)
                .distinct()
                .sorted()
                .toArray();

        String versionIds = marks(versions.length);
        String questionIds = marks(questions.length);

        jdbc.query(
                "select id from quiz_questions"
                        + " where id in (" + questionIds + ")"
                        + " order by id for update",
                (rs, n) -> rs.getLong(1),
                questions
        );

        jdbc.query(
                "select id from quiz_question_versions"
                        + " where id in (" + versionIds + ")"
                        + " order by question_id, id for update",
                (rs, n) -> rs.getLong(1),
                versions
        );

        jdbc.update(
                """
                update quiz_questions q
                join quiz_question_versions v
                  on v.question_id = q.id
                 and v.version_no = q.published_version_no
                set q.published_version_no = null
                where v.id in (
                """ + versionIds + ")",
                versions
        );

        var parameters = new ArrayList<Object>(
                List.of(
                        reason,
                        actor,
                        utc(now)
                )
        );

        parameters.addAll(List.of(versions));

        if (jdbc.update(
                """
                update quiz_question_versions
                set status = 'SUSPENDED',
                    review_note = ?,
                    reviewed_by = ?,
                    reviewed_at = ?
                where id in (
                """ + versionIds
                        + ") and status in ('PUBLISHED', 'IN_REVIEW', 'APPROVED')",
                parameters.toArray()
        ) != versions.length) {
            throw new BusinessException(
                    ErrorCode.VERSION_CONFLICT
            );
        }

        jdbc.batchUpdate(
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
                changed,
                100,
                (s, version) -> {
                    s.setLong(1, actor);
                    s.setString(
                            2,
                            "QUIZ_" + version.status() + "_TO_SUSPENDED"
                    );
                    s.setString(
                            3,
                            Long.toString(version.id())
                    );
                    s.setString(4, reason);
                    s.setObject(5, utc(now));
                }
        );

        parameters = new ArrayList<>(
                List.of(
                        utc(now),
                        QuizBank.MAX_ID
                )
        );

        parameters.addAll(List.of(questions));

        if (jdbc.update(
                """
                update quiz_questions
                set version = version + 1,
                    updated_at = ?
                where version < ?
                  and id in (
                """ + questionIds + ")",
                parameters.toArray()
        ) != questions.length) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }

        return total;
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