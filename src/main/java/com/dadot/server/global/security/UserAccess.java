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

@Component
@RequiredArgsConstructor
public class UserAccess {

    private final LogoutRepository sessions;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public long require(SessionAuthenticationToken actor, boolean lock) {
        if (actor == null || !actor.isAuthenticated()) {
            throw denied();
        }
        long id = actor.getPrincipal().userId();
        if (lock) {
            var account = sessions.lockAccount(id).orElseThrow(UserAccess::denied);
            if (!account.active() || account.authVersion() != actor.getAuthVersion()) {
                throw denied();
            }
            var session = sessions.lockSession(id, actor.getSessionId())
                    .orElseThrow(UserAccess::denied);
            if (!session.usable(clock.instant())) {
                throw denied();
            }
        } else {
            var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
            if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(
                        select 1 from users u join auth_sessions s on s.user_id = u.id
                        where u.id = ? and s.id = ? and u.status = 'ACTIVE'
                          and u.auth_version = ? and s.revoked_at is null
                          and s.created_at <= ? and s.expires_at > ?
                    )
                    """, Boolean.class, id, actor.getSessionId(), actor.getAuthVersion(), now, now))) {
                throw denied();
            }
        }
        return id;
    }

    private static BusinessException denied() {
        return new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
}