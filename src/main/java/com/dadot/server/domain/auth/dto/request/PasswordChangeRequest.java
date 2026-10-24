package com.dadot.server.domain.auth.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PasswordChangeRequest(
        @NotBlank
        @Pattern(regexp = "\\A[A-Za-z0-9_-]{43}\\z")
        @Schema(
                description = "PASSWORD_CHANGE 이메일 인증 완료 후 발급받은 verificationToken"
        )
        String verificationToken,

        @NotBlank
        @Size(min = 8, max = 64)
        @Pattern(
                regexp = "\\A(?=.*[A-Za-z])(?=.*[0-9])[^\\s\\p{Z}\\p{C}]+\\z"
        )
        @Schema(
                description = "8~64자, 영문·숫자 포함, 공백 금지",
                example = "ExampleNew123!"
        )
        String newPassword
) {

    @JsonAnySetter
    public void rejectUnknownField(
            String name,
            Object value
    ) {
        throw new IllegalArgumentException(
                "Unknown password change field"
        );
    }

    @Override
    public String toString() {
        return "PasswordChangeRequest[REDACTED]";
    }
}