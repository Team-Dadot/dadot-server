package com.dadot.server.domain.member.service;

import com.dadot.server.domain.auth.repository.AuthRequestGuardRepository;
import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.TreeMap;

@Service
public class AccountReauthenticationLimiter {
    private final AuthRequestGuardRepository guards;
    private final VerificationCrypto crypto;
    private final Clock clock;
    private final int perTenMinutes;
    private final int perHour;

    public AccountReauthenticationLimiter(
            AuthRequestGuardRepository guards,
            VerificationCrypto crypto,
            Clock clock,
            @Value("${app.account-deletion.email-attempts-per-ten-minutes:5}")
            int perTenMinutes,
            @Value("${app.account-deletion.email-attempts-per-hour:20}")
            int perHour
    ) {
        if (perTenMinutes < 1
                || perTenMinutes > 1000
                || perHour < 1
                || perHour > 1000) {
            throw new IllegalArgumentException(
                    "Invalid account reauthentication limits"
            );
        }

        this.guards = guards;
        this.crypto = crypto;
        this.clock = clock;
        this.perTenMinutes = perTenMinutes;
        this.perHour = perHour;
    }

    // 비밀번호 비교 전에 시도권을 원자적으로 사용하고 별도로 커밋한다.
    // 실패 응답·탈퇴 트랜잭션 롤백·IP/세션/요청 키 변경으로 복구되지 않는다.
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            timeout = 5
    )
    public void consume(long user) {
        if (user <= 0) {
            throw new IllegalArgumentException("Invalid member ID");
        }

        var now = clock.instant();
        Map<String, Integer> limits = new TreeMap<>();

        limits.put(
                crypto.hmac(
                        "member-delete-email-ten-minutes",
                        Long.toString(user),
                        Long.toString(
                                Math.floorDiv(now.getEpochSecond(), 600)
                        )
                ),
                perTenMinutes
        );

        limits.put(
                crypto.hmac(
                        "member-delete-email-hour",
                        Long.toString(user),
                        Long.toString(
                                Math.floorDiv(now.getEpochSecond(), 3600)
                        )
                ),
                perHour
        );

        for (var limit : limits.entrySet()) {
            int used = guards.lock(
                    limit.getKey(),
                    now.plusSeconds(172800)
            );

            if (used >= limit.getValue()) {
                throw new BusinessException(ErrorCode.RATE_LIMITED);
            }

            guards.increment(limit.getKey());
        }
    }
}