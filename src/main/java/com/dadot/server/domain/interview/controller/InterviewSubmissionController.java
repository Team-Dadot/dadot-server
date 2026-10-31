package com.dadot.server.domain.interview.controller;

import com.dadot.server.domain.interview.model.InterviewGeneration.Attempt;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.interview.model.InterviewSubmission.*;
import com.dadot.server.domain.interview.service.InterviewSubmissionService;
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
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequiredArgsConstructor
public class InterviewSubmissionController {
    private final InterviewSubmissionService service;

    @Operation(operationId = "saveInterviewDraft", summary = "면접 답변 초안 저장·비우기")
    @PutMapping(value = "/v1/interview-questions/{questionId}/draft",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Saved>> save(
            Authentication auth, @PathVariable long questionId,
            @RequestParam MultiValueMap<String, String> query, @RequestBody JsonNode body) {
        noQuery(query);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.save(actor(auth), questionId, SaveDraft.parse(body))));
    }

    @Operation(operationId = "submitInterviewAnswer", summary = "면접 답변 제출·평가 작업 접수")
    @PostMapping(value = "/v1/interview-questions/{questionId}/attempts",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<Accepted>> submit(
            Authentication auth, @PathVariable long questionId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestParam MultiValueMap<String, String> query, @RequestBody JsonNode body) {
        noQuery(query);
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.submit(actor(auth), questionId, key, Submit.parse(body))));
    }

    @Operation(operationId = "getInterviewAttempt", summary = "제출한 면접 답변 상세 조회")
    @GetMapping("/v1/interview-attempts/{attemptId}")
    public ResponseEntity<ApiResponse<Attempt>> detail(
            Authentication auth, @PathVariable long attemptId,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        noQuery(query);
        try {
            if (request.getInputStream().read() != -1) throw InterviewPolicy.invalid();
        } catch (java.io.IOException ex) {
            throw InterviewPolicy.invalid();
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.detail(actor(auth), attemptId)));
    }

    private static void noQuery(MultiValueMap<String, String> query) {
        if (!query.isEmpty()) throw InterviewPolicy.invalid();
    }

    private static SessionAuthenticationToken actor(Authentication auth) {
        if (auth instanceof SessionAuthenticationToken token && token.isAuthenticated()) return token;
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
}