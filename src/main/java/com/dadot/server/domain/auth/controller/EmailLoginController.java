package com.dadot.server.domain.auth.controller;

import com.dadot.server.domain.auth.dto.request.EmailLoginRequest;
import com.dadot.server.domain.auth.dto.response.EmailLoginResponse;
import com.dadot.server.domain.auth.service.EmailLoginService;
import com.dadot.server.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class EmailLoginController {

    private final EmailLoginService service;

    @Operation(operationId = "AUTH-04", summary = "이메일 로그인")
    @SecurityRequirements
    @PostMapping("/v1/auth/login")
    public ResponseEntity<ApiResponse<EmailLoginResponse>> login(
            @Valid @RequestBody EmailLoginRequest request,
            HttpServletRequest servletRequest
    ) {
        var response = service.login(request, servletRequest.getRemoteAddr());

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(response));
    }
}