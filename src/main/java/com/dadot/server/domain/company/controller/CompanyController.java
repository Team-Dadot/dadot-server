package com.dadot.server.domain.company.controller;

import com.dadot.server.domain.company.dto.request.CompanySearchRequest;
import com.dadot.server.domain.company.dto.response.CompanyResponse;
import com.dadot.server.domain.company.service.CompanyService;
import com.dadot.server.global.response.ApiResponse;
import com.dadot.server.global.response.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService service;

    @Operation(
            operationId = "COM-01",
            summary = "제공 기업 검색",
            description = "제공 기업만 기업명·ID 오름차순으로 조회합니다."
    )
    @GetMapping("/v1/companies")
    public ResponseEntity<ApiResponse<PageResponse<CompanyResponse>>> search(
            @ModelAttribute @Valid CompanySearchRequest request
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.search(request)));
    }
}