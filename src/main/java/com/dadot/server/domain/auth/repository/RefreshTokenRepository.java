package com.dadot.server.domain.auth.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class RefreshTokenRepository {
    private final JdbcTemplate jdbc;

    public record Token(String hash, String family, long user, String session,
                        Instant issued, Instant expires, Instant consumed,
                        String keyHash, String encrypted, String keyVersion, Instant replayUntil) {
        @Override public String toString() { return "RefreshTokenRecord[REDACTED]"; }
    }
    public record Account(boolean active, int version) {}
    public record Family(int version, Instant expires, boolean revoked) {}

    public Optional<Token> find(String hash) {
        return jdbc.query("select * from auth_refresh_tokens where token_hash = ?", (r, n) ->
                new Token(r.getString("token_hash"), r.getString("family_id"), r.getLong("user_id"),
                        r.getString("session_id"), instant(r, "issued_at"), instant(r, "expires_at"),
                        instant(r, "consumed_at"), r.getString("request_key_hash"), r.getString("replay_response"),
                        r.getString("replay_key_version"), instant(r, "replay_until")), hash).stream().findFirst();
    }

    public Optional<Account> lockAccount(long user) {
        return jdbc.query("select status, auth_version from users where id = ? for update",
                (r, n) -> new Account("ACTIVE".equals(r.getString(1)), r.getInt(2)), user).stream().findFirst();
    }

    public Optional<Family> lockFamily(long user, String family) {
        return jdbc.query("""
                select auth_version, expires_at, revoked_at from auth_refresh_families
                where id = ? and user_id = ? for update
                """, (r, n) -> new Family(r.getInt(1), instant(r, "expires_at"),
                r.getObject("revoked_at") != null), family, user).stream().findFirst();
    }

    public boolean sessionValid(long user, String session) {
        // An expired access token is expected during refresh; revocation still blocks refresh.
        return jdbc.queryForObject("""
                select count(*) from auth_sessions where id = ? and user_id = ? and revoked_at is null
                """, Long.class, session, user) == 1;
    }

    public void createFamily(String family, long user, int version, Instant now, Instant expires) {
        jdbc.update("""
                insert into auth_refresh_families(id,user_id,auth_version,created_at,expires_at)
                values (?,?,?,?,?)
                """, family, user, version, utc(now), utc(expires));
    }

    public void createToken(String hash, String family, long user, String session, Instant now, Instant expires) {
        jdbc.update("""
                insert into auth_refresh_tokens(token_hash,family_id,user_id,session_id,issued_at,expires_at)
                values (?,?,?,?,?,?)
                """, hash, family, user, session, utc(now), utc(expires));
    }

    public void createAccessSession(String session, long user, Instant now, Instant expires) {
        jdbc.update("insert into auth_sessions(id,user_id,created_at,expires_at) values (?,?,?,?)",
                session, user, utc(now), utc(expires));
    }

    public int consume(Token old, String keyHash, String encrypted, String version, Instant now, Instant until) {
        return jdbc.update("""
                update auth_refresh_tokens set active_slot=null, consumed_at=?, request_key_hash=?,
                    replay_response=?, replay_key_version=?, replay_until=?
                where token_hash=? and consumed_at is null
                """, utc(now), keyHash, encrypted, version, utc(until), old.hash());
    }

    public void extendFamily(long user, String family, Instant expires) {
        jdbc.update("update auth_refresh_families set expires_at=? where id=? and user_id=?",
                utc(expires), family, user);
    }

    public void moveDevices(long user, String previous, String next) {
        jdbc.update("update user_devices set session_id=? where user_id=? and session_id=?", next, user, previous);
    }

    public void revokeFamily(long user, String family, Instant now) {
        jdbc.update("update auth_refresh_families set revoked_at=coalesce(revoked_at,?) where id=? and user_id=?",
                utc(now), family, user);
        jdbc.update("""
                update auth_sessions s join auth_refresh_tokens t on t.session_id=s.id and t.user_id=s.user_id
                set s.revoked_at=coalesce(s.revoked_at,?) where t.family_id=? and s.user_id=?
                """, utc(now), family, user);
        jdbc.update("""
                delete d from user_devices d join auth_refresh_tokens t
                  on t.session_id=d.session_id and t.user_id=d.user_id
                where t.family_id=? and d.user_id=?
                """, family, user);
    }

    public void revokeForSession(long user, String session, Instant now) {
        familyForSession(user, session).ifPresent(f -> revokeFamily(user, f, now));
    }

    public Optional<String> familyForSession(long user, String session) {
        return jdbc.query("select family_id from auth_refresh_tokens where user_id=? and session_id=?",
                (r,n) -> r.getString(1), user, session).stream().findFirst();
    }

    public void revokeOtherSessions(long user, String keepSession, Instant now) {
        String family = familyForSession(user, keepSession).orElse("");
        jdbc.update("""
                update auth_refresh_families set revoked_at=coalesce(revoked_at,?)
                where user_id=? and id<>?
                """, utc(now), user, family);
        jdbc.update("""
                update auth_sessions s set s.revoked_at=coalesce(s.revoked_at,?)
                where s.user_id=? and s.id<>? and not exists(
                    select 1 from auth_refresh_tokens t where t.session_id=s.id
                    and t.user_id=s.user_id and t.family_id=?)
                """, utc(now), user, keepSession, family);
        jdbc.update("""
                delete d from user_devices d where d.user_id=? and d.session_id<>? and not exists(
                    select 1 from auth_refresh_tokens t where t.session_id=d.session_id
                    and t.user_id=d.user_id and t.family_id=?)
                """, user, keepSession, family);
    }

    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static Instant instant(ResultSet r, String name) throws SQLException {
        LocalDateTime value = r.getObject(name, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
