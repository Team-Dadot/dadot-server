package com.dadot.server.domain.job.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record JobSearchRequest(
        @Min(0) Integer page,
        @Min(1) @Max(100) Integer size,
        String query,
        @Min(1) @Max(9007199254740991L) Long categoryId,
        Boolean popular
) {

    public JobSearchRequest {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
        query = query == null ? "" : query.strip();
        popular = Boolean.TRUE.equals(popular);
    }

    @AssertTrue(message = "직무 검색어는 50자 이하여야 합니다.")
    @Schema(hidden = true)
    public boolean isQueryLengthValid() {
        return query.codePointCount(0, query.length()) <= 50;
    }
}