package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.dto.request.EmailLoginRequest;
import com.dadot.server.domain.auth.dto.response.EmailLoginResponse;
import com.dadot.server.domain.auth.repository.EmailLoginRepository;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.UUID;

@Service
@Validated
public class EmailLoginService {

    private final LoginAttemptLimiter limiter;
    private final EmailLoginRepository repository;
    private final EmailLoginSessionService sessions;
    private final PasswordEncoder passwords;
    private final String dummyPasswordHash;

    public EmailLoginService(
            LoginAttemptLimiter limiter,
            EmailLoginRepository repository,
            EmailLoginSessionService sessions,
            PasswordEncoder passwords
    ) {
        this.limiter = limiter;
        this.repository = repository;
        this.sessions = sessions;
        this.passwords = passwords;
        this.dummyPasswordHash = passwords.encode(UUID.randomUUID().toString());
    }

    // 비용이 큰 비밀번호 검증 중에는 DB 트랜잭션을 유지하지 않는다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public EmailLoginResponse login(
            @NotNull @Valid EmailLoginRequest request,
            @NotBlank @Size(max = 64) String remoteAddress
    ) {
        try {
            limiter.consume(request.email(), remoteAddress);
            var credential = repository.findCredential(request.email());
            String hash = credential
                    .map(EmailLoginRepository.Credential::passwordHash)
                    .orElse(dummyPasswordHash);

            // 미가입 이메일도 같은 알고리즘으로 검증해 즉시 반환하지 않는다.
            boolean matches = matches(request.password(), hash);

            if (credential.isEmpty() || !matches) {
                throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
            }

            return sessions.create(credential.get(), request.email());

        } catch (TransientDataAccessException
                 | DataAccessResourceFailureException ex) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private boolean matches(String raw, String encoded) {
        try {
            return passwords.matches(raw, encoded);
        } catch (IllegalArgumentException ex) {
            // 저장된 해시 손상을 잘못된 비밀번호로 처리하지 않는다.
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}