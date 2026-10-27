package com.dadot.server.domain.company.controller;

import com.dadot.server.domain.company.model.PreparationCompany;
import com.dadot.server.domain.company.model.PreparationCompany.*;
import com.dadot.server.domain.company.service.PreparationCompanyService;
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
@RequestMapping("/v1/users/me/companies")
public class PreparationCompanyController {

    private final PreparationCompanyService service;

    @Operation(
            operationId = "getPreparationCompanies",
            summary = "내 준비 기업 목록 조회"
    )
    @GetMapping
    public ResponseEntity<ApiResponse<Page>> list(
            Authentication authentication,
            @Parameter(hidden = true)
            @RequestParam MultiValueMap<String, String> query,
            @RequestBody(required = false) byte[] body
    ) {
        validate(query, body, Set.of("page", "size"));

        var paging = new Paging(
                integer(query.getFirst("page"), 0),
                integer(query.getFirst("size"), 20));

        return response(200, service.list(actor(authentication), paging));
    }

    @Operation(
            operationId = "addPreparationCompany",
            summary = "준비 기업 추가·재추가"
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    schema = @Schema(implementation = Add.class)
            )
    )
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Item>> add(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody JsonNode body,
            @Parameter(hidden = true)
            @RequestParam MultiValueMap<String, String> query
    ) {
        validate(query, null, Set.of());

        return response(201,
                service.add(actor(authentication), key, Add.parse(body)));
    }

    @Operation(
            operationId = "updatePreparationCompany",
            summary = "기업별 직무·면접일 수정 및 전체 직무 적용"
    )
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(
                    schema = @Schema(implementation = Patch.class)
            )
    )
    @PatchMapping(
            value = "/{companyId}",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<Changed>> update(
            Authentication authentication,
            @PathVariable long companyId,
            @RequestBody JsonNode body,
            @Parameter(hidden = true)
            @RequestParam MultiValueMap<String, String> query
    ) {
        validate(query, null, Set.of());

        return response(200,
                service.update(actor(authentication),
                        companyId, Patch.parse(body)));
    }

    @Operation(
            operationId = "excludePreparationCompany",
            summary = "준비 기업 제외"
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "204",
            description = "준비 기업 제외 완료",
            content = @Content
    )
    @DeleteMapping("/{companyId}")
    public ResponseEntity<Void> exclude(
            Authentication authentication,
            @PathVariable long companyId,
            @Parameter(hidden = true)
            @RequestParam MultiValueMap<String, String> query,
            @RequestBody(required = false) byte[] body
    ) {
        validate(query, body, Set.of());
        service.exclude(actor(authentication), companyId);

        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    private static void validate(
            MultiValueMap<String, String> query,
            byte[] body,
            Set<String> allowed
    ) {
        if ((body != null && body.length != 0)
                || query.entrySet().stream().anyMatch(e ->
                !allowed.contains(e.getKey())
                        || e.getValue().size() != 1)) {
            throw PreparationCompany.invalid();
        }
    }

    private static int integer(String value, int fallback) {
        if (value == null) return fallback;

        if (!value.matches("[0-9]{1,10}")) {
            throw PreparationCompany.invalid();
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw PreparationCompany.invalid();
        }
    }

    private static SessionAuthenticationToken actor(
            Authentication authentication
    ) {
        if (authentication instanceof SessionAuthenticationToken token
                && token.isAuthenticated()) {
            return token;
        }

        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }

    private static <T> ResponseEntity<ApiResponse<T>> response(
            int status,
            T data
    ) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(data));
    }
}