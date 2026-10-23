package com.dadot.server.domain.auth.dto.response;

import java.time.Instant;

public record EmailSignupResponse(
        long userId,
        String accessToken,
        String tokenType,
        Instant expiresAt,
        boolean onboardingCompleted
) {
    @Override
    public String toString() {
        return "EmailSignupResponse[userId="
                + userId
                + ", accessToken=REDACTED]";
    }
}