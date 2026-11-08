package com.dadot.server.domain.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record SocialLoginResponse(
        Status status,
        Long userId,
        String accessToken,
        String tokenType,
        Instant expiresAt,
        Boolean onboardingCompleted,
        String registrationToken,
        Instant registrationExpiresAt,
        String refreshToken,
        Instant refreshExpiresAt
) {

    public enum Status {
        LOGIN_COMPLETED,
        SIGN_UP_REQUIRED
    }

    public SocialLoginResponse(Status status, Long userId, String accessToken, String tokenType,
            Instant expiresAt, Boolean onboardingCompleted, String registrationToken, Instant registrationExpiresAt) {
        this(status, userId, accessToken, tokenType, expiresAt, onboardingCompleted,
                registrationToken, registrationExpiresAt, null, null);
    }

    public SocialLoginResponse {
        boolean login = status == Status.LOGIN_COMPLETED
                && userId != null
                && userId > 0
                && accessToken != null
                && !accessToken.isBlank()
                && "Bearer".equals(tokenType)
                && expiresAt != null
                && onboardingCompleted != null
                && registrationToken == null
                && registrationExpiresAt == null;

        boolean signup = status == Status.SIGN_UP_REQUIRED
                && userId == null
                && accessToken == null
                && tokenType == null
                && expiresAt == null
                && onboardingCompleted == null
                && registrationToken != null
                && !registrationToken.isBlank()
                && registrationExpiresAt != null
                && refreshToken == null && refreshExpiresAt == null;

        if (!login && !signup) {
            throw new IllegalArgumentException(
                    "Invalid social login result"
            );
        }
    }

    public static SocialLoginResponse login(
            long userId,
            String token,
            Instant expiresAt,
            boolean onboarded
    ) {
        return new SocialLoginResponse(
                Status.LOGIN_COMPLETED,
                userId,
                token,
                "Bearer",
                expiresAt,
                onboarded,
                null,
                null
        );
    }

    public static SocialLoginResponse login(long userId, String token, Instant expiresAt,
            boolean onboarded, String refreshToken, Instant refreshExpiresAt) {
        return new SocialLoginResponse(Status.LOGIN_COMPLETED, userId, token, "Bearer", expiresAt,
                onboarded, null, null, refreshToken, refreshExpiresAt);
    }

    public static SocialLoginResponse signup(
            String token,
            Instant expiresAt
    ) {
        return new SocialLoginResponse(
                Status.SIGN_UP_REQUIRED,
                null,
                null,
                null,
                null,
                null,
                token,
                expiresAt
        );
    }

    @Override
    public String toString() {
        return "SocialLoginResponse[status="
                + status
                + ", tokens=REDACTED]";
    }
}