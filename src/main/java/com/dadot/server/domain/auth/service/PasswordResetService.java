package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.dto.request.PasswordResetRequest;
import com.dadot.server.domain.auth.repository.PasswordResetRepository;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.Clock;

@Service
@Validated
@RequiredArgsConstructor
@Transactional(
        propagation = Propagation.NOT_SUPPORTED
)
public class PasswordResetService {

    public static final String UUID_PATTERN =
            "(?i)\\A[0-9a-f]{8}-[0-9a-f]{4}-"
                    + "[0-9a-f]{4}-[0-9a-f]{4}-"
                    + "[0-9a-f]{12}\\z";

    private final VerificationIpLimiter limiter;
    private final PasswordResetRepository repository;
    private final PasswordResetTransaction transaction;
    private final VerificationTokenCrypto crypto;
    private final PasswordEncoder passwords;
    private final Clock clock;

    public void reset(
            @NotNull
            @Valid
            PasswordResetRequest request,

            @NotBlank
            @Pattern(regexp = UUID_PATTERN)
            String idempotencyKey,

            @NotBlank
            @Size(max = 64)
            String remoteAddress
    ) {
        try {
            limiter.consume(remoteAddress);

            var key = repository.key(
                    request,
                    idempotencyKey
            );

            if (transaction.replay(key)) {
                return;
            }

            String tokenHash = crypto.tokenHash(
                    request.verificationToken()
            );

            var proof = repository.findUsableProof(
                            tokenHash,
                            clock.instant()
                    )
                    .orElseThrow(() ->
                            new BusinessException(
                                    ErrorCode.INVALID_VERIFICATION_TOKEN
                            )
                    );

            // 해시 계산 중 DB 트랜잭션이나 행 잠금을 유지하지 않는다.
            String encoded = passwords.encode(
                    request.newPassword()
            );

            transaction.reset(
                    key,
                    proof,
                    tokenHash,
                    encoded
            );

        } catch (
                TransientDataAccessException
                | DataAccessResourceFailureException
                | TransactionException ex
        ) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }
}