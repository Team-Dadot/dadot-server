package com.dadot.server.domain.auth.dto.response;

import java.time.Instant;

public record SocialSignupResponse(
        long userId,
        String accessToken,
        String tokenType,
        Instant expiresAt,
        boolean onboardingCompleted,
        String refreshToken,
        Instant refreshExpiresAt
) {

    // Legacy cached responses and existing Java callers may not have refresh fields yet.
    public SocialSignupResponse(long userId, String accessToken, String tokenType,
            Instant expiresAt, boolean onboardingCompleted) {
        this(userId, accessToken, tokenType, expiresAt, onboardingCompleted, null, null);
    }

    public SocialSignupResponse {
        if (userId <= 0
                || accessToken == null
                || accessToken.isBlank()
                || !"Bearer".equals(tokenType)
                || expiresAt == null) {
            throw new IllegalArgumentException(
                    "Invalid social signup result"
            );
        }
    }

    @Override
    public String toString() {
        return "SocialSignupResponse[userId="
                + userId
                + ", accessToken=REDACTED]";
    }
}