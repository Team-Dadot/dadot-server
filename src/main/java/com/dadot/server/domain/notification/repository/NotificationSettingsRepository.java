package com.dadot.server.domain.notification.repository;

import com.dadot.server.domain.notification.model.NotificationSettings.Settings;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class NotificationSettingsRepository {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private final JdbcTemplate jdbc;

    public Optional<Settings> find(long user) {
        return jdbc.query("""
                select enabled, scheduled_time, time_zone, version
                from notification_settings where user_id = ?
                """, (rs, row) -> {
            LocalTime time = rs.getObject("scheduled_time", LocalTime.class);
            return new Settings(
                    rs.getBoolean("enabled"), time == null ? null : time.format(TIME),
                    rs.getString("time_zone"), rs.getLong("version")
            );
        }, user).stream().findFirst();
    }

    // 호출자가 users -> auth_sessions 순으로 잠근 뒤 실행한다.
    // 최초 생성과 기존 설정 수정을 같은 회원 잠금으로 직렬화한다.
    public void save(long user, Settings next, long expectedVersion, boolean exists, Instant now) {
        LocalTime time = next.scheduledTime() == null
                ? null : LocalTime.parse(next.scheduledTime(), TIME);
        LocalDateTime updatedAt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        int changed;
        if (exists) {
            changed = jdbc.update("""
                    update notification_settings
                    set enabled = ?, scheduled_time = ?, time_zone = ?, version = ?, updated_at = ?
                    where user_id = ? and version = ?
                    """, next.enabled(), time, next.timeZone(), next.version(), updatedAt,
                    user, expectedVersion);
        } else {
            changed = jdbc.update("""
                    insert into notification_settings
                        (user_id, enabled, scheduled_time, time_zone, version, updated_at)
                    values (?, ?, ?, ?, ?, ?)
                    """, user, next.enabled(), time, next.timeZone(), next.version(), updatedAt);
        }
        if (changed != 1) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
    }
}