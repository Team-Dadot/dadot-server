package com.dadot.server.domain.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record KakaoLoginRequest(
        @NotBlank
        @Size(max = 2048)
        @Pattern(regexp = "\\A[A-Za-z0-9._~+/=-]+\\z")
        @Schema(
                description = "카카오 SDK가 발급한 accessToken",
                example = "EXAMPLE_KAKAO_TOKEN"
        )
        String accessToken,

        @Null
        @Schema(hidden = true)
        String identityToken,

        @Null
        @Schema(hidden = true)
        String authorizationCode
) {

    public KakaoLoginRequest(String accessToken) {
        this(accessToken, null, null);
    }

    @Override
    public String toString() {
        return "KakaoLoginRequest[credentials=REDACTED]";
    }
}