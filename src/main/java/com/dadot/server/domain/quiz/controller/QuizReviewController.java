package com.dadot.server.domain.quiz.controller;

import com.dadot.server.domain.quiz.model.QuizBank;
import com.dadot.server.domain.quiz.model.QuizPlay;
import com.dadot.server.domain.quiz.model.QuizReview.*;
import com.dadot.server.domain.quiz.service.QuizReviewService;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.response.ApiResponse;
import com.dadot.server.global.security.SessionAuthenticationToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequiredArgsConstructor
public class QuizReviewController {

    private final QuizReviewService service;

    @Operation(operationId = "getReviewCompanies", summary = "복습 대상 기업 조회")
    @GetMapping("/v1/reviews/companies")
    public ResponseEntity<ApiResponse<CompanyPage>> companies(
            Authentication authentication,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query,
            @RequestBody(required = false) byte[] body) {
        validate(query, body, Set.of("page", "size"));
        return ok(service.companies(actor(authentication), paging(query)));
    }

    @Operation(operationId = "getQuizReviews", summary = "기업별 퀴즈 복습 목록")
    @GetMapping("/v1/reviews/quizzes")
    public ResponseEntity<ApiResponse<QuestionPage>> questions(
            Authentication authentication,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query,
            @RequestBody(required = false) byte[] body) {
        validate(query, body, Set.of("page", "size", "companyId", "filter"));
        long company = QuizBank.id(number(query.getFirst("companyId")));
        Filter filter;
        try {
            filter = query.containsKey("filter") ? Filter.valueOf(query.getFirst("filter")) : Filter.ALL;
        } catch (IllegalArgumentException ex) {
            throw QuizBank.invalid();
        }
        return ok(service.questions(actor(authentication), new Search(company, filter, paging(query))));
    }

    @Operation(operationId = "getQuizReview", summary = "최신 제출 퀴즈 복습 상세·근거 조회")
    @GetMapping("/v1/reviews/quizzes/{questionId}")
    public ResponseEntity<ApiResponse<QuizPlay.ResultItem>> detail(
            Authentication authentication, @PathVariable long questionId,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> query,
            @RequestBody(required = false) byte[] body) {
        validate(query, body, Set.of());
        return ok(service.detail(actor(authentication), questionId));
    }

    private static Paging paging(MultiValueMap<String, String> query) {
        return new Paging(integer(query.getFirst("page"), 0), integer(query.getFirst("size"), 20));
    }

    private static int integer(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        long parsed = number(value);
        if (parsed > Integer.MAX_VALUE) {
            throw QuizBank.invalid();
        }
        return (int) parsed;
    }

    private static long number(String value) {
        if (value == null || !value.matches("[0-9]{1,16}")) {
            throw QuizBank.invalid();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw QuizBank.invalid();
        }
    }

    private static void validate(MultiValueMap<String, String> query, byte[] body, Set<String> allowed) {
        if ((body != null && body.length > 0) || query.entrySet().stream()
                .anyMatch(entry -> !allowed.contains(entry.getKey()) || entry.getValue().size() != 1)) {
            throw QuizBank.invalid();
        }
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(data));
    }

    private static SessionAuthenticationToken actor(Authentication authentication) {
        if (authentication instanceof SessionAuthenticationToken token && token.isAuthenticated()) {
            return token;
        }
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
}