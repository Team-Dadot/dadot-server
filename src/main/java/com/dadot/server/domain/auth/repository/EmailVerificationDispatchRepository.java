package com.dadot.server.domain.auth.repository;

import com.dadot.server.domain.auth.entity.VerificationPurpose;
import com.dadot.server.domain.auth.support.VerificationCrypto;
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
public class EmailVerificationDispatchRepository {
    private final JdbcTemplate jdbc;
    private final VerificationCrypto crypto;

    public record Delivery(String key, String body, String id, Long user, String identityStamp,
                           String email, VerificationPurpose purpose, String encryptedCode, String keyVersion,
                           Instant createdAt, Instant expiresAt, String owner, Instant leaseUntil, Instant sentAt) {
        @Override public String toString() { return "EmailVerificationDelivery[REDACTED]"; }
        public String encryptionContext() { return "AUTH-01-delivery:" + key + ":" + id; }
    }

    public Optional<Delivery> byKey(String key) {
        return jdbc.query("select * from email_verification_deliveries where request_key_hash=?",
                (rs, n) -> delivery(rs), key).stream().findFirst();
    }

    public Optional<Delivery> byEmail(String email, VerificationPurpose purpose) {
        return jdbc.query("select * from email_verification_deliveries where email=? and purpose=?",
                (rs, n) -> delivery(rs), email, purpose.name()).stream().findFirst();
    }

    // 비밀번호 변경이 현재 세션을 보존해도, 변경 전 예약한 인증번호가 나중에 활성화되지 않게 한다.
    public String identityStamp(long user, String email) {
        return jdbc.query("""
                select i.password_hash, u.auth_version from auth_identities i join users u on u.id=i.user_id
                where i.user_id=? and i.provider='EMAIL' and i.provider_subject=? and u.status='ACTIVE'
                """, (rs, n) -> crypto.hmac("AUTH-01-identity", Long.toString(user), email,
                        rs.getString(1), Integer.toString(rs.getInt(2))), user, email)
                .stream().findFirst().orElse(null);
    }

    public void insert(Delivery d) {
        changed(jdbc.update("""
                insert into email_verification_deliveries(request_key_hash,body_hmac,verification_id,user_id,
                    identity_stamp,email,purpose,encrypted_code,code_key_version,created_at,expires_at,
                    lease_owner,lease_until) values (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, d.key(), d.body(), d.id(), d.user(), d.identityStamp(), d.email(), d.purpose().name(),
                d.encryptedCode(), d.keyVersion(), utc(d.createdAt()), utc(d.expiresAt()), d.owner(), utc(d.leaseUntil())));
    }

    public Delivery claim(Delivery d, String owner, Instant until) {
        changed(jdbc.update("""
                update email_verification_deliveries set lease_owner=?,lease_until=?
                where request_key_hash=? and lease_owner=? and sent_at is null
                """, owner, utc(until), d.key(), d.owner()));
        return byKey(d.key()).orElseThrow();
    }

    public void sent(Delivery d, Instant now) {
        changed(jdbc.update("""
                update email_verification_deliveries set sent_at=?
                where request_key_hash=? and lease_owner=? and sent_at is null
                """, utc(now), d.key(), d.owner()));
    }

    public void delete(Delivery d) {
        changed(jdbc.update("delete from email_verification_deliveries where request_key_hash=? and lease_owner=?",
                d.key(), d.owner()));
    }

    public void cancel(Delivery d) {
        jdbc.update("""
                delete from email_verification_deliveries
                where request_key_hash=? and lease_owner=? and sent_at is null
                """, d.key(), d.owner());
    }

    private static Delivery delivery(ResultSet rs) throws SQLException {
        return new Delivery(rs.getString("request_key_hash"), rs.getString("body_hmac"), rs.getString("verification_id"),
                rs.getObject("user_id", Long.class), rs.getString("identity_stamp"), rs.getString("email"),
                VerificationPurpose.valueOf(rs.getString("purpose")), rs.getString("encrypted_code"),
                rs.getString("code_key_version"), instant(rs, "created_at"), instant(rs, "expires_at"),
                rs.getString("lease_owner"), instant(rs, "lease_until"), instant(rs, "sent_at"));
    }
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        var value = rs.getObject(name, LocalDateTime.class); return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static void changed(int count) {
        if (count != 1) throw new IllegalStateException("Email verification delivery state changed");
    }
}
