package com.dadot.server.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyEmailVerificationRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9]{6}$")
        String code
) {
    @Override
    public String toString() {
        return "VerifyEmailVerificationRequest[code=REDACTED]";
    }
}