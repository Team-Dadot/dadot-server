package com.dadot.server.domain.auth.controller;

import com.dadot.server.domain.auth.dto.request.EmailSignupRequest;
import com.dadot.server.domain.auth.dto.response.EmailSignupResponse;
import com.dadot.server.domain.auth.service.EmailSignupService;
import com.dadot.server.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class EmailSignupController {

    private final EmailSignupService service;

    @Operation(
            summary = "이메일 회원가입",
            description = "AUTH-03"
    )
    @SecurityRequirements
    @PostMapping("/v1/auth/signup")
    public ResponseEntity<ApiResponse<EmailSignupResponse>> signup(
            @RequestHeader("Idempotency-Key")
            @Pattern(
                    regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
            )
            String requestKey,
            @Valid @RequestBody EmailSignupRequest request
    ) {
        var result = service.signup(
                request,
                UUID.fromString(requestKey).toString()
        );

        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(result));
    }
}