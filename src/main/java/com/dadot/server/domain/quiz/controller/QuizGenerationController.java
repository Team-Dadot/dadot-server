package com.dadot.server.domain.quiz.controller;

import com.dadot.server.domain.quiz.model.QuizBank;
import com.dadot.server.domain.quiz.model.QuizGeneration;
import com.dadot.server.domain.quiz.service.QuizGenerationService;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.response.ApiResponse;
import com.dadot.server.global.security.SessionAuthenticationToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequiredArgsConstructor
public class QuizGenerationController {
    private final QuizGenerationService service;

    @Operation(summary = "공시 기반 AI 퀴즈 사전 생성 접수", requestBody =
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content =
    @io.swagger.v3.oas.annotations.media.Content(schema = @Schema(implementation = QuizGeneration.Request.class))))
    @PostMapping(value = "/v1/admin/companies/{companyId}/quiz-generation-tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<QuizGeneration.Accepted>> start(
            Authentication authentication, @PathVariable long companyId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw QuizBank.invalid();
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(ApiResponse.success(
                service.start(actor(authentication), companyId, key, QuizGeneration.Request.parse(body))));
    }

    @Operation(summary = "AI 퀴즈 생성 작업 상태·결과 조회")
    @GetMapping("/v1/admin/quiz-generation-tasks/{taskId}")
    public ResponseEntity<ApiResponse<QuizGeneration.Result>> result(
            Authentication authentication, @PathVariable String taskId,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query,
            @RequestBody(required = false) byte[] body) {
        if (!query.isEmpty() || body != null && body.length != 0) throw QuizBank.invalid();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(
                service.result(actor(authentication), taskId)));
    }

    private static SessionAuthenticationToken actor(Authentication authentication) {
        if (authentication instanceof SessionAuthenticationToken token && token.isAuthenticated()) return token;
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
}