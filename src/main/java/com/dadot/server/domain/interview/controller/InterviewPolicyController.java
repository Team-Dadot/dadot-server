package com.dadot.server.domain.interview.controller;

import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.interview.model.InterviewPolicy.*;
import com.dadot.server.domain.interview.service.InterviewPolicyService;
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
@RequestMapping("/v1/admin/interview-policies")
public class InterviewPolicyController {

    private final InterviewPolicyService service;

    @Operation(operationId = "adminSearchInterviewPolicies", summary = "면접 생성·평가 정책 버전 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<Page>> search(
            Authentication authentication,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query,
            @RequestBody(required = false) byte[] body) {
        if (body != null && body.length != 0) throw InterviewPolicy.invalid();
        if (query.entrySet().stream().anyMatch(entry ->
                !Set.of("page", "size", "companyId", "jobId", "type").contains(entry.getKey())
                        || entry.getValue().size() != 1)) throw InterviewPolicy.invalid();
        try {
            var search = new Search(query.containsKey("page") ? Integer.parseInt(digits(query.getFirst("page"))) : 0,
                    query.containsKey("size") ? Integer.parseInt(digits(query.getFirst("size"))) : 20,
                    optionalId(query.getFirst("companyId")), optionalId(query.getFirst("jobId")),
                    query.containsKey("type")
                            ? InterviewPolicy.enumValue(Type.class, query.getFirst("type")) : null);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(ApiResponse.success(service.search(actor(authentication), search)));
        } catch (NumberFormatException ex) {
            throw InterviewPolicy.invalid();
        }
    }

    @Operation(operationId = "adminCreateInterviewPolicy", summary = "면접 생성·평가 정책 버전 등록",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = Create.class))))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<View>> create(
            Authentication authentication, @RequestHeader("Idempotency-Key") String key,
            @RequestBody JsonNode body,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw InterviewPolicy.invalid();
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.create(actor(authentication), key, body)));
    }

    @Operation(operationId = "adminUpdateInterviewPolicy", summary = "면접 생성·평가 정책 검수·게시·중단",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(schema = @Schema(implementation = Patch.class))))
    @PatchMapping(value = "/{policyId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<View>> update(
            Authentication authentication, @PathVariable long policyId, @RequestBody JsonNode body,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw InterviewPolicy.invalid();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.update(actor(authentication), policyId, Patch.parse(body))));
    }

    private static Long optionalId(String value) { return value == null ? null : Long.valueOf(digits(value)); }

    private static String digits(String value) {
        if (value == null || !value.matches("[0-9]{1,16}")) throw InterviewPolicy.invalid();
        return value;
    }

    private static SessionAuthenticationToken actor(Authentication authentication) {
        if (authentication instanceof SessionAuthenticationToken token && token.isAuthenticated()) return token;
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
}