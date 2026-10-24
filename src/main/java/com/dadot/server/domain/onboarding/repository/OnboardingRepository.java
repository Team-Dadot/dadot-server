package com.dadot.server.domain.onboarding.repository;

import com.dadot.server.domain.onboarding.dto.response.OnboardingResponse.CurrentDevice;
import com.dadot.server.domain.onboarding.dto.response.OnboardingResponse.NotificationSettings;
import com.dadot.server.domain.onboarding.model.OnboardingDraft;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Repository
@RequiredArgsConstructor
public class OnboardingRepository {

    private final JdbcTemplate jdbc;

    public boolean actorExists(
            long userId, String sessionId, int authVersion, Instant now
    ) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(
                    select 1 from users u join auth_sessions s on s.user_id = u.id
                    where u.id = ? and s.id = ? and u.status = 'ACTIVE'
                      and u.auth_version = ? and s.revoked_at is null
                      and s.created_at <= ? and s.expires_at > ?
                )
                """, Boolean.class,
                userId, sessionId, authVersion, utc(now), utc(now)));
    }

    public State find(long userId) {
        return load(userId, false);
    }

    public State lock(long userId, Instant now) {
        jdbc.update("""
                insert into user_onboarding(user_id, updated_at) values (?, ?)
                on duplicate key update user_id = values(user_id)
                """, userId, utc(now));

        return load(userId, true);
    }

    private State load(long userId, boolean lock) {
        return jdbc.query("""
                select version, draft_payload, notification_guidance_completed_at, completed_at
                from user_onboarding where user_id = ?
                """ + (lock ? " for update" : ""),
                (rs, row) -> new State(
                        rs.getLong("version"),
                        rs.getString("draft_payload"),
                        rs.getObject(
                                "notification_guidance_completed_at",
                                LocalDateTime.class
                        ),
                        rs.getObject("completed_at", LocalDateTime.class)
                ),
                userId
        ).stream().findFirst().orElse(new State(1, null, null, null));
    }

    public void save(
            long userId,
            long version,
            OnboardingDraft draft,
            String json,
            Instant guidanceAt,
            Instant completedAt,
            Instant now
    ) {
        int changed = jdbc.update("""
                update user_onboarding
                set draft_payload = ?, current_step = ?, version = version + 1,
                    notification_guidance_completed_at = ?, completed_at = ?, updated_at = ?
                where user_id = ? and version = ? and completed_at is null
                """,
                json, draft.currentStep().name(), utc(guidanceAt), utc(completedAt),
                utc(now), userId, version);

        if (changed != 1) {
            throw new IllegalStateException("Onboarding update lost its lock");
        }
    }

    public List<Long> lockPublicCompanies(List<Long> ids) {
        return jdbc.query(
                "select id from companies where is_service_company = true and id in ("
                        + placeholders(ids.size())
                        + ") order by id lock in share mode",
                (rs, row) -> rs.getLong("id"),
                ids.toArray()
        );
    }

    public Map<Long, String> lockActiveJobs(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> result = new LinkedHashMap<>();

        jdbc.query(
                "select id, name from jobs where is_active = true and id in ("
                        + placeholders(ids.size())
                        + ") order by id lock in share mode",
                rs -> {
                    result.put(rs.getLong("id"), rs.getString("name"));
                },
                ids.toArray()
        );

        return result;
    }

    public List<Long> lockExperiences(long userId, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }

        var arguments = new java.util.ArrayList<Object>();
        arguments.add(userId);
        arguments.addAll(ids);

        return jdbc.query("""
                select m.id from personal_materials m
                join material_versions v on v.material_id = m.id
                  and v.version_no = m.current_version_no
                where m.user_id = ? and m.type = 'EXPERIENCE' and m.id in (
                """ + placeholders(ids.size()) + ") order by m.id lock in share mode",
                (rs, row) -> rs.getLong("id"),
                arguments.toArray()
        );
    }

    public long experienceCount(long userId) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                select count(*) from personal_materials m
                join material_versions v on v.material_id = m.id
                  and v.version_no = m.current_version_no
                where m.user_id = ? and m.type = 'EXPERIENCE'
                """, Long.class, userId));
    }

    public NotificationSettings notifications(long userId, String sessionId) {
        var device = jdbc.query("""
                select installation_id, platform, permission,
                    (permission = 'GRANTED' and push_token_ciphertext is not null
                     and push_token_digest is not null) as registered
                from user_devices where user_id = ? and session_id = ?
                order by updated_at desc, installation_id asc limit 1
                """,
                (rs, row) -> new CurrentDevice(
                        rs.getString("installation_id"),
                        rs.getString("platform"),
                        rs.getString("permission"),
                        rs.getBoolean("registered")
                ),
                userId, sessionId
        ).stream().findFirst().orElse(null);

        return jdbc.query("""
                select enabled, scheduled_time, time_zone, version
                from notification_settings where user_id = ?
                """,
                (rs, row) -> {
                    LocalTime time = rs.getObject("scheduled_time", LocalTime.class);

                    return new NotificationSettings(
                            rs.getBoolean("enabled"),
                            time == null
                                    ? null
                                    : time.format(DateTimeFormatter.ofPattern("HH:mm")),
                            rs.getString("time_zone"),
                            "NOT_CONFIGURED",
                            device,
                            rs.getLong("version")
                    );
                },
                userId
        ).stream().findFirst().orElseGet(() -> new NotificationSettings(
                false, null, "Asia/Seoul", "NOT_CONFIGURED", device, 1
        ));
    }

    public void activate(long userId, OnboardingDraft draft, Instant now) {
        Long existing = jdbc.queryForObject(
                "select count(*) from user_companies where user_id = ?",
                Long.class,
                userId
        );

        if (!Objects.equals(existing, 0L)) {
            throw new IllegalStateException(
                    "Unexpected companies before onboarding completion"
            );
        }

        for (int i = 0; i < draft.assignments().size(); i++) {
            var assignment = draft.assignments().get(i);
            var job = assignment.job();

            jdbc.update("""
                    insert into user_companies(
                        user_id, company_id, active_slot, job_id, custom_job_name,
                        interview_date, first_added_at, activated_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    userId,
                    assignment.companyId(),
                    i + 1,
                    job.jobId(),
                    job.isCustom() ? job.jobName() : null,
                    assignment.interviewDate(),
                    utc(now),
                    utc(now));
        }

        jdbc.update("""
                insert into user_preferences(user_id, selected_company_id, updated_at)
                values (?, ?, ?)
                on duplicate key update selected_company_id = values(selected_company_id),
                    updated_at = values(updated_at)
                """, userId, draft.companyIds().getFirst(), utc(now));

        jdbc.update("""
                insert into notification_settings(user_id, updated_at) values (?, ?)
                on duplicate key update user_id = values(user_id)
                """, userId, utc(now));
    }

    private static String placeholders(int size) {
        return String.join(",", Collections.nCopies(size, "?"));
    }

    public static LocalDateTime utc(Instant value) {
        return value == null
                ? null
                : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    public record State(
            long version,
            String json,
            LocalDateTime guidanceAt,
            LocalDateTime completedAt
    ) {
    }
}