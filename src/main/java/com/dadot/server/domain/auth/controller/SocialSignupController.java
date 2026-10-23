package com.dadot.server.domain.auth.controller;

import com.dadot.server.domain.auth.dto.request.SocialSignupRequest;
import com.dadot.server.domain.auth.dto.response.SocialSignupResponse;
import com.dadot.server.domain.auth.service.SocialSignupService;
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
public class SocialSignupController {

    private final SocialSignupService service;

    @Operation(
            summary = "소셜 회원가입 완료",
            description = "AUTH-11 · 가입 증빙과 필수 약관 동의로 가입 완료"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "201",
            description = "회원가입 완료"
    )
    @SecurityRequirements
    @PostMapping("/v1/auth/social/signup")
    public ResponseEntity<ApiResponse<SocialSignupResponse>> signup(
            @Valid
            @RequestBody
            SocialSignupRequest body,

            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = SocialSignupService.UUID_PATTERN)
            String idempotencyKey,

            HttpServletRequest request
    ) {
        var response = service.signup(
                body,
                idempotencyKey,
                request.getRemoteAddr()
        );

        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(
                        ApiResponse.success(response)
                );
    }
}