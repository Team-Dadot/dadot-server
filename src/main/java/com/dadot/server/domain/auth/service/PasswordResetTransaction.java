package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.repository.AuthRequestGuardRepository;
import com.dadot.server.domain.auth.repository.PasswordResetRepository;
import com.dadot.server.domain.auth.repository.PasswordResetRepository.Proof;
import com.dadot.server.domain.auth.repository.PasswordResetRepository.RequestKey;
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
@Transactional(
        isolation = Isolation.READ_COMMITTED,
        timeout = 10
)
public class PasswordResetTransaction {

    private final PasswordResetRepository repository;
    private final AuthRequestGuardRepository guards;
    private final VerificationCrypto hmac;
    private final VerificationTokenCrypto crypto;
    private final Clock clock;

    public boolean replay(RequestKey key) {
        lockRequest(key);

        return restore(key);
    }

    public void reset(
            RequestKey key,
            Proof source,
            String tokenHash,
            String passwordHash
    ) {
        lockRequest(key);

        if (restore(key)) {
            return;
        }

        // 재발송과 같은 잠금을 사용하며 두 목적을 일정한 순서로 잠근다.
        for (String purpose : new String[]{
                "PASSWORD_CHANGE",
                "PASSWORD_RESET"
        }) {
            guards.lock(
                    hmac.hmac(
                            "verification-email-lock",
                            source.email(),
                            purpose
                    ),
                    clock.instant().plus(Duration.ofDays(2))
            );
        }

        repository.lockVerifications(source);

        var account = repository.lockAccount(source.userId())
                .filter(PasswordResetRepository.Account::active)
                .orElseThrow(this::invalid);

        if (account.authVersion() == Integer.MAX_VALUE) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }

        if (!repository.lockIdentity(source)) {
            throw invalid();
        }

        var proof = repository.lockProof(
                        source,
                        tokenHash,
                        clock.instant()
                )
                .filter(value ->
                        value.usable(clock.instant())
                )
                .orElseThrow(this::invalid);

        var now = clock.instant()
                .truncatedTo(ChronoUnit.MICROS);

        if (!proof.usable(now)) {
            throw invalid();
        }

        repository.changePassword(
                proof,
                passwordHash,
                now
        );

        repository.consume(proof, now);

        repository.invalidateOtherProofs(proof, now);

        repository.revokeSessions(proof.userId(), now);

        repository.complete(
                key,
                proof.userId(),
                now
        );
    }

    private boolean restore(RequestKey key) {
        var stored = repository.findRequest(key);

        if (stored.isEmpty()) {
            return false;
        }

        var value = stored.get();

        if (!crypto.matches(
                value.bodyHmac(),
                key.bodyHmac()
        ) || !value.expiresAt().isAfter(clock.instant())) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_CONFLICT
            );
        }

        if (!"SUCCEEDED".equals(value.status())
                || !Objects.equals(value.httpStatus(), 204)
                || value.userId() == null) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }

        repository.lockAccount(value.userId())
                .filter(PasswordResetRepository.Account::active)
                .orElseThrow(this::invalid);

        // 비밀번호 저장이나 세션 폐기를 다시 수행하지 않는다.
        return true;
    }

    private void lockRequest(RequestKey key) {
        guards.lock(
                hmac.hmac(
                        "AUTH-08-key-lock",
                        key.keyHash()
                ),
                clock.instant().plus(Duration.ofDays(2))
        );
    }

    private BusinessException invalid() {
        return new BusinessException(
                ErrorCode.INVALID_VERIFICATION_TOKEN
        );
    }
}