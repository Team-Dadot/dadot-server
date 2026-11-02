package com.dadot.server.domain.quiz.controller;

import com.dadot.server.domain.quiz.model.QuizBank;
import com.dadot.server.domain.quiz.model.QuizBank.*;
import com.dadot.server.domain.quiz.service.QuizBankService;
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

import java.util.Set;

@RestController
@RequiredArgsConstructor
public class QuizBankController {

    private final QuizBankService service;

    @Operation(
            operationId = "adminCreateQuizQuestion",
            summary = "퀴즈 문항 초안 등록",
            requestBody =
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content =
                    @io.swagger.v3.oas.annotations.media.Content(
                            schema = @Schema(
                                    implementation = QuizBank.Content.class
                            )
                    )
            )
    )
    @PostMapping(
            value = "/v1/admin/quiz-questions",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<View>> create(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody JsonNode body,
            @Parameter(hidden = true)
            @RequestParam MultiValueMap<String, String> query
    ) {
        if (!query.isEmpty()) {
            throw QuizBank.invalid();
        }

        return ResponseEntity.status(201)
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(
                        service.create(
                                actor(authentication),
                                key,
                                QuizBank.Content.parse(body)
                        )
                ));
    }

    @Operation(
            operationId = "adminSearchQuizQuestions",
            summary = "퀴즈 문제은행 조회"
    )
    @GetMapping("/v1/admin/quiz-questions")
    public ResponseEntity<ApiResponse<Page>> search(
            Authentication authentication,
            @Parameter(hidden = true)
            @RequestParam MultiValueMap<String, String> query,
            @RequestBody(required = false) byte[] body
    ) {
        if (body != null && body.length != 0) {
            throw QuizBank.invalid();
        }

        if (query.entrySet().stream().anyMatch(
                e -> !Set.of(
                        "page",
                        "size",
                        "companyId",
                        "status"
                ).contains(e.getKey())
                        || e.getValue().size() != 1
        )) {
            throw QuizBank.invalid();
        }

        try {
            var search = new Search(
                    query.getFirst("page") == null
                            ? 0
                            : Integer.parseInt(
                            digits(query.getFirst("page"))
                    ),
                    query.getFirst("size") == null
                            ? 20
                            : Integer.parseInt(
                            digits(query.getFirst("size"))
                    ),
                    query.getFirst("companyId") == null
                            ? null
                            : Long.valueOf(
                            digits(query.getFirst("companyId"))
                    ),
                    query.getFirst("status") == null
                            ? null
                            : QuizBank.state(query.getFirst("status"))
            );

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(ApiResponse.success(
                            service.search(
                                    actor(authentication),
                                    search
                            )
                    ));

        } catch (NumberFormatException ex) {
            throw QuizBank.invalid();
        }
    }

    @Operation(
            operationId = "adminUpdateQuizQuestion",
            summary = "퀴즈 문항 수정·검수·게시·중단",
            requestBody =
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content =
                    @io.swagger.v3.oas.annotations.media.Content(
                            schema = @Schema(
                                    implementation = Patch.class
                            )
                    )
            )
    )
    @PatchMapping(
            value = "/v1/admin/quiz-questions/{questionId}",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<View>> update(
            Authentication authentication,
            @PathVariable long questionId,
            @RequestBody JsonNode body,
            @Parameter(hidden = true)
            @RequestParam MultiValueMap<String, String> query
    ) {
        if (!query.isEmpty()) {
            throw QuizBank.invalid();
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(
                        service.update(
                                actor(authentication),
                                questionId,
                                Patch.parse(body)
                        )
                ));
    }

    private static String digits(String value) {
        if (!value.matches("[0-9]{1,16}")) {
            throw QuizBank.invalid();
        }

        return value;
    }

    private static SessionAuthenticationToken actor(
            Authentication authentication
    ) {
        if (authentication instanceof SessionAuthenticationToken token
                && token.isAuthenticated()) {
            return token;
        }

        throw new BusinessException(
                ErrorCode.UNAUTHENTICATED
        );
    }
}