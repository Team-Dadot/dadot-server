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
public class RefreshTokenIpLimiter {
    private final AuthRequestGuardRepository guards;
    private final VerificationCrypto crypto;
    private final Clock clock;

    @Transactional(propagation=Propagation.REQUIRES_NEW, timeout=5)
    public void consume(String ip) {
        if (ip == null || ip.isBlank() || ip.length()>64) throw new BusinessException(ErrorCode.INVALID_INPUT);
        var now=clock.instant();
        Map<String,Integer> limits=new TreeMap<>();
        limits.put(crypto.hmac("refresh-ip-minute",ip,Long.toString(now.getEpochSecond()/60)),60);
        limits.put(crypto.hmac("refresh-ip-hour",ip,Long.toString(now.getEpochSecond()/3600)),1000);
        for (var entry:limits.entrySet()) {
            int count=guards.lock(entry.getKey(),now.plus(Duration.ofDays(2)));
            if (count>=entry.getValue()) throw new BusinessException(ErrorCode.RATE_LIMITED);
            guards.increment(entry.getKey());
        }
    }
}
