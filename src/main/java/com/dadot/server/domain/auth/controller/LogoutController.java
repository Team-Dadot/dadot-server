package com.dadot.server.domain.auth.controller;

import com.dadot.server.domain.auth.service.LogoutService;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.SessionAuthenticationToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
public class LogoutController {

    private final LogoutService service;

    @Operation(
            operationId = "AUTH-07",
            summary = "로그아웃"
    )
    @ApiResponse(
            responseCode = "204",
            description = "현재 세션 로그아웃 완료",
            content = @Content
    )
    @PostMapping("/v1/auth/logout")
    public ResponseEntity<Void> logout(
            Authentication authentication,
            HttpServletRequest request
    ) throws IOException {
        if (!(authentication instanceof SessionAuthenticationToken session)
                || !session.isAuthenticated()) {
            throw new BusinessException(
                    ErrorCode.UNAUTHENTICATED
            );
        }

        // 이 API는 JSON을 포함한 요청 본문을 받지 않는다.
        if (request.getInputStream().read() != -1) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT
            );
        }

        service.logout(
                session.getPrincipal().userId(),
                session.getSessionId(),
                session.getAuthVersion()
        );

        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}