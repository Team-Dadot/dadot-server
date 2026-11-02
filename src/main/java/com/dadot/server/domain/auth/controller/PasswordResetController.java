package com.dadot.server.domain.auth.controller;

import com.dadot.server.domain.auth.dto.request.PasswordResetRequest;
import com.dadot.server.domain.auth.service.PasswordResetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PasswordResetController {

    private final PasswordResetService service;

    @Operation(
            operationId = "AUTH-08",
            summary = "비밀번호 재설정",
            description = "PASSWORD_RESET 이메일 인증 증빙으로 비밀번호를 바꾸고 모든 세션을 폐기합니다."
    )
    @ApiResponse(
            responseCode = "204",
            description = "비밀번호 재설정 완료",
            content = @Content
    )
    @SecurityRequirements
    @PostMapping(
            value = "/v1/auth/password/reset",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Void> reset(
            @RequestBody
            @Valid
            PasswordResetRequest body,

            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = PasswordResetService.UUID_PATTERN)
            String idempotencyKey,

            HttpServletRequest request
    ) {
        service.reset(
                body,
                idempotencyKey,
                request.getRemoteAddr()
        );

        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}