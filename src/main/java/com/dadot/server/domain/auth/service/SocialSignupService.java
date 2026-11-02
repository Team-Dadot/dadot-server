package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.dto.request.SocialSignupRequest;
import com.dadot.server.domain.auth.dto.response.SocialSignupResponse;
import com.dadot.server.domain.auth.repository.SocialSignupRequestStore;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@RequiredArgsConstructor
public class SocialSignupService {

    public static final String UUID_PATTERN =
            "(?i)\\A[0-9a-f]{8}-[0-9a-f]{4}-"
                    + "[0-9a-f]{4}-[0-9a-f]{4}-"
                    + "[0-9a-f]{12}\\z";

    private final SocialSignupTransaction transaction;
    private final SocialSignupRequestStore requests;
    private final SocialLoginIpLimiter limiter;

    @Transactional(
            propagation = Propagation.NOT_SUPPORTED
    )
    public SocialSignupResponse signup(
            @NotNull
            @Valid
            SocialSignupRequest request,

            @NotBlank
            @Pattern(regexp = UUID_PATTERN)
            String idempotencyKey,

            @NotBlank
            @Size(max = 64)
            String remoteAddress
    ) {
        long uniqueCount = request.acceptedTermsIds()
                .stream()
                .distinct()
                .count();

        if (uniqueCount != request.acceptedTermsIds().size()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT
            );
        }

        try {
            limiter.consume(remoteAddress);

            var result = transaction.signup(
                    request,
                    requests.key(
                            request,
                            idempotencyKey
                    )
            );

            // 약관 오류 등 실패 결과가 커밋된 뒤 예외를 던진다.
            if (result.error() != null) {
                throw new BusinessException(
                        result.error()
                );
            }

            return result.response();

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