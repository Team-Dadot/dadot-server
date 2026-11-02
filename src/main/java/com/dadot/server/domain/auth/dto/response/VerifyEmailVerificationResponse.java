package com.dadot.server.domain.auth.dto.response;

import java.time.Instant;

public record VerifyEmailVerificationResponse(
        String verificationToken,
        Instant expiresAt
) {
    @Override
    public String toString() {
        return "VerifyEmailVerificationResponse[verificationToken=REDACTED, expiresAt="
                + expiresAt + "]";
    }
}