package com.dadot.server.domain.quiz.repository;

import com.dadot.server.domain.quiz.model.QuizBank;
import com.dadot.server.domain.quiz.model.QuizPlay.*;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static com.dadot.server.domain.quiz.model.QuizPlay.fail;

@Repository
@RequiredArgsConstructor
public class QuizPlayRepository {

    private final JdbcTemplate jdbc;

    // 게시 포인터와 고정 버전 모두 유효해야 한다. 정정 접수만 된 원문도 신규 사용하지 않는다.
    private static final String AVAILABLE = """
            v.status = 'PUBLISHED' and q.published_version_no = v.version_no
            and exists (select 1 from quiz_evidence e where e.quiz_version_id = v.id)
            and not exists (
                select 1 from quiz_evidence e
                join disclosure_sections ds on ds.id = e.section_id
                join disclosure_versions dv on dv.id = ds.disclosure_version_id
                where e.quiz_version_id = v.id and (
                    dv.status <> 'PUBLISHED' or exists (
                        select 1 from disclosures c where c.corrects_disclosure_id = dv.disclosure_id
                    )
                )
            )
            """;

    public record SetRow(long id, long companyId, LocalDate date, String mode,
                         String status, Accuracy before, Accuracy after) {}

    public record Candidate(long questionId, long versionId, boolean incorrect) {}

    public record Item(long id, long questionId, long versionId, int position,
                       String text, String explanation, boolean review, boolean available,
                       Long answerId, Long selected, Boolean correct, Instant submittedAt) {}

    public record Choices(List<Option> options, long correctId) {}

    public record CompanyRow(long id, String name, String industry, String logoKey) {}

    public LocalDate requirePreparation(long user, long company, boolean lock) {
        return jdbc.query("""
                select interview_date from user_companies
                where user_id = ? and company_id = ? and active_slot is not null
                  and excluded_at is null
                """ + (lock ? " for update" : ""),
                        (rs, n) -> Optional.ofNullable(rs.getObject(1, LocalDate.class)), user, company)
                .stream().findFirst().orElseThrow(QuizBank::missing).orElse(null);
    }

    public void lockCompany(long company) {
        if (jdbc.query("select id from companies where id = ? and is_service_company = true lock in share mode",
                (rs, n) -> rs.getLong(1), company).isEmpty()) {
            throw QuizBank.missing();
        }
    }

    public Optional<SetRow> today(long user, long company, LocalDate date) {
        return sets("where user_id = ? and company_id = ? and business_date = ?", user, company, date)
                .stream().findFirst();
    }

    public SetRow owned(long user, long setId) {
        return sets("where user_id = ? and id = ?", user, setId)
                .stream().findFirst().orElseThrow(QuizBank::missing);
    }

    private List<SetRow> sets(String filter, Object... args) {
        return jdbc.query("""
                select id, company_id, business_date, mode, status,
                    accuracy_before_total, accuracy_before_correct,
                    accuracy_after_total, accuracy_after_correct
                from quiz_sets
                """ + filter, (rs, n) -> new SetRow(
                rs.getLong(1), rs.getLong(2), rs.getObject(3, LocalDate.class),
                rs.getString(4), rs.getString(5), Accuracy.of(rs.getInt(6), rs.getInt(7)),
                rs.getObject(8) == null ? null : Accuracy.of(rs.getInt(8), rs.getInt(9))), args);
    }

    public Accuracy accuracy(long user, long company) {
        return jdbc.queryForObject("""
                select count(*), coalesce(sum(a.is_correct), 0)
                from quiz_answers a
                where a.user_id = ? and a.company_id = ? and not exists (
                    select 1 from quiz_answers earlier
                    where earlier.user_id = a.user_id and earlier.company_id = a.company_id
                      and earlier.question_id = a.question_id
                      and (earlier.submitted_at < a.submitted_at
                           or (earlier.submitted_at = a.submitted_at and earlier.id < a.id))
                )
                """, (rs, n) -> Accuracy.of(rs.getInt(1), rs.getInt(2)), user, company);
    }

