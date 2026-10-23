package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.repository.LogoutRepository;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
@RequiredArgsConstructor
public class LogoutTransaction {

    private final LogoutRepository repository;
    private final Clock clock;

    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            timeout = 5
    )
    public void logout(
            long userId,
            String sessionId,
            int authVersion
    ) {
        // 회원 → 세션 → 기기 순서로 잠그고 처리한다.
        repository.lockAccount(userId)
                .filter(value ->
                        value.active()
                                && value.authVersion() == authVersion
                )
                .orElseThrow(() ->
                        new BusinessException(
                                ErrorCode.UNAUTHENTICATED
                        )
                );

        var session = repository.lockSession(
                        userId,
                        sessionId
                )
                .orElseThrow(() ->
                        new BusinessException(
                                ErrorCode.UNAUTHENTICATED
                        )
                );

        // 인증 이후 잠금 대기 중 만료·폐기된 세션도 거절한다.
        var now = clock.instant();

        if (!session.usable(now)) {
            throw new BusinessException(
                    ErrorCode.UNAUTHENTICATED
            );
        }

        if (repository.revoke(userId, sessionId, now) != 1) {
            throw new BusinessException(
                    ErrorCode.UNAUTHENTICATED
            );
        }

        repository.detachDevices(userId, sessionId);
    }
}