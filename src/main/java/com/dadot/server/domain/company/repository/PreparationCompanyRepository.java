package com.dadot.server.domain.company.repository;

import com.dadot.server.domain.company.model.PreparationCompany;
import com.dadot.server.domain.company.model.PreparationCompany.JobInput;
import com.dadot.server.domain.company.model.PreparationCompany.QuizToday;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PreparationCompanyRepository {

    private final JdbcTemplate jdbc;

    public record State(
            long companyId,
            Integer slot,
            Long jobId,
            String customName,
            LocalDate interviewDate,
            long version
    ) {}

    public record Row(
            long companyId,
            String name,
            String industry,
            String logoKey,
            Long jobId,
            String jobName,
            LocalDate interviewDate,
            boolean selected,
            long version
    ) {}

    public void requireCompleted(long user) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from user_onboarding
                    where user_id = ? and completed_at is not null)
                """, Boolean.class, user))) {
            throw new BusinessException(ErrorCode.ONBOARDING_INCOMPLETE);
        }
    }

    public List<Row> rows(long user) {
        return jdbc.query("""
                select uc.company_id, c.name, c.industry, c.logo_key, uc.job_id,
                    coalesce(uc.custom_job_name, j.name), uc.interview_date,
                    coalesce(p.selected_company_id = uc.company_id, false), uc.version
                from user_companies uc
                join companies c on c.id = uc.company_id
                left join jobs j on j.id = uc.job_id
                left join user_preferences p on p.user_id = uc.user_id
                where uc.user_id = ? and uc.active_slot is not null
                order by uc.active_slot
                """,
                (rs, n) -> new Row(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getObject(5, Long.class),
                        rs.getString(6),
                        rs.getObject(7, LocalDate.class),
                        rs.getBoolean(8),
                        rs.getLong(9)),
                user);
    }

    // 최대 5개 기업의 당일 세트·답안 수를 한 번에 조회한다. 과거 이력은 집계하지 않는다.
    public Map<Long, QuizToday> quizzes(
            long user,
            LocalDate date,
            List<Long> companies
    ) {
        if (companies.isEmpty()) return Map.of();

        var args = new java.util.ArrayList<Object>(List.of(user, date));
        args.addAll(companies);

        Map<Long, QuizToday> result = new HashMap<>();

        jdbc.query("""
                select s.company_id, s.id, count(a.id), s.status = 'COMPLETED'
                from quiz_sets s
                left join quiz_set_items i on i.set_id = s.id
                left join quiz_answers a on a.set_item_id = i.id
                where s.user_id = ? and s.business_date = ? and s.company_id in (
                """
                        + String.join(",",
                        java.util.Collections.nCopies(companies.size(), "?"))
                        + ") group by s.company_id, s.id, s.status",
                rs -> {
                    result.put(
                            rs.getLong(1),
                            new QuizToday(
                                    rs.getLong(2),
                                    rs.getInt(3),
                                    5,
                                    rs.getBoolean(4)));
                },
                args.toArray());

        return Map.copyOf(result);
    }

    // 호출자가 users → auth_sessions를 먼저 잠근다. 같은 사용자의 모든 변경을 직렬화한다.
    public List<State> lockActive(long user) {
        return states("""
                where user_id = ? and active_slot is not null
                order by active_slot for update
                """, user);
    }

    public Optional<State> lockExisting(long user, long company) {
        return states(
                "where user_id = ? and company_id = ? for update",
                user, company).stream().findFirst();
    }

    private List<State> states(String filter, Object... args) {
        return jdbc.query("""
                select company_id, active_slot, job_id, custom_job_name,
                    interview_date, version
                from user_companies
                """ + filter,
                (rs, n) -> new State(
                        rs.getLong(1),
                        rs.getObject(2, Integer.class),
                        rs.getObject(3, Long.class),
                        rs.getString(4),
                        rs.getObject(5, LocalDate.class),
                        rs.getLong(6)),
                args);
    }

    public long lockSelected(long user) {
        return jdbc.query("""
                select selected_company_id from user_preferences
                where user_id = ? for update
                """,
                        (rs, n) -> rs.getObject(1, Long.class),
                        user).stream()
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing selected preparation company"));
    }

    public void lockPublicCompany(long company) {
        if (jdbc.query("""
                select id from companies
                where id = ? and is_service_company = true
                lock in share mode
                """,
                (rs, n) -> rs.getLong(1),
                company).isEmpty()) {
            throw PreparationCompany.missing();
        }
    }

    public void validateJob(JobInput job) {
        if (job.jobId() != null && jdbc.query("""
                select id from jobs
                where id = ? and is_active = true
                lock in share mode
                """,
                (rs, n) -> rs.getLong(1),
                job.jobId()).isEmpty()) {
            throw PreparationCompany.missing();
        }
    }

    public void add(
            long user,
            long company,
            int slot,
            JobInput job,
            LocalDate date,
            State previous,
            Instant now
    ) {
        if (previous == null) {
            changed(jdbc.update("""
                    insert into user_companies(
                        user_id, company_id, active_slot, job_id,
                        custom_job_name, interview_date,
                        first_added_at, activated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    user, company, slot, job.jobId(), job.customName(),
                    date, utc(now), utc(now)));
        } else {
            mutable(previous.version());

            changed(jdbc.update("""
                    update user_companies
                    set active_slot = ?, job_id = ?, custom_job_name = ?,
                        interview_date = ?, activated_at = ?,
                        excluded_at = null, version = version + 1
                    where user_id = ? and company_id = ?
                      and active_slot is null and version = ?
                    """,
                    slot, job.jobId(), job.customName(), date, utc(now),
                    user, company, previous.version()));
        }
    }

    public void update(
            long user,
            State state,
            JobInput job,
            LocalDate date
    ) {
        mutable(state.version());

        changed(jdbc.update("""
                update user_companies
                set job_id = ?, custom_job_name = ?,
                    interview_date = ?, version = version + 1
                where user_id = ? and company_id = ?
                  and active_slot is not null and version = ?
                """,
                job.jobId(), job.customName(), date,
                user, state.companyId(), state.version()));
    }

    public void exclude(long user, State state, Instant now) {
        mutable(state.version());

        changed(jdbc.update("""
                update user_companies
                set active_slot = null, excluded_at = ?, version = version + 1
                where user_id = ? and company_id = ?
                  and active_slot = ? and version = ?
                """,
                utc(now), user, state.companyId(),
                state.slot(), state.version()));
    }

    // 앞 슬롯부터 이동해야 UNIQUE(user_id, active_slot)를 침범하지 않는다. 최대 4행이다.
    public void moveForward(long user, State state) {
        mutable(state.version());

        changed(jdbc.update("""
                update user_companies
                set active_slot = active_slot - 1, version = version + 1
                where user_id = ? and company_id = ?
                  and active_slot = ? and version = ?
                """,
                user, state.companyId(), state.slot(), state.version()));
    }

    public void select(long user, long company, Instant now) {
        changed(jdbc.update("""
                update user_preferences
                set selected_company_id = ?, updated_at = ?
                where user_id = ?
                """,
                company, utc(now), user));
    }

    public static void mutable(long version) {
        if (version == Long.MAX_VALUE) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static void changed(int count) {
        if (count != 1) {
            throw new IllegalStateException(
                    "Preparation company update lost its lock");
        }
    }

    private static LocalDateTime utc(Instant now) {
        return LocalDateTime.ofInstant(now, ZoneOffset.UTC);
    }
}