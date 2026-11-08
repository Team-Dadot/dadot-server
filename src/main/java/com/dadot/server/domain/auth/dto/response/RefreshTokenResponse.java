package com.dadot.server.domain.auth.dto.response;

import java.time.Instant;

public record RefreshTokenResponse(long userId, String accessToken, String tokenType,
                                   Instant expiresAt, String refreshToken, Instant refreshExpiresAt) {
    @Override public String toString() {
        return "RefreshTokenResponse[userId=" + userId + ", tokens=REDACTED]";
    }
}