    public List<Candidate> select(long user, long company, boolean focus) {
        List<Candidate> result = new ArrayList<>();
        if (focus) {
            result.addAll(candidates(user, company, true, List.of(), 4));
        }
        result.addAll(candidates(user, company, false,
                result.stream().map(Candidate::questionId).toList(), 5 - result.size()));
        return List.copyOf(result);
    }

    private List<Candidate> candidates(long user, long company, boolean wrong,
                                       List<Long> excluded, int limit) {
        List<Object> args = new ArrayList<>(List.of(user, company));
        args.addAll(excluded);
        args.add(limit);
        String exclusion = excluded.isEmpty() ? "" : " and q.id not in (" + marks(excluded.size()) + ")";
        return jdbc.query("""
                select q.id, v.id
                from quiz_questions q
                join quiz_question_versions v on v.question_id = q.id
                    and v.version_no = q.published_version_no
                left join quiz_answers last_answer on last_answer.id = (
                    select h.id from quiz_answers h
                    where h.user_id = ? and h.company_id = q.company_id and h.question_id = q.id
                    order by h.submitted_at desc, h.id desc limit 1
                )
                where q.company_id = ? and
                """ + AVAILABLE + exclusion
                        + (wrong ? " and last_answer.is_correct = false" : "")
                        + " order by (last_answer.id is not null), last_answer.submitted_at, last_answer.id, q.id limit ?",
                (rs, n) -> new Candidate(rs.getLong(1), rs.getLong(2), wrong), args.toArray());
    }

    public SetRow create(long user, long company, LocalDate date, String mode,
                         Accuracy before, List<Candidate> items, Instant now) {
        jdbc.update("""
                update quiz_sets set status = 'EXPIRED'
                where user_id = ? and company_id = ? and business_date < ? and status = 'IN_PROGRESS'
                """, user, company, date);
        long id = insert("""
                insert into quiz_sets(user_id, company_id, business_date, mode,
                    accuracy_before_total, accuracy_before_correct, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, user, company, date, mode, before.firstAnsweredCount(), before.firstCorrectCount(), utc(now));
        List<Integer> positions = List.of(1, 2, 3, 4, 5);
        jdbc.batchUpdate("""
                insert into quiz_set_items(set_id, user_id, company_id, question_id,
                    quiz_version_id, position, selected_from_incorrect)
                values (?, ?, ?, ?, ?, ?, ?)
                """, positions, 5, (s, position) -> {
            var item = items.get(position - 1);
            s.setLong(1, id);
            s.setLong(2, user);
            s.setLong(3, company);
            s.setLong(4, item.questionId());
            s.setLong(5, item.versionId());
            s.setInt(6, position);
            s.setBoolean(7, item.incorrect());
        });
        return owned(user, id);
    }

    public List<Item> items(long setId) {
        return jdbc.query("""
                select i.id, i.question_id, i.quiz_version_id, i.position,
                    v.question_text, v.explanation,
                    exists(select 1 from quiz_answers h
                        where h.user_id = i.user_id and h.company_id = i.company_id
                          and h.question_id = i.question_id and h.set_item_id <> i.id
                          and h.submitted_at <= st.created_at) as is_review,
                """ + "(" + AVAILABLE + ") as available, " + """
                    a.id, a.selected_option_id, a.is_correct, a.submitted_at
                from quiz_set_items i
                join quiz_sets st on st.id = i.set_id
                join quiz_question_versions v on v.id = i.quiz_version_id
                join quiz_questions q on q.id = i.question_id
                left join quiz_answers a on a.set_item_id = i.id
                where i.set_id = ? order by i.position
                """, (rs, n) -> new Item(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getInt(4), rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getBoolean(8),
                rs.getObject(9, Long.class), rs.getObject(10, Long.class), rs.getObject(11, Boolean.class),
                rs.getObject(12) == null ? null : rs.getObject(12, LocalDateTime.class).toInstant(ZoneOffset.UTC)), setId);
    }

    public Map<Long, Choices> choices(List<Item> items) {
        if (items.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Option>> options = new HashMap<>();
        Map<Long, List<Long>> correct = new HashMap<>();
        jdbc.query("""
                select quiz_version_id, id, position, option_text, is_correct
                from quiz_options where quiz_version_id in (
                """ + marks(items.size()) + ") order by quiz_version_id, position", rs -> {
            long version = rs.getLong(1);
            options.computeIfAbsent(version, ignored -> new ArrayList<>())
                    .add(new Option(rs.getLong(2), rs.getInt(3), rs.getString(4)));
            if (rs.getBoolean(5)) {
                correct.computeIfAbsent(version, ignored -> new ArrayList<>()).add(rs.getLong(2));
            }
        }, items.stream().map(Item::versionId).toArray());
        Map<Long, Choices> result = new HashMap<>();
        for (var item : items) {
            var list = options.getOrDefault(item.versionId(), List.of());
            var answers = correct.getOrDefault(item.versionId(), List.of());
            if (list.size() != 4 || answers.size() != 1) {
                throw QuizBank.unavailable();
            }
            result.put(item.versionId(), new Choices(List.copyOf(list), answers.getFirst()));
        }
        return result;
    }

    public long save(long user, SetRow set, Item item, long selected,
                     boolean correct, String keyHash, String bodyHmac, Instant now) {
        return insert("""
                insert into quiz_answers(set_item_id, user_id, company_id, question_id,
                    quiz_version_id, selected_option_id, is_correct,
                    request_key_hash, request_body_hmac, submitted_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, item.id(), user, set.companyId(), item.questionId(), item.versionId(),
                selected, correct, keyHash, bodyHmac, utc(now));
    }

    public void complete(long setId, Accuracy after, Instant now) {
        if (jdbc.update("""
                update quiz_sets set status = 'COMPLETED', accuracy_after_total = ?,
                    accuracy_after_correct = ?, completed_at = ?
                where id = ? and status = 'IN_PROGRESS'
                """, after.firstAnsweredCount(), after.firstCorrectCount(), utc(now), setId) != 1) {
            throw new IllegalStateException("Quiz completion lost its lock");
        }
    }

    public CompanyRow company(long company) {
        return jdbc.query("select id, name, industry, logo_key from companies where id = ?",
                (rs, n) -> new CompanyRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                company).stream().findFirst().orElseThrow(QuizBank::missing);
    }

    public Map<Long, List<Evidence>> evidence(List<Item> items) {
        Map<Long, List<Evidence>> result = new HashMap<>();
        jdbc.query("""
                select e.quiz_version_id, d.id, dv.id, d.report_name, d.source_url,
                    ds.heading, e.quotation,
                    (dv.status = 'SUPERSEDED' or exists (
                        select 1 from disclosures c where c.corrects_disclosure_id = d.id
                    )) as superseded
                from quiz_evidence e
                join disclosure_sections ds on ds.id = e.section_id
                join disclosure_versions dv on dv.id = ds.disclosure_version_id
                join disclosures d on d.id = dv.disclosure_id
                where e.quiz_version_id in (
                """ + marks(items.size()) + ") order by e.quiz_version_id, e.section_id", rs -> {
            result.computeIfAbsent(rs.getLong(1), ignored -> new ArrayList<>()).add(new Evidence(
                    rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                    rs.getString(6), rs.getString(7), rs.getBoolean(8)));
        }, items.stream().map(Item::versionId).toArray());
        result.replaceAll((id, evidence) -> List.copyOf(evidence));
        return result;
    }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, key);
        Number value = key.getKey();
        if (value == null) {
            throw new IllegalStateException("Missing quiz generated key");
        }
        long id = value.longValue();
        if (id < 1 || id > QuizBank.MAX_ID) {
            throw fail(ErrorCode.SERVICE_UNAVAILABLE);
        }
        return id;
    }

    public static LocalDateTime utc(Instant now) {
        return LocalDateTime.ofInstant(now, ZoneOffset.UTC);
    }

    private static String marks(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }
}