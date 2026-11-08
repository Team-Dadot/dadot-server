package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.dto.request.RefreshTokenRequest;
import com.dadot.server.domain.auth.dto.response.RefreshTokenResponse;
import com.dadot.server.domain.auth.repository.RefreshTokenRepository;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.AccessTokenIssuer;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class RefreshTokenService {
    private static final Duration LIFETIME = Duration.ofDays(30);
    private static final Duration REPLAY_WINDOW = Duration.ofMinutes(5);
    private final RefreshTokenRepository repository;
    private final VerificationTokenCrypto crypto;
    private final AccessTokenIssuer tokens;
    private final JsonMapper mapper;
    private final Clock clock;
    private final RefreshTokenIpLimiter limiter;
    private final TransactionTemplate transaction;

    public RefreshTokenService(RefreshTokenRepository repository, VerificationTokenCrypto crypto,
                               AccessTokenIssuer tokens, JsonMapper mapper, Clock clock,
                               RefreshTokenIpLimiter limiter, PlatformTransactionManager manager) {
        this.repository=repository; this.crypto=crypto; this.tokens=tokens;
        this.mapper=mapper; this.clock=clock; this.limiter=limiter;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(10);
    }

    public record Issued(String refreshToken, Instant refreshExpiresAt) {
        @Override public String toString() { return "IssuedRefreshToken[REDACTED]"; }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Issued issue(long user, String session, int authVersion, Instant issuedAt) {
        Instant now = issuedAt.truncatedTo(ChronoUnit.SECONDS);
        String raw = crypto.generateToken();
        String family = UUID.randomUUID().toString();
        Instant expires = now.plus(LIFETIME);
        repository.createFamily(family, user, authVersion, now, expires);
        repository.createToken(crypto.tokenHash(raw), family, user, session, now, expires);
        return new Issued(raw, expires);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCachedUsable(String raw, Instant expires, long user, String session) {
        // Pre-upgrade cached login/signup responses have neither field.
        if (raw == null && expires == null) return;
        var account = repository.lockAccount(user).orElse(null);
        var token = raw == null ? null : repository.find(crypto.tokenHash(raw)).orElse(null);
        if (account == null || token == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        var family = repository.lockFamily(user, token.family()).orElse(null);
        var now = clock.instant();
        if (!account.active() || family == null || family.revoked() || family.version()!=account.version()
                || token.user()!=user || !token.session().equals(session) || token.consumed()!=null
                || !token.expires().equals(expires) || !token.expires().isAfter(now)
                || !family.expires().isAfter(now) || !repository.sessionValid(user, session)) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
    }

    public RefreshTokenResponse refresh(RefreshTokenRequest request, String key, String ip) {
        if (key == null || key.length()!=36) throw invalidInput();
        try { if (!UUID.fromString(key).toString().equals(key)) throw invalidInput(); }
        catch (IllegalArgumentException ex) { throw invalidInput(); }
        try {
            limiter.consume(ip);
            RefreshTokenResponse response = transaction.execute(tx -> rotate(request.refreshToken(), key));
            // Returning null commits a replay-triggered family revocation before responding 401.
            if (response == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
            return response;
        } catch (DataAccessException | org.springframework.transaction.TransactionException ex) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private RefreshTokenResponse rotate(String raw, String key) {
        String hash = crypto.tokenHash(raw);
        var initial = repository.find(hash).orElse(null);
        if (initial == null) return null;
        // Serialize refresh, logout, password changes and deletion by locking the same user first.
        var account = repository.lockAccount(initial.user()).orElse(null);
        var family = repository.lockFamily(initial.user(), initial.family()).orElse(null);
        var old = repository.find(hash).orElse(null);
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        if (account == null || family == null || old == null) return null;
        if (!account.active() || account.version()!=family.version() || family.revoked()
                || !family.expires().isAfter(now) || old.issued().isAfter(now) || !old.expires().isAfter(now)
                || !repository.sessionValid(old.user(), old.session())) return null;

        String keyHash = crypto.tokenHash("refresh-request:" + key);
        String context = "refresh:" + old.family() + ":" + old.hash() + ":" + keyHash;
        if (old.consumed() != null) {
            if (!keyHash.equals(old.keyHash())) {
                repository.revokeFamily(old.user(), old.family(), now);
                return null;
            }
            if (old.replayUntil() == null || !old.replayUntil().isAfter(now)) return null;
            var replay = mapper.readValue(crypto.decrypt(old.encrypted(), old.keyVersion(), context), RefreshTokenResponse.class);
            var head = repository.find(crypto.tokenHash(replay.refreshToken())).orElse(null);
            // A late duplicate must never roll the client back to an already-consumed successor.
            if (head == null || head.consumed()!=null || !head.family().equals(old.family())
                    || !repository.sessionValid(old.user(), head.session()) || !head.expires().isAfter(now)
                    || !replay.expiresAt().isAfter(now)) return null;
            return replay;
        }

        String nextRaw=crypto.generateToken();
        String session=UUID.randomUUID().toString();
        Instant accessExpires=now.plusSeconds(3600);
        Instant refreshExpires=now.plus(LIFETIME);
        var response=new RefreshTokenResponse(old.user(),
                tokens.issue(old.user(), session, account.version(), now, accessExpires),
                "Bearer", accessExpires, nextRaw, refreshExpires);
        String encrypted=crypto.encrypt(mapper.writeValueAsString(response), context);
        if (repository.consume(old, keyHash, encrypted, VerificationTokenCrypto.KEY_VERSION,
                now, now.plus(REPLAY_WINDOW))!=1) throw new IllegalStateException("Concurrent refresh state changed");
        repository.createAccessSession(session, old.user(), now, accessExpires);
        repository.createToken(crypto.tokenHash(nextRaw), old.family(), old.user(), session, now, refreshExpires);
        repository.extendFamily(old.user(), old.family(), refreshExpires);
        repository.moveDevices(old.user(), old.session(), session);
        return response;
    }

    private static BusinessException invalidInput() { return new BusinessException(ErrorCode.INVALID_INPUT); }
}
