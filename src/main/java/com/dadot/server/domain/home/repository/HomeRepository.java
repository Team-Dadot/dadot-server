package com.dadot.server.domain.home.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Repository
@RequiredArgsConstructor
public class HomeRepository {

    private final JdbcTemplate jdbc;

    public record ProfileRow(
            String nickname,
            String imageKey,
            Long selectedCompanyId,
            boolean completed
    ) {}

    public ProfileRow profile(long user) {
        return jdbc.queryForObject("""
                select u.nickname, u.profile_image_key, p.selected_company_id,
                    o.completed_at is not null
                from users u
                left join user_preferences p on p.user_id = u.id
                left join user_onboarding o on o.user_id = u.id
                where u.id = ?
                """, (rs, n) -> new ProfileRow(
                rs.getString(1), rs.getString(2),
                rs.getObject(3, Long.class), rs.getBoolean(4)), user);
    }

    // 호출자가 users → auth_sessions → 활성 user_companies를 먼저 잠근다.
    public void select(long user, long company, Instant now) {
        int changed = jdbc.update("""
                update user_preferences
                set selected_company_id = ?, updated_at = ?
                where user_id = ?
                """, company, LocalDateTime.ofInstant(now, ZoneOffset.UTC), user);
        if (changed != 1) {
            throw new IllegalStateException("Missing user preferences");
        }
    }
}