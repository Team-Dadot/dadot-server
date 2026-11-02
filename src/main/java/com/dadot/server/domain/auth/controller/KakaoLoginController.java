package com.dadot.server.domain.auth.controller;

import com.dadot.server.domain.auth.dto.request.KakaoLoginRequest;
import com.dadot.server.domain.auth.dto.response.SocialLoginResponse;
import com.dadot.server.domain.auth.service.KakaoLoginService;
import com.dadot.server.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class KakaoLoginController {

    private final KakaoLoginService service;

    @Operation(
            summary = "카카오 로그인",
            description = "AUTH-05 · 기존 회원 로그인 또는 신규 가입 증빙 발급"
    )
    @SecurityRequirements
    @PostMapping("/v1/auth/social/kakao")
    public ResponseEntity<ApiResponse<SocialLoginResponse>> login(
            @RequestBody
            @Valid
            KakaoLoginRequest body,

            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = KakaoLoginService.UUID_PATTERN)
            String idempotencyKey,

            HttpServletRequest request
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        ApiResponse.success(
                                service.login(
                                        body,
                                        idempotencyKey,
                                        request.getRemoteAddr()
                                )
                        )
                );
    }
}