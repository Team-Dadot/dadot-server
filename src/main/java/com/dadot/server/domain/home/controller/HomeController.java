package com.dadot.server.domain.home.controller;

import com.dadot.server.domain.company.model.PreparationCompany;
import com.dadot.server.domain.home.model.Home.Dashboard;
import com.dadot.server.domain.home.model.Home.Preferences;
import com.dadot.server.domain.home.service.HomeService;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.response.ApiResponse;
import com.dadot.server.global.security.SessionAuthenticationToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.Set;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/users/me")
public class HomeController {

    private final HomeService service;

    @Operation(operationId = "getHomeDashboard", summary = "홈 대시보드 조회")
    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<Dashboard>> dashboard(
            Authentication authentication,
            @Parameter(description = "미리 볼 활성 준비 기업 ID. 생략하면 저장된 선택 기업")
            @RequestParam(required = false) String companyId,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query,
            @RequestBody(required = false) byte[] body
    ) {
        validate(query, body, Set.of("companyId"));
        return response(service.dashboard(actor(authentication), id(companyId)));
    }

    @Operation(operationId = "updateHomePreferences", summary = "홈 선택 기업 저장")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(schema = @Schema(implementation = Preferences.class))
    )
    @PatchMapping(value = "/preferences", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Preferences>> preferences(
            Authentication authentication,
            @RequestBody JsonNode body,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query
    ) {
        validate(query, null, Set.of());
        return response(service.select(actor(authentication), Preferences.parse(body)));
    }

    private static Long id(String value) {
        if (value == null) return null;
        if (!value.matches("[0-9]{1,16}")) throw PreparationCompany.invalid();
        try {
            return PreparationCompany.id(Long.parseLong(value));
        } catch (NumberFormatException ex) {
            throw PreparationCompany.invalid();
        }
    }

    private static void validate(MultiValueMap<String, String> query,
                                 byte[] body, Set<String> allowed) {
        if ((body != null && body.length != 0)
                || query.entrySet().stream().anyMatch(e ->
                !allowed.contains(e.getKey()) || e.getValue().size() != 1)) {
            throw PreparationCompany.invalid();
        }
    }

    private static SessionAuthenticationToken actor(Authentication authentication) {
        if (authentication instanceof SessionAuthenticationToken token
                && token.isAuthenticated()) return token;
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }

    private static <T> ResponseEntity<ApiResponse<T>> response(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(data));
    }
}