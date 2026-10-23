package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.repository.AuthRequestGuardRepository;
import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

@Service
public class LoginAttemptLimiter {

    private final AuthRequestGuardRepository guards;
    private final VerificationCrypto crypto;
    private final Clock clock;
    private final int ipPerMinute;
    private final int ipPerHour;
    private final int emailPerTenMinutes;

    public LoginAttemptLimiter(
            AuthRequestGuardRepository guards,
            VerificationCrypto crypto,
            Clock clock,
            @Value("${app.email-login.ip-per-minute:20}") int ipPerMinute,
            @Value("${app.email-login.ip-per-hour:100}") int ipPerHour,
            @Value("${app.email-login.email-per-ten-minutes:10}")
            int emailPerTenMinutes
    ) {
        if (ipPerMinute < 1 || ipPerHour < 1 || emailPerTenMinutes < 1) {
            throw new IllegalArgumentException("Login limits must be positive");
        }
        this.guards = guards;
        this.crypto = crypto;
        this.clock = clock;
        this.ipPerMinute = ipPerMinute;
        this.ipPerHour = ipPerHour;
        this.emailPerTenMinutes = emailPerTenMinutes;
    }

    // 로그인 실패와 별도로 커밋해 실패 요청도 제한에 포함한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 5)
    public void consume(String email, String remoteAddress) {
        var now = clock.instant();
        long seconds = now.getEpochSecond();
        Map<String, Integer> limits = new TreeMap<>();

        limits.put(crypto.hmac(
                "login-ip-minute", remoteAddress,
                Long.toString(Math.floorDiv(seconds, 60))
        ), ipPerMinute);

        limits.put(crypto.hmac(
                "login-ip-hour", remoteAddress,
                Long.toString(Math.floorDiv(seconds, 3600))
        ), ipPerHour);

        limits.put(crypto.hmac(
                "login-email-ten-minutes", email,
                Long.toString(Math.floorDiv(seconds, 600))
        ), emailPerTenMinutes);

        // 모든 요청이 동일한 순서로 잠가 교착 가능성을 줄인다.
        for (var entry : limits.entrySet()) {
            int count = guards.lock(
                    entry.getKey(), now.plus(Duration.ofDays(2))
            );
            if (count >= entry.getValue()) {
                throw new BusinessException(ErrorCode.RATE_LIMITED);
            }
            guards.increment(entry.getKey());
        }
    }
}