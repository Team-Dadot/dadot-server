package com.dadot.server.domain.auth.controller;

import com.dadot.server.domain.auth.dto.request.PasswordChangeRequest;
import com.dadot.server.domain.auth.service.PasswordChangeService;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.SessionAuthenticationToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PasswordChangeController {

    private final PasswordChangeService service;

    @Operation(operationId = "AUTH-09", summary = "로그인 후 비밀번호 변경",
            description = "PASSWORD_CHANGE 증빙을 사용하며 현재 세션은 유지하고 다른 세션은 종료합니다.")
    @ApiResponse(responseCode = "204", description = "비밀번호 변경 완료",
            content = @Content)
    @PatchMapping(value = "/v1/users/me/password",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> change(
            @RequestBody @Valid PasswordChangeRequest body,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = PasswordChangeService.UUID_PATTERN) String idempotencyKey,
            Authentication authentication,
            HttpServletRequest request) {
        if (!(authentication instanceof SessionAuthenticationToken session)
                || !session.isAuthenticated()) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }

        service.change(body, idempotencyKey, session.getPrincipal().userId(),
                session.getSessionId(), session.getAuthVersion(), request.getRemoteAddr());

        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}