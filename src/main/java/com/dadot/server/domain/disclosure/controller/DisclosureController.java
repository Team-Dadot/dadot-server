package com.dadot.server.domain.disclosure.controller;

import com.dadot.server.domain.disclosure.model.DisclosureSync;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Accepted;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Request;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Result;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Search;
import com.dadot.server.domain.disclosure.service.DisclosureService;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.response.ApiResponse;
import com.dadot.server.global.security.SessionAuthenticationToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.Set;

@RestController
@RequiredArgsConstructor
public class DisclosureController {
    private final DisclosureService service;

    @Operation(
            operationId = "ADM-01",
            summary = "공시 목록 수집 접수",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(schema = @Schema(implementation = Request.class))
            )
    )
    @PostMapping(
            value = "/v1/admin/companies/{companyId}/disclosure-sync-tasks",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<Accepted>> start(
            Authentication authentication,
            @PathVariable long companyId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody JsonNode body,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query
    ) {
        if (!query.isEmpty()) throw DisclosureSync.invalid();

        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(
                        service.start(
                                actor(authentication), companyId, key, Request.parse(body)
                        )
                ));
    }

    @Operation(operationId = "ADM-03", summary = "공시 자료 목록 및 수집 작업 상태 조회")
    @Parameters({
            @Parameter(name = "page", in = ParameterIn.QUERY, description = "페이지 번호, 기본 0"),
            @Parameter(name = "size", in = ParameterIn.QUERY, description = "페이지 크기, 기본 20 / 최대 100"),
            @Parameter(name = "companyId", in = ParameterIn.QUERY, description = "기업 ID"),
            @Parameter(name = "status", in = ParameterIn.QUERY, description = "공시 내용 버전의 검수 상태"),
            @Parameter(name = "syncTaskId", in = ParameterIn.QUERY, description = "수집 작업 UUID")
    })
    @GetMapping("/v1/admin/disclosures")
    public ResponseEntity<ApiResponse<Result>> search(
            Authentication authentication,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query,
            @RequestBody(required = false) byte[] body
    ) {
        if (body != null && body.length != 0) throw DisclosureSync.invalid();

        Set<String> allowed = Set.of("page", "size", "companyId", "status", "syncTaskId");
        if (query.entrySet().stream().anyMatch(
                e -> !allowed.contains(e.getKey()) || e.getValue().size() != 1))
            throw DisclosureSync.invalid();

        try {
            var request = new Search(
                    Integer.parseInt(query.getFirst("page") == null
                            ? "0" : digits(query.getFirst("page"))),
                    Integer.parseInt(query.getFirst("size") == null
                            ? "20" : digits(query.getFirst("size"))),
                    query.getFirst("companyId") == null
                            ? null : Long.valueOf(digits(query.getFirst("companyId"))),
                    query.getFirst("status"),
                    query.getFirst("syncTaskId")
            );

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(ApiResponse.success(service.search(actor(authentication), request)));

        } catch (NumberFormatException ex) {
            throw DisclosureSync.invalid();
        }
    }

    private String digits(String value) {
        if (!value.matches("[0-9]{1,16}")) throw DisclosureSync.invalid();
        return value;
    }

    private SessionAuthenticationToken actor(Authentication authentication) {
        if (authentication instanceof SessionAuthenticationToken session
                && session.isAuthenticated()) return session;

        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
}