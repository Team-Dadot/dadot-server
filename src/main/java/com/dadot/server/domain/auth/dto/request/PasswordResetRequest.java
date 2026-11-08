package com.dadot.server.domain.auth.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.dadot.server.domain.auth.support.PasswordPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PasswordResetRequest(
        @NotBlank
        @Pattern(regexp = "\\A[A-Za-z0-9_-]{43}\\z")
        @Schema(
                description = "PASSWORD_RESET 이메일 인증 완료 후 발급받은 verificationToken"
        )
        String verificationToken,

        @NotBlank
        @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH)
        @Pattern(regexp = PasswordPolicy.PATTERN)
        @Schema(
                description = PasswordPolicy.DESCRIPTION,
                example = "ExampleNew123!",
                format = "password"
        )
        String newPassword
) {

    @JsonAnySetter
    public void rejectUnknownField(
            String name,
            Object value
    ) {
        throw new IllegalArgumentException(
                "Unknown password reset field"
        );
    }

    @Override
    public String toString() {
        return "PasswordResetRequest[REDACTED]";
    }
}