package com.dadot.server.global.security;

import com.dadot.server.domain.auth.repository.LogoutRepository;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class AdminAccess {

    private final JdbcTemplate jdbc;
    private final LogoutRepository sessions;
    private final Clock clock;

    public void require(
            SessionAuthenticationToken actor,
            boolean lock
    ) {
        if (actor == null || !actor.isAuthenticated()) {
            fail(ErrorCode.UNAUTHENTICATED);
        }

        var rows = jdbc.query(
                "select role, status, auth_version from users where id = ?"
                        + (lock ? " for update" : ""),
                (rs, n) -> new Account(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getInt(3)
                ),
                actor.getPrincipal().userId()
        );

        if (rows.isEmpty()
                || !"ACTIVE".equals(rows.getFirst().status())
                || rows.getFirst().version() != actor.getAuthVersion()) {
            fail(ErrorCode.UNAUTHENTICATED);
        }

        if (!"ADMIN".equals(rows.getFirst().role())) {
            fail(ErrorCode.FORBIDDEN);
        }

        if (lock) {
            var session = sessions.lockSession(
                    actor.getPrincipal().userId(),
                    actor.getSessionId()
            ).orElseThrow(
                    () -> new BusinessException(ErrorCode.UNAUTHENTICATED)
            );

            if (!session.usable(clock.instant())) {
                fail(ErrorCode.UNAUTHENTICATED);
            }

        } else {
            var now = LocalDateTime.ofInstant(
                    clock.instant(),
                    ZoneOffset.UTC
            );

            if (Objects.requireNonNull(jdbc.queryForObject(
                    """
                    select count(*)
                    from auth_sessions
                    where id = ?
                      and user_id = ?
                      and revoked_at is null
                      and created_at <= ?
                      and expires_at > ?
                    """,
                    Long.class,
                    actor.getSessionId(),
                    actor.getPrincipal().userId(),
                    now,
                    now
            )) != 1) {
                fail(ErrorCode.UNAUTHENTICATED);
            }
        }
    }

    private record Account(
            String role,
            String status,
            int version
    ) {}

    private static void fail(ErrorCode code) {
        throw new BusinessException(code);
    }
}