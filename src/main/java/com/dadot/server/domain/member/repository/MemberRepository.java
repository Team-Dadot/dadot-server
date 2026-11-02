package com.dadot.server.domain.member.repository;

import com.dadot.server.domain.member.model.Member.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class MemberRepository {
    private final JdbcTemplate jdbc;

    public Identity identity(long user, boolean lock) {
        List<Identity> identities = jdbc.query(
                """
                select provider, provider_subject, email, password_hash
                from auth_identities where user_id = ? order by id
                """ + (lock ? " for update" : ""),
                (rs, row) -> new Identity(
                        Provider.valueOf(rs.getString(1)),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4)
                ),
                user
        );

        // 현재 가입 모델에는 계정 연결 기능이 없다.
        // 여러 로그인 수단을 임의 선택하지 않는다.
        if (identities.size() != 1) {
            throw new IllegalStateException("Expected one member identity");
        }

        return identities.getFirst();
    }

    public Summary summary(long user, String session) {
        Identity identity = identity(user, false);

        return jdbc.queryForObject(
                """
                select u.nickname, o.completed_at is not null,
                    (select count(*) from personal_materials m
                        where m.user_id = u.id
                        and m.type = 'EXPERIENCE'
                        and m.current_version_no is not null),
                    (select count(*) from personal_materials m
                        where m.user_id = u.id
                        and m.type = 'COVER_LETTER'
                        and m.current_version_no is not null)
                from users u
                left join user_onboarding o on o.user_id = u.id
                where u.id = ?
                """,
                (rs, row) -> new Summary(
                        user,
                        rs.getString(1),
                        identity.provider().name(),
                        identity.email(),
                        rs.getBoolean(2),
                        rs.getInt(3),
                        rs.getInt(4),
                        notifications(user, session)
                ),
                user
        );
    }

    public Profile nickname(long user, String nickname, Instant now) {
        int changed = jdbc.update(
                """
                update users set nickname = ?, updated_at = ?
                where id = ? and status = 'ACTIVE'
                """,
                nickname,
                utc(now),
                user
        );

        if (changed != 1) {
            throw new IllegalStateException("Member update failed");
        }

        return new Profile(user, nickname);
    }

    private Notifications notifications(long user, String session) {
        Device device = jdbc.query(
                """
                select installation_id, platform, permission,
                    push_token_ciphertext is not null
                from user_devices where user_id = ? and session_id = ?
                order by updated_at desc, installation_id desc limit 1
                """,
                (rs, row) -> new Device(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getBoolean(4)
                ),
                user,
                session
        ).stream().findFirst().orElse(null);

        return jdbc.query(
                """
                select enabled, scheduled_time, time_zone, version
                from notification_settings where user_id = ?
                """,
                (rs, row) -> {
                    LocalTime time = rs.getObject(2, LocalTime.class);

                    return new Notifications(
                            rs.getBoolean(1),
                            time == null
                                    ? null
                                    : time.format(
                                    DateTimeFormatter.ofPattern("HH:mm")
                            ),
                            rs.getString(3),
                            "NOT_CONFIGURED",
                            device,
                            rs.getLong(4)
                    );
                },
                user
        ).stream().findFirst().orElseGet(() ->
                new Notifications(
                        false,
                        null,
                        "Asia/Seoul",
                        "NOT_CONFIGURED",
                        device,
                        1
                )
        );
    }

    public static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}