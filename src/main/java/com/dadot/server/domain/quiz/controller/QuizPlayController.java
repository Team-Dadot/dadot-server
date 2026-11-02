package com.dadot.server.domain.quiz.controller;

import com.dadot.server.domain.quiz.model.QuizBank;
import com.dadot.server.domain.quiz.model.QuizPlay.*;
import com.dadot.server.domain.quiz.service.QuizPlayService;
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
public class QuizPlayController {

    private final QuizPlayService service;

    @Operation(operationId = "prepareTodayQuizSet", summary = "오늘 퀴즈 세트 준비·재개")
    @PostMapping("/v1/companies/{companyId}/quiz-sets/today")
    public ResponseEntity<ApiResponse<Summary>> prepare(
            Authentication authentication, @PathVariable long companyId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required = false) byte[] body,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        empty(query, body);
        var result = service.prepare(actor(authentication), companyId, key);
        return ResponseEntity.status(result.httpStatus()).cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(result.body()));
    }

    @Operation(operationId = "getQuizSet", summary = "퀴즈 세트·현재 문항 조회")
    @GetMapping("/v1/quiz-sets/{quizSetId}")
    public ResponseEntity<ApiResponse<Detail>> detail(
            Authentication authentication, @PathVariable long quizSetId,
            @RequestBody(required = false) byte[] body,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        empty(query, body);
        return ok(service.detail(actor(authentication), quizSetId));
    }

    @Operation(operationId = "submitQuizAnswer", summary = "현재 퀴즈 문항 답안 제출",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true, content = @io.swagger.v3.oas.annotations.media.Content(
                    schema = @Schema(implementation = Answer.class))))
    @PostMapping(value = "/v1/quiz-sets/{quizSetId}/answers", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Submitted>> answer(
            Authentication authentication, @PathVariable long quizSetId,
            @RequestHeader("Idempotency-Key") String key, @RequestBody JsonNode body,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        empty(query, null);
        return ok(service.answer(actor(authentication), quizSetId, key, Answer.parse(body)));
    }

    @Operation(operationId = "getQuizSetResult", summary = "완료한 퀴즈 세트 결과 조회")
    @GetMapping("/v1/quiz-sets/{quizSetId}/result")
    public ResponseEntity<ApiResponse<Result>> result(
            Authentication authentication, @PathVariable long quizSetId,
            @RequestBody(required = false) byte[] body,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query) {
        empty(query, body);
        return ok(service.result(actor(authentication), quizSetId));
    }

    private static void empty(MultiValueMap<String, String> query, byte[] body) {
        if (!query.isEmpty() || (body != null && body.length != 0)) {
            throw QuizBank.invalid();
        }
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(body));
    }

    private static SessionAuthenticationToken actor(Authentication authentication) {
        if (authentication instanceof SessionAuthenticationToken token && token.isAuthenticated()) {
            return token;
        }
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
}