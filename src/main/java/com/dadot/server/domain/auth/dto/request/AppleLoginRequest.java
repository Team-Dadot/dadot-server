package com.dadot.server.domain.auth.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AppleLoginRequest(
        @NotBlank
        @Size(max = 8192)
        @Pattern(
                regexp = "\\A[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\z"
        )
        @Schema(description = "Apple SDK에서 받은 identityToken")
        String identityToken,

        @NotBlank
        @Size(max = 2048)
        @Pattern(regexp = "\\A[A-Za-z0-9._~+/=-]+\\z")
        @Schema(description = "같은 로그인에서 받은 일회성 authorizationCode")
        String authorizationCode,

        @NotBlank
        @Pattern(regexp = "\\A[A-Za-z0-9_-]{43}\\z")
        @Schema(description = "인증 준비 API에서 받은 nonce 원문")
        String nonce
) {

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException(
                "Unsupported Apple login field"
        );
    }

    @Override
    public String toString() {
        return "AppleLoginRequest[credentials=REDACTED]";
    }
}