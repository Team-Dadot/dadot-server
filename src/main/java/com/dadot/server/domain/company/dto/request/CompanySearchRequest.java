package com.dadot.server.domain.company.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record CompanySearchRequest(
        @Min(0) Integer page,
        @Min(1) @Max(100) Integer size,
        @Size(max = 100) String query
) {

    public CompanySearchRequest {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
        query = query == null ? "" : query.strip();
    }
}