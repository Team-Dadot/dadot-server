package com.dadot.server.domain.quiz.repository;

import com.dadot.server.domain.quiz.model.QuizBank;
import com.dadot.server.domain.quiz.model.QuizReview.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class QuizReviewRepository {

    private final JdbcTemplate jdbc;

    private static final String REVIEW_COMPANIES = """
            from user_companies uc
            join companies c on c.id = uc.company_id
            where uc.user_id = ? and (
                uc.active_slot is not null or exists (
                    select 1 from quiz_answers a
                    where a.user_id = uc.user_id and a.company_id = uc.company_id
                )
            )
            """;

    private static final String LATEST = """
            where a.user_id = ? and a.company_id = ?
              and not exists (
                  select 1 from quiz_answers newer
                  where newer.user_id = a.user_id and newer.company_id = a.company_id
                    and newer.question_id = a.question_id
                    and (newer.submitted_at > a.submitted_at
                         or (newer.submitted_at = a.submitted_at and newer.id > a.id))
              )
            """;

    public record CompanyRow(long id, String name, String industry, String logoKey, boolean active) {}
    public record Companies(List<CompanyRow> rows, Map<Long, Long> quizCounts, long total) {}
    public record Counts(long all, long incorrect) {}
    public record SubmittedItem(long setId, long itemId, long answerId) {}

    public Companies companies(long user, Paging paging) {
        long total = Objects.requireNonNull(jdbc.queryForObject(
                "select count(*) " + REVIEW_COMPANIES, Long.class, user));
        if (paging.offset() >= total) {
            return new Companies(List.of(), Map.of(), total);
        }
        var rows = jdbc.query("""
                select c.id, c.name, c.industry, c.logo_key, uc.active_slot is not null
                """ + REVIEW_COMPANIES + """
                order by (uc.active_slot is not null) desc, c.name asc, c.id asc
                limit ? offset ?
                """, (rs, n) -> new CompanyRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getBoolean(5)), user, paging.size(), paging.offset());

        List<Object> args = new ArrayList<>();
        args.add(user);
        rows.forEach(row -> args.add(row.id()));
        Map<Long, Long> counts = new HashMap<>();
        jdbc.query("""
                select company_id, count(distinct question_id)
                from quiz_answers where user_id = ? and company_id in (
                """ + marks(rows.size()) + ") group by company_id", rs -> {
            counts.put(rs.getLong(1), rs.getLong(2));
        }, args.toArray());
        return new Companies(List.copyOf(rows), Map.copyOf(counts), total);
    }

    public void requireCompany(long user, long company) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(
                    select 1 from user_companies uc
                    where uc.user_id = ? and uc.company_id = ?
                      and (uc.active_slot is not null or exists (
                          select 1 from quiz_answers a
                          where a.user_id = uc.user_id and a.company_id = uc.company_id
                      ))
                )
                """, Boolean.class, user, company))) {
            throw QuizBank.missing();
        }
    }

    public Counts counts(long user, long company) {
        return jdbc.queryForObject("""
                select count(*), coalesce(sum(a.is_correct = false), 0)
                from quiz_answers a
                """ + LATEST, (rs, n) -> new Counts(rs.getLong(1), rs.getLong(2)), user, company);
    }

    public List<QuestionItem> questions(long user, Search search, long total) {
        if (search.paging().offset() >= total) {
            return List.of();
        }
        return jdbc.query("""
                select a.question_id, a.quiz_version_id, v.question_text,
                    a.id, a.is_correct, a.submitted_at
                from quiz_answers a
                join quiz_question_versions v on v.id = a.quiz_version_id
                """ + LATEST
                        + (search.filter() == Filter.INCORRECT ? " and a.is_correct = false" : "")
                        + " order by a.submitted_at desc, a.question_id desc limit ? offset ?",
                (rs, n) -> new QuestionItem(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getLong(4), rs.getBoolean(5),
                        rs.getObject(6, LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                user, search.companyId(), search.paging().size(), search.paging().offset());
    }

    public SubmittedItem latest(long user, long question) {
        return jdbc.query("""
                select i.set_id, a.set_item_id, a.id
                from quiz_questions q
                join quiz_answers a on a.question_id = q.id and a.company_id = q.company_id
                join quiz_set_items i on i.id = a.set_item_id
                where q.id = ? and a.user_id = ?
                order by a.submitted_at desc, a.id desc limit 1
                """, (rs, n) -> new SubmittedItem(rs.getLong(1), rs.getLong(2), rs.getLong(3)), question, user)
                .stream().findFirst().orElseThrow(QuizBank::missing);
    }

    private static String marks(int size) {
        return String.join(",", Collections.nCopies(size, "?"));
    }
}