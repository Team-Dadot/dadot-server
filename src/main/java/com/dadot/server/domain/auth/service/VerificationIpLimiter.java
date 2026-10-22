package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.repository.AuthRequestGuardRepository;
import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.properties.EmailVerificationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class VerificationIpLimiter {

    private final AuthRequestGuardRepository guards;
    private final VerificationCrypto crypto;
    private final EmailVerificationProperties properties;
    private final Clock clock;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            timeout = 5
    )
    public void consume(String remoteAddress) {
        Instant now = clock.instant();

        Map<String, Integer> limits = new TreeMap<>();

        limits.put(
                crypto.hmac(
                        "verification-ip-minute",
                        remoteAddress,
                        Long.toString(
                                Math.floorDiv(now.getEpochSecond(), 60)
                        )
                ),
                properties.ipPerMinute()
        );

        limits.put(
                crypto.hmac(
                        "verification-ip-hour",
                        remoteAddress,
                        Long.toString(
                                Math.floorDiv(now.getEpochSecond(), 3600)
                        )
                ),
                properties.ipPerHour()
        );

        for (var entry : limits.entrySet()) {
            int count = guards.lock(
                    entry.getKey(),
                    now.plus(Duration.ofDays(2))
            );

            if (count >= entry.getValue()) {
                throw new BusinessException(ErrorCode.RATE_LIMITED);
            }

            guards.increment(entry.getKey());
        }
    }
}