package com.dadot.server.domain.auth.controller;

import com.dadot.server.domain.auth.dto.request.IssueEmailVerificationRequest;
import com.dadot.server.domain.auth.dto.response.IssueEmailVerificationResponse;
import com.dadot.server.domain.auth.service.EmailVerificationService;
import com.dadot.server.global.response.ApiResponse;
import com.dadot.server.global.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/v1/auth/email-verifications")
@RequiredArgsConstructor
public class EmailVerificationController {

    private final EmailVerificationService service;

    @PostMapping
    @SecurityRequirements
    @Operation(
            operationId = "AUTH-01",
            summary = "이메일 인증번호 발송"
    )
    public ResponseEntity<ApiResponse<IssueEmailVerificationResponse>> issue(
            @Valid
            @RequestBody
            IssueEmailVerificationRequest request,

            @RequestHeader("Idempotency-Key")
            @Pattern(
                    regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
            )
            String requestKey,

            HttpServletRequest servletRequest,
            Authentication authentication
    ) {
        Long userId = authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal()
                instanceof UserPrincipal principal
                ? principal.userId()
                : null;

        var response = service.issue(
                request,
                UUID.fromString(requestKey).toString(),
                servletRequest.getRemoteAddr(),
                userId
        );

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(response));
    }
}