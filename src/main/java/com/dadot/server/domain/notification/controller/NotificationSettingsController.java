package com.dadot.server.domain.notification.controller;

import com.dadot.server.domain.notification.model.NotificationSettings;
import com.dadot.server.domain.notification.model.NotificationSettings.Patch;
import com.dadot.server.domain.notification.model.NotificationSettings.Settings;
import com.dadot.server.domain.notification.service.NotificationSettingsService;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.response.ApiResponse;
import com.dadot.server.global.security.SessionAuthenticationToken;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/users/me/notification-settings")
public class NotificationSettingsController {
    private static final int MAX_BODY_BYTES = 4096;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    private final NotificationSettingsService service;

    @Operation(summary = "학습 알림 설정 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<Settings>> get(Authentication auth, HttpServletRequest request) {
        noQuery(request);
        try {
            if (request.getInputStream().read() != -1) throw NotificationSettings.invalid();
        } catch (IOException ex) {
            throw NotificationSettings.invalid();
        }
        return ok(service.get(actor(auth)));
    }

    @Operation(summary = "학습 알림 설정 수정 — 실제 알림 예약은 앱에서 처리")
    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Settings>> patch(Authentication auth, HttpServletRequest request) {
        noQuery(request);
        try {
            byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE);
            }
            return ok(service.patch(actor(auth), Patch.parse(JSON.readTree(bytes))));
        } catch (IOException | JacksonException ex) {
            throw NotificationSettings.invalid();
        }
    }

    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw NotificationSettings.invalid();
    }

    private static SessionAuthenticationToken actor(Authentication auth) {
        if (auth instanceof SessionAuthenticationToken token && token.isAuthenticated()) return token;
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }

    private static ResponseEntity<ApiResponse<Settings>> ok(Settings settings) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(settings));
    }
}