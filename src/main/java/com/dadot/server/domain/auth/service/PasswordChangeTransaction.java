package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.repository.AuthRequestGuardRepository;
import com.dadot.server.domain.auth.repository.LogoutRepository;
import com.dadot.server.domain.auth.repository.PasswordChangeRepository;
import com.dadot.server.domain.auth.repository.PasswordChangeRepository.Proof;
import com.dadot.server.domain.auth.repository.PasswordChangeRepository.RequestKey;
import com.dadot.server.domain.auth.repository.PasswordChangeRepository.Stored;
import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional(isolation = Isolation.READ_COMMITTED, timeout = 10)
public class PasswordChangeTransaction {

    private final PasswordChangeRepository repository;
    private final LogoutRepository sessions;
    private final AuthRequestGuardRepository guards;
    private final VerificationCrypto hmac;
    private final VerificationTokenCrypto crypto;
    private final Clock clock;

    public boolean replay(RequestKey key, long userId, String sessionId,
                          int authVersion) {
        lockRequest(key);
        var session = lockActor(userId, sessionId, authVersion);
        requireEmail(userId);
        requireUsable(session);

        var stored = repository.findRequest(key);
        if (stored.isEmpty()) {
            return false;
        }

        restore(key, stored.get(), userId);
        return true;
    }

    public void change(RequestKey key, Proof source, String tokenHash,
                       String passwordHash, long userId, String sessionId,
                       int authVersion) {
        lockRequest(key);

        var stored = repository.findRequest(key);
        if (stored.isPresent()) {
            var session = lockActor(userId, sessionId, authVersion);
            requireEmail(userId);
            requireUsable(session);
            restore(key, stored.get(), userId);
            return;
        }

        // 재발송·재설정과 같은 두 목적의 잠금을 같은 순서로 획득한다.
        for (String purpose : new String[]{"PASSWORD_CHANGE", "PASSWORD_RESET"}) {
            guards.lock(
                    hmac.hmac("verification-email-lock", source.email(), purpose),
                    clock.instant().plus(Duration.ofDays(2)));
        }

        repository.lockVerifications(source);
        var session = lockActor(userId, sessionId, authVersion);
        String email = requireEmail(userId);

        if (source.userId() != userId || !source.email().equals(email)) {
            throw invalid();
        }

        var proof = repository.lockProof(source, tokenHash, clock.instant())
                .orElseThrow(this::invalid);

        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!session.usable(now)) {
            throw unauthenticated();
        }
        if (!proof.usable(now)) {
            throw invalid();
        }

        repository.changePassword(proof, passwordHash, now);
        repository.consume(proof, now);
        repository.invalidateOtherProofs(proof, now);
        repository.revokeOtherSessions(userId, sessionId, now);
        repository.complete(key, userId, now);
    }

    private LogoutRepository.Session lockActor(
            long userId, String sessionId, int authVersion) {
        sessions.lockAccount(userId)
                .filter(account -> account.active()
                        && account.authVersion() == authVersion)
                .orElseThrow(this::unauthenticated);

        return sessions.lockSession(userId, sessionId)
                .filter(session -> session.usable(clock.instant()))
                .orElseThrow(this::unauthenticated);
    }

    private void requireUsable(LogoutRepository.Session session) {
        if (!session.usable(clock.instant())) {
            throw unauthenticated();
        }
    }

    private String requireEmail(long userId) {
        return repository.lockEmailIdentity(userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.PASSWORD_AUTH_UNAVAILABLE));
    }

    private void restore(RequestKey key, Stored stored, long userId) {
        if (!crypto.matches(stored.bodyHmac(), key.bodyHmac())
                || !stored.expiresAt().isAfter(clock.instant())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
        }
        if (!"SUCCEEDED".equals(stored.status())
                || !Objects.equals(stored.httpStatus(), 204)
                || !Objects.equals(stored.userId(), userId)) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        // 성공 확인만 반환하며 비밀번호 변경·세션 폐기를 반복하지 않는다.
    }

    private void lockRequest(RequestKey key) {
        guards.lock(
                hmac.hmac("AUTH-09-key-lock", key.scopeHash(), key.keyHash()),
                clock.instant().plus(Duration.ofDays(2)));
    }

    private BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_VERIFICATION_TOKEN);
    }

    private BusinessException unauthenticated() {
        return new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
}