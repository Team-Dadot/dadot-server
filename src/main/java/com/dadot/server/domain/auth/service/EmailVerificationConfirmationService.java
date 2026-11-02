package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.dto.response.VerifyEmailVerificationResponse;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailVerificationConfirmationService {

    private final EmailVerificationConfirmer confirmer;

    public VerifyEmailVerificationResponse verify(
            String id,
            String code,
            String requestKey,
            Long actor
    ) {
        EmailVerificationConfirmer.Result result;

        try {
            result = confirmer.confirm(
                    id,
                    code,
                    requestKey,
                    actor
            );

        } catch (
                TransientDataAccessException
                | DataAccessResourceFailureException ex
        ) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }

        // 실패 횟수와 멱등 결과가 커밋된 뒤 예외를 전달한다.
        if (result.error() != null) {
            throw new BusinessException(
                    result.error()
            );
        }

        return result.response();
    }
}