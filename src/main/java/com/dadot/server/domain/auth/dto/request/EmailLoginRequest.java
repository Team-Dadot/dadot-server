package com.dadot.server.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public record EmailLoginRequest(
        @NotBlank
        @Email
        @Size(max = 254)
        @Pattern(regexp = "^[^\\s\\p{Cntrl}]+$")
        @Schema(example = "hello@example.com")
        String email,

        @NotBlank
        @Size(min = 8, max = 64)
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*[0-9])[^\\s\\p{Z}\\p{C}]+$")
        @Schema(example = "Example123!", format = "password")
        String password
) {
    public EmailLoginRequest {
        if (email != null) {
            email = email.strip().toLowerCase(Locale.ROOT);
        }
    }

    @Override
    public String toString() {
        return "EmailLoginRequest[REDACTED]";
    }
}