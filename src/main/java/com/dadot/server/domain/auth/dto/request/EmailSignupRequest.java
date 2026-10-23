package com.dadot.server.domain.auth.dto.request;

import jakarta.validation.constraints.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public record EmailSignupRequest(
        @NotBlank
        @Email
        @Size(max = 254)
        @Pattern(regexp = "^[^\\s\\p{Cntrl}]+$")
        String email,

        @NotBlank
        @Size(min = 8, max = 64)
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*[0-9])[^\\s\\p{Z}\\p{C}]+$"
        )
        String password,

        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_-]{43}$")
        String verificationToken,

        @NotNull
        @Size(max = 20)
        List<@NotNull @Positive Long> acceptedTermsIds
) {
    public EmailSignupRequest {
        if (email != null) {
            email = email.strip().toLowerCase(Locale.ROOT);
        }

        if (acceptedTermsIds != null) {
            acceptedTermsIds = Collections.unmodifiableList(
                    new ArrayList<>(acceptedTermsIds)
            );
        }
    }

    @Override
    public String toString() {
        return "EmailSignupRequest[REDACTED]";
    }
}