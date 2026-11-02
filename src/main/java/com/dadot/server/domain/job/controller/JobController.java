package com.dadot.server.domain.job.controller;

import com.dadot.server.domain.job.dto.request.JobSearchRequest;
import com.dadot.server.domain.job.dto.response.JobSearchResponse;
import com.dadot.server.domain.job.service.JobService;
import com.dadot.server.global.response.ApiResponse;
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
public class JobController {

    private final JobService service;

    @Operation(operationId = "COM-02", summary = "직무 검색·분류 조회")
    @GetMapping("/v1/jobs")
    public ResponseEntity<ApiResponse<JobSearchResponse>> search(
            @ModelAttribute @Valid JobSearchRequest request
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(service.search(request)));
    }
}