package com.dadot.server.domain.interview.controller;

import com.dadot.server.domain.interview.model.InterviewGeneration.Attempt;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.interview.model.InterviewReview.*;
import com.dadot.server.domain.interview.service.InterviewReviewService;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.response.ApiResponse;
import com.dadot.server.global.security.SessionAuthenticationToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Set;

@RestController
@RequiredArgsConstructor
public class InterviewReviewController {
    private final InterviewReviewService service;

    @Operation(operationId = "getInterviewReviews", summary = "기업별 면접 복습 질문 목록")
    @GetMapping("/v1/reviews/interviews")
    public ResponseEntity<ApiResponse<Page<Question>>> questions(Authentication auth,
                                                                 @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        validate(query, request, Set.of("companyId", "page", "size"));
        long company = InterviewPolicy.id(number(query.getFirst("companyId")));
        return ok(service.questions(actor(auth), company, paging(query)));
    }

    @Operation(operationId = "getInterviewAttempts", summary = "질문별 남은 답변 시도 목록")
    @GetMapping("/v1/interview-questions/{questionId}/attempts")
    public ResponseEntity<ApiResponse<Page<Attempt>>> attempts(Authentication auth, @PathVariable long questionId,
                                                               @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        validate(query, request, Set.of("page", "size"));
        return ok(service.attempts(actor(auth), questionId, paging(query)));
    }

    @Operation(operationId = "deleteInterviewQuestion", summary = "면접 질문과 연결된 답변·평가 삭제")
    @DeleteMapping("/v1/interview-questions/{questionId}")
    public ResponseEntity<Void> deleteQuestion(Authentication auth, @PathVariable long questionId,
                                               @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        validate(query, request, Set.of());
        service.deleteQuestion(actor(auth), questionId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @Operation(operationId = "deleteInterviewAttempt", summary = "개별 면접 답변과 평가 삭제")
    @DeleteMapping("/v1/interview-attempts/{attemptId}")
    public ResponseEntity<Void> deleteAttempt(Authentication auth, @PathVariable long attemptId,
                                              @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        validate(query, request, Set.of());
        service.deleteAttempt(actor(auth), attemptId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static Paging paging(MultiValueMap<String, String> query) {
        long page = query.containsKey("page") ? number(query.getFirst("page")) : 0;
        long size = query.containsKey("size") ? number(query.getFirst("size")) : 20;
        if (page > Integer.MAX_VALUE || size > 100) throw InterviewPolicy.invalid();
        return new Paging((int) page, (int) size);
    }

    private static long number(String value) {
        if (value == null || !value.matches("[0-9]{1,19}")) throw InterviewPolicy.invalid();
        try { return Long.parseLong(value); }
        catch (NumberFormatException ex) { throw InterviewPolicy.invalid(); }
    }

    private static void validate(MultiValueMap<String, String> query, HttpServletRequest request, Set<String> allowed) {
        if (query.entrySet().stream().anyMatch(e -> !allowed.contains(e.getKey()) || e.getValue().size() != 1)) {
            throw InterviewPolicy.invalid();
        }
        try { if (request.getInputStream().read() != -1) throw InterviewPolicy.invalid(); }
        catch (IOException ex) { throw InterviewPolicy.invalid(); }
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));
    }

    private static SessionAuthenticationToken actor(Authentication auth) {
        if (auth instanceof SessionAuthenticationToken token && token.isAuthenticated()) return token;
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
}