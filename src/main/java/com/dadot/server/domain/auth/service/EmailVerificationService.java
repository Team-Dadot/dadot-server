package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.dto.request.IssueEmailVerificationRequest;
import com.dadot.server.domain.auth.dto.response.IssueEmailVerificationResponse;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final VerificationIpLimiter limiter;
    private final EmailVerificationIssuer issuer;

    public IssueEmailVerificationResponse issue(
            IssueEmailVerificationRequest request,
            String requestKey,
            String remoteAddress,
            Long authenticatedUserId
    ) {
        try {
            limiter.consume(remoteAddress);

            return issuer.issue(
                    request,
                    requestKey,
                    authenticatedUserId
            );

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