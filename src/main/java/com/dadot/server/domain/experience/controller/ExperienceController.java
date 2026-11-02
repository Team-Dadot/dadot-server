package com.dadot.server.domain.experience.controller;

import com.dadot.server.domain.experience.model.Experience;
import com.dadot.server.domain.experience.model.Experience.*;
import com.dadot.server.domain.experience.service.ExperienceService;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.response.ApiResponse;
import com.dadot.server.global.response.PageResponse;
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
@RequestMapping("/v1/users/me/experiences")
public class ExperienceController {

    private final ExperienceService service;

    @Operation(operationId = "listExperiences", summary = "경험 목록 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<Summary>>> list(
            Authentication authentication,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query,
            @RequestBody(required = false) byte[] body) {
        validate(query, body, Set.of("page", "size"));
        return response(200, service.list(actor(authentication), new Paging(integer(page, 0), integer(size, 20))));
    }

    @Operation(operationId = "getExperience", summary = "경험 상세 조회")
    @GetMapping("/{experienceId}")
    public ResponseEntity<ApiResponse<Detail>> detail(
            Authentication authentication, @PathVariable long experienceId,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query,
            @RequestBody(required = false) byte[] body) {
        validate(query, body, Set.of());
        return response(200, service.detail(actor(authentication), experienceId));
    }

    @Operation(operationId = "createExperience", summary = "경험 등록")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = Create.class)))
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Detail>> create(
            Authentication authentication, @RequestHeader("Idempotency-Key") String key,
            @RequestBody JsonNode body,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        validate(query, null, Set.of());
        return response(201, service.create(actor(authentication), key, Create.parse(body)));
    }

    @Operation(operationId = "updateExperience", summary = "경험 수정")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
            content = @Content(schema = @Schema(implementation = Patch.class)))
    @PatchMapping(value = "/{experienceId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Detail>> update(
            Authentication authentication, @PathVariable long experienceId,
            @RequestBody JsonNode body,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        validate(query, null, Set.of());
        return response(200, service.update(actor(authentication), experienceId, Patch.parse(body)));
    }

    @Operation(operationId = "deleteExperience", summary = "경험 삭제")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204",
            description = "경험 삭제 완료", content = @Content)
    @DeleteMapping("/{experienceId}")
    public ResponseEntity<Void> delete(
            Authentication authentication, @PathVariable long experienceId,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query,
            @RequestBody(required = false) byte[] body) {
        validate(query, body, Set.of());
        service.delete(actor(authentication), experienceId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static int integer(String value, int fallback) {
        if (value == null) return fallback;
        if (!value.matches("[0-9]{1,10}")) throw Experience.invalid();
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw Experience.invalid();
        }
    }

    private static void validate(MultiValueMap<String, String> query, byte[] body, Set<String> allowed) {
        if ((body != null && body.length != 0) || query.entrySet().stream().anyMatch(e ->
                !allowed.contains(e.getKey()) || e.getValue().size() != 1)) throw Experience.invalid();
    }

    private static SessionAuthenticationToken actor(Authentication authentication) {
        if (authentication instanceof SessionAuthenticationToken token && token.isAuthenticated()) return token;
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }

    private static <T> ResponseEntity<ApiResponse<T>> response(int status, T body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.success(body));
    }
}