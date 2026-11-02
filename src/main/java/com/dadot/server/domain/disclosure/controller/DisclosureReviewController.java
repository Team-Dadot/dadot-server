package com.dadot.server.domain.disclosure.controller;

import com.dadot.server.domain.disclosure.model.DisclosureReview.Request;
import com.dadot.server.domain.disclosure.model.DisclosureReview.Result;
import com.dadot.server.domain.disclosure.model.DisclosureReview.Status;
import com.dadot.server.domain.disclosure.model.DisclosureSync;
import com.dadot.server.domain.disclosure.service.DisclosureReviewService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequiredArgsConstructor
public class DisclosureReviewController {

    private final DisclosureReviewService service;

    @Operation(
            operationId = "adminReviewDisclosure",
            summary = "공시 검수·게시·정정 및 제공 중단",
            requestBody =
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            schema = @Schema(
                                    implementation = ReviewBody.class
                            )
                    )
            )
    )
    @PatchMapping(
            value = "/v1/admin/disclosures/{disclosureId}",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<Result>> review(
            Authentication authentication,
            @PathVariable long disclosureId,
            @RequestBody JsonNode body,
            @Parameter(hidden = true)
            @RequestParam MultiValueMap<String, String> query
    ) {
        if (!query.isEmpty()) {
            throw DisclosureSync.invalid();
        }

        if (!(authentication instanceof SessionAuthenticationToken actor)
                || !actor.isAuthenticated()) {
            throw new BusinessException(
                    ErrorCode.UNAUTHENTICATED
            );
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(
                        service.review(
                                actor,
                                disclosureId,
                                Request.parse(body)
                        )
                ));
    }

    public record ReviewBody(
            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    minimum = "1"
            )
            long expectedVersion,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    minimum = "1"
            )
            long disclosureVersionId,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED
            )
            Status status,

            @Schema(
                    nullable = true,
                    minimum = "1"
            )
            Long correctionOf,

            @Schema(
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    minLength = 1,
                    maxLength = 1000
            )
            String reason
    ) {}
}