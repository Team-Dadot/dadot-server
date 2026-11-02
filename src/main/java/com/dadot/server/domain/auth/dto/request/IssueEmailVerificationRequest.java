package com.dadot.server.domain.auth.dto.request;

import com.dadot.server.domain.auth.entity.VerificationPurpose;
import jakarta.validation.constraints.*;

import java.util.Locale;

public record IssueEmailVerificationRequest(

        @NotBlank
        @Email
        @Size(max = 254)
        @Pattern(regexp = "^[^\\s\\p{Cntrl}]+$")
        String email,

        @NotNull
        VerificationPurpose purpose
) {
    public IssueEmailVerificationRequest {
        if (email != null) {
            email = email.strip().toLowerCase(Locale.ROOT);
        }
    }
}