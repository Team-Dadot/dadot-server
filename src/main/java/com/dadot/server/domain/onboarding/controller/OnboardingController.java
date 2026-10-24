package com.dadot.server.domain.onboarding.controller;

import com.dadot.server.domain.onboarding.dto.request.OnboardingPatchRequest;
import com.dadot.server.domain.onboarding.dto.response.OnboardingCompleteResponse;
import com.dadot.server.domain.onboarding.dto.response.OnboardingResponse;
import com.dadot.server.domain.onboarding.service.OnboardingService;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.response.ApiResponse;
import com.dadot.server.global.security.SessionAuthenticationToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/users/me/onboarding")
public class OnboardingController {

    private final OnboardingService service;

    @Operation(operationId = "ONB-01", summary = "온보딩 저장 상태 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<OnboardingResponse>> get(
            Authentication authentication,
            @RequestBody(required = false) byte[] body
    ) {
        requireNoBody(body);
        var actor = actor(authentication);

        return ok(service.get(
                actor.getPrincipal().userId(),
                actor.getSessionId(),
                actor.getAuthVersion()
        ));
    }

    @Operation(
            operationId = "ONB-02",
            summary = "온보딩 단계 저장",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            schema = @Schema(
                                    implementation = OnboardingPatchRequest.class
                            )
                    )
            )
    )
    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<OnboardingResponse>> save(
            Authentication authentication,
            @RequestBody JsonNode body
    ) {
        var request = OnboardingPatchRequest.parse(body);
        var actor = actor(authentication);

        return ok(service.save(
                actor.getPrincipal().userId(),
                actor.getSessionId(),
                actor.getAuthVersion(),
                request
        ));
    }

    @Operation(operationId = "ONB-03", summary = "온보딩 완료")
    @PostMapping("/complete")
    public ResponseEntity<ApiResponse<OnboardingCompleteResponse>> complete(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required = false) byte[] body
    ) {
        requireNoBody(body);
        var actor = actor(authentication);

        return ok(service.complete(
                actor.getPrincipal().userId(),
                actor.getSessionId(),
                actor.getAuthVersion(),
                key
        ));
    }

    private SessionAuthenticationToken actor(Authentication authentication) {
        if (authentication instanceof SessionAuthenticationToken session
                && session.isAuthenticated()) {
            return session;
        }

        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }

    private void requireNoBody(byte[] body) {
        if (body != null && body.length != 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    private <T> ResponseEntity<ApiResponse<T>> ok(T value) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(value));
    }
}