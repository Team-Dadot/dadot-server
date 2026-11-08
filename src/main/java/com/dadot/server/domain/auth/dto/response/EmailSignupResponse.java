package com.dadot.server.domain.auth.dto.response;

import java.time.Instant;

public record EmailSignupResponse(
        long userId,
        String accessToken,
        String tokenType,
        Instant expiresAt,
        boolean onboardingCompleted,
        String refreshToken,
        Instant refreshExpiresAt
) {

    // Legacy cached responses and existing Java callers may not have refresh fields yet.
    public EmailSignupResponse(long userId, String accessToken, String tokenType,
            Instant expiresAt, boolean onboardingCompleted) {
        this(userId, accessToken, tokenType, expiresAt, onboardingCompleted, null, null);
    }
    @Override
    public String toString() {
        return "EmailSignupResponse[userId="
                + userId
                + ", accessToken=REDACTED]";
    }
}