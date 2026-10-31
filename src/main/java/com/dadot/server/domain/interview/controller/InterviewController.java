package com.dadot.server.domain.interview.controller;

import com.dadot.server.domain.interview.model.InterviewGeneration;
import com.dadot.server.domain.interview.model.InterviewGeneration.*;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.interview.service.InterviewGenerationService;
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

import java.util.Set;

@RestController
@RequiredArgsConstructor
public class InterviewController {
    private final InterviewGenerationService service;

    @Operation(
            operationId = "getInterviewEntry",
            summary = "면접 진입·이어 쓰기 상태 조회"
    )
    @GetMapping("/v1/companies/{companyId}/interview-entry")
    public ResponseEntity<ApiResponse<Entry>> entry(
            Authentication auth,
            @PathVariable long companyId,
            @RequestParam MultiValueMap<String, String> query,
            HttpServletRequest request
    ) {
        emptyBody(request);

        if (query.entrySet().stream().anyMatch(
                e -> !Set.of("taskId").contains(e.getKey())
                        || e.getValue().size() != 1
        )) {
            throw InterviewPolicy.invalid();
        }

        String task = query.containsKey("taskId")
                ? InterviewGeneration.uuid(query.getFirst("taskId"))
                : null;

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(
                        service.entry(actor(auth), companyId, task)
                ));
    }

    @Operation(
            operationId = "createInterviewQuestionTask",
            summary = "AI 면접 질문 생성 접수"
    )
    @PostMapping("/v1/companies/{companyId}/interview-question-tasks")
    public ResponseEntity<ApiResponse<Accepted>> create(
            Authentication auth,
            @PathVariable long companyId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestParam MultiValueMap<String, String> query,
            HttpServletRequest request
    ) {
        emptyBody(request);

        if (!query.isEmpty()) throw InterviewPolicy.invalid();

        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(
                        service.create(actor(auth), companyId, key)
                ));
    }

    @Operation(
            operationId = "getInterviewQuestion",
            summary = "면접 질문 상세 조회"
    )
    @GetMapping("/v1/interview-questions/{questionId}")
    public ResponseEntity<ApiResponse<Detail>> detail(
            Authentication auth,
            @PathVariable long questionId,
            @RequestParam MultiValueMap<String, String> query,
            HttpServletRequest request
    ) {
        emptyBody(request);

        if (!query.isEmpty()) throw InterviewPolicy.invalid();

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(
                        service.detail(actor(auth), questionId)
                ));
    }

    private static void emptyBody(HttpServletRequest request) {
        // 거절할 본문 전체를 byte[]로 먼저 적재하지 않는다.
        try {
            if (request.getInputStream().read() != -1) {
                throw InterviewPolicy.invalid();
            }
        } catch (java.io.IOException ex) {
            throw InterviewPolicy.invalid();
        }
    }

    private static SessionAuthenticationToken actor(Authentication auth) {
        if (auth instanceof SessionAuthenticationToken token
                && token.isAuthenticated()) {
            return token;
        }

        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
}