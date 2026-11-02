package com.dadot.server.domain.auth.controller;

import com.dadot.server.domain.auth.dto.request.AppleLoginRequest;
import com.dadot.server.domain.auth.dto.response.SocialLoginResponse;
import com.dadot.server.domain.auth.service.AppleLoginChallengeService.Challenge;
import com.dadot.server.domain.auth.service.AppleLoginService;
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
@SecurityRequirements
public class AppleLoginController {

    private final AppleLoginService service;

    @Operation(
            summary = "애플 로그인 준비",
            description = "nonce 발급 · 5분 유효 · 로그인마다 새로 발급"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "인증 준비 완료"
    )
    @PostMapping("/v1/auth/social/apple/challenges")
    public ResponseEntity<ApiResponse<Challenge>> challenge(
            HttpServletRequest request
    ) {
        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(
                        ApiResponse.success(
                                service.challenge(
                                        request.getRemoteAddr()
                                )
                        )
                );
    }

    @Operation(
            summary = "애플 로그인",
            description = "AUTH-05 · 기존 회원 로그인 또는 신규 가입 증빙 발급"
    )
    @PostMapping("/v1/auth/social/apple")
    public ResponseEntity<ApiResponse<SocialLoginResponse>> login(
            @RequestBody
            @Valid
            AppleLoginRequest body,

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