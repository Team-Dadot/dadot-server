package com.dadot.server.domain.auth.controller;

import com.dadot.server.domain.auth.dto.request.VerifyEmailVerificationRequest;
import com.dadot.server.domain.auth.dto.response.VerifyEmailVerificationResponse;
import com.dadot.server.domain.auth.service.EmailVerificationConfirmationService;
import com.dadot.server.global.response.ApiResponse;
import com.dadot.server.global.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/auth/email-verifications")
@RequiredArgsConstructor
public class EmailVerificationConfirmationController {

    private static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    private final EmailVerificationConfirmationService service;

    @PostMapping("/{verificationId}/verify")
    @SecurityRequirements
    @Operation(
            operationId = "AUTH-02",
            summary = "이메일 인증번호 확인"
    )
    public ResponseEntity<ApiResponse<VerifyEmailVerificationResponse>> verify(
            @PathVariable
            @Pattern(regexp = UUID_PATTERN)
            String verificationId,

            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = UUID_PATTERN)
            String requestKey,

            @Valid
            @RequestBody
            VerifyEmailVerificationRequest request,

            Authentication authentication
    ) {
        Long actor = authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal()
                instanceof UserPrincipal principal
                ? principal.userId()
                : null;

        var response = service.verify(
                UUID.fromString(verificationId).toString(),
                request.code(),
                UUID.fromString(requestKey).toString(),
                actor
        );

        return ResponseEntity
                .ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(response));
    }
}