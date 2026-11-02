package com.dadot.server.domain.company.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CompanyResponse(
        long companyId,
        String companyName,
        @Schema(nullable = true) String industry,
        @Schema(nullable = true) String logoUrl
) {
}