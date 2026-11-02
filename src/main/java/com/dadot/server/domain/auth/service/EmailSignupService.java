package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.dto.request.EmailSignupRequest;
import com.dadot.server.domain.auth.dto.response.EmailSignupResponse;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
@RequiredArgsConstructor
public class EmailSignupService {

    private final EmailSignupTransaction transaction;

    public EmailSignupResponse signup(
            @NotNull @Valid EmailSignupRequest request,
            String requestKey
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
            var result = transaction.signup(
                    request,
                    requestKey
            );

            // 실패 결과 저장이 커밋된 뒤 예외를 던진다.
            if (result.error() != null) {
                throw new BusinessException(
                        result.error()
                );
            }

            return result.response();

        } catch (
                TransientDataAccessException
                | DataAccessResourceFailureException ex
        ) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }
}