package com.dadot.server.domain.interview.controller;

import com.dadot.server.domain.interview.model.InterviewEvaluation.View;
import com.dadot.server.domain.interview.model.InterviewGeneration.Accepted;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.interview.service.InterviewEvaluationService;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.response.ApiResponse;
import com.dadot.server.global.security.SessionAuthenticationToken;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class InterviewEvaluationController {
    private final InterviewEvaluationService service;

    @Operation(operationId = "getInterviewEvaluation", summary = "면접 답변 평가 상태·결과 조회")
    @GetMapping("/v1/interview-attempts/{attemptId}/evaluation")
    public ResponseEntity<ApiResponse<View>> view(Authentication auth, @PathVariable long attemptId,
                                                  @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        empty(query, request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.view(actor(auth), attemptId)));
    }

    @Operation(operationId = "retryInterviewEvaluation", summary = "실패한 면접 평가 재접수")
    @PostMapping("/v1/interview-attempts/{attemptId}/evaluation-tasks")
    public ResponseEntity<ApiResponse<Accepted>> retry(Authentication auth, @PathVariable long attemptId,
                                                       @RequestHeader("Idempotency-Key") String key, @RequestParam MultiValueMap<String, String> query,
                                                       HttpServletRequest request) {
        empty(query, request);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.retry(actor(auth), attemptId, key)));
    }

    private static void empty(MultiValueMap<String, String> query, HttpServletRequest request) {
        if (!query.isEmpty()) throw InterviewPolicy.invalid();
        try { if (request.getInputStream().read() != -1) throw InterviewPolicy.invalid(); }
        catch (java.io.IOException ex) { throw InterviewPolicy.invalid(); }
    }

    private static SessionAuthenticationToken actor(Authentication auth) {
        if (auth instanceof SessionAuthenticationToken token && token.isAuthenticated()) return token;
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
}