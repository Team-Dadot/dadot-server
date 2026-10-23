package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.dto.response.EmailLoginResponse;
import com.dadot.server.domain.auth.repository.EmailLoginRepository;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.AccessTokenIssuer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailLoginSessionService {

    private final EmailLoginRepository repository;
    private final AccessTokenIssuer tokens;
    private final Clock clock;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED,
            timeout = 5
    )
    public EmailLoginResponse create(
            EmailLoginRepository.Credential verified,
            String email
    ) {
        var account = repository.lockAccount(verified.userId())
                .filter(EmailLoginRepository.Account::active)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVALID_CREDENTIALS
                ));

        // 검증 중 비밀번호 변경·로그인 수단 삭제가 발생했다면 거절한다.
        boolean unchanged = repository.lockPassword(verified.userId(), email)
                .filter(verified.passwordHash()::equals)
                .isPresent();

        if (!unchanged) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        var issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var expiresAt = issuedAt.plusSeconds(3600);
        String sessionId = UUID.randomUUID().toString();

        repository.createSession(
                sessionId, verified.userId(), issuedAt, expiresAt
        );

        String accessToken = tokens.issue(
                verified.userId(), sessionId, account.authVersion(),
                issuedAt, expiresAt
        );

        return new EmailLoginResponse(
                verified.userId(), accessToken, "Bearer", expiresAt,
                account.onboardingCompleted()
        );
    }
}