package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.dto.request.AppleLoginRequest;
import com.dadot.server.domain.auth.dto.response.SocialLoginResponse;
import com.dadot.server.domain.auth.repository.SocialLoginRequestStore;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.infrastructure.apple.AppleClient;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@RequiredArgsConstructor
@Transactional(
        propagation = Propagation.NOT_SUPPORTED
)
public class AppleLoginService {

    private final AppleClient apple;
    private final AppleLoginChallengeService challenges;
    private final SocialLoginIpLimiter limiter;
    private final SocialLoginRequestStore requests;
    private final SocialLoginTransaction transaction;
    private final VerificationTokenCrypto crypto;

    public AppleLoginChallengeService.Challenge challenge(
            @NotBlank
            @Size(max = 64)
            String remoteAddress
    ) {
        try {
            apple.checkConfigured();

            limiter.consume(remoteAddress);

            return challenges.issue();

        } catch (
                TransientDataAccessException
                | DataAccessResourceFailureException
                | CannotCreateTransactionException ex
        ) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }

    public SocialLoginResponse login(
            @NotNull
            @Valid
            AppleLoginRequest request,

            @NotBlank
            @Pattern(regexp = KakaoLoginService.UUID_PATTERN)
            String idempotencyKey,

            @NotBlank
            @Size(max = 64)
            String remoteAddress
    ) {
        try {
            limiter.consume(remoteAddress);

            var key = requests.appleKey(
                    request,
                    idempotencyKey
            );

            var replay = transaction.replay(key);

            if (replay.isPresent()) {
                return replay.get();
            }

            var identity = apple.verifyIdentity(
                    request.identityToken(),
                    request.nonce()
            );

            String requestHash = crypto.tokenHash(
                    key.scopeHash()
                            + ":"
                            + key.keyHash()
                            + ":"
                            + key.bodyHmac()
            );

            var claim = challenges.consume(
                    request.nonce(),
                    requestHash
            );

            if (!claim.owned()) {
                // 같은 요청이 처리 중이면 코드를 재교환하지 않는다.
                return transaction.replay(key)
                        .orElseThrow(() ->
                                new BusinessException(
                                        ErrorCode.SERVICE_UNAVAILABLE
                                )
                        );
            }

            try {
                var verified = apple.exchange(
                        request.authorizationCode(),
                        request.nonce(),
                        identity
                );

                var validUntil =
                        verified.validUntil().isBefore(claim.expiresAt())
                                ? verified.validUntil()
                                : claim.expiresAt();

                return transaction.complete(
                        key,
                        verified.subject(),
                        validUntil,
                        verified.email()
                );

            } catch (RuntimeException ex) {
                try {
                    challenges.fail(
                            request.nonce(),
                            requestHash
                    );
                } catch (
                        DataAccessException
                        | TransactionException markingFailure
                ) {
                    // 실패 표시가 불가능해도 이미 소비한 코드는 재교환하지 않는다.
                }

                throw ex;
            }

        } catch (
                TransientDataAccessException
                | DataAccessResourceFailureException
                | CannotCreateTransactionException ex
        ) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }
}