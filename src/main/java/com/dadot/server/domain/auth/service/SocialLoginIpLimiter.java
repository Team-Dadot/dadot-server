package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.repository.AuthRequestGuardRepository;
import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class SocialLoginIpLimiter {

    private final AuthRequestGuardRepository guards;
    private final VerificationCrypto crypto;
    private final Clock clock;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            timeout = 5
    )
    public void consume(String ip) {
        var now = clock.instant();
        long seconds = now.getEpochSecond();

        Map<String, Integer> limits = new TreeMap<>();

        limits.put(
                crypto.hmac(
                        "social-login-ip-minute",
                        ip,
                        Long.toString(
                                Math.floorDiv(seconds, 60)
                        )
                ),
                20
        );

        limits.put(
                crypto.hmac(
                        "social-login-ip-hour",
                        ip,
                        Long.toString(
                                Math.floorDiv(seconds, 3600)
                        )
                ),
                100
        );

        for (var entry : limits.entrySet()) {
            int count = guards.lock(
                    entry.getKey(),
                    now.plus(Duration.ofDays(2))
            );

            if (count >= entry.getValue()) {
                throw new BusinessException(
                        ErrorCode.RATE_LIMITED
                );
            }

            guards.increment(entry.getKey());
        }
    }
}