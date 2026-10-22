package com.dadot.server.domain.auth.dto.response;

import java.time.Instant;

public record IssueEmailVerificationResponse(
        String verificationId,
        Instant expiresAt,
        Instant resendAvailableAt
) {
}