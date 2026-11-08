package com.dadot.server.domain.auth.controller;

import com.dadot.server.domain.auth.dto.request.RefreshTokenRequest;
import com.dadot.server.domain.auth.dto.response.RefreshTokenResponse;
import com.dadot.server.domain.auth.service.RefreshTokenService;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequiredArgsConstructor
public class RefreshTokenController {
    private final RefreshTokenService service;

    @Operation(operationId="AUTH-12", summary="로그인 토큰 자동 갱신 — 같은 본문·요청 키는 5분간 재시도 가능", requestBody =
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required=true, content=
                    @io.swagger.v3.oas.annotations.media.Content(schema=
                            @io.swagger.v3.oas.annotations.media.Schema(implementation=RefreshTokenRequest.class))))
    @SecurityRequirements
    @PostMapping(value="/v1/auth/refresh", consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<RefreshTokenResponse>> refresh(
            @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body,
            HttpServletRequest request) {
        if (request.getQueryString()!=null) throw new BusinessException(ErrorCode.INVALID_INPUT);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.refresh(RefreshTokenRequest.parse(body),key,request.getRemoteAddr())));
    }
}
