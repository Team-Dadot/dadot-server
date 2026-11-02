package com.dadot.server.domain.appconfig.controller;

import com.dadot.server.domain.appconfig.dto.AppConfigResponse;
import com.dadot.server.domain.appconfig.service.SignupTermsService;
import com.dadot.server.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AppConfigController {

    private final SignupTermsService terms;

    @Operation(
            summary = "앱 설정 및 가입 약관 조회",
            description = "CFG-01"
    )
    @SecurityRequirements
    @GetMapping("/v1/app-config")
    public ResponseEntity<ApiResponse<AppConfigResponse>> config() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(terms.config()));
    }
}