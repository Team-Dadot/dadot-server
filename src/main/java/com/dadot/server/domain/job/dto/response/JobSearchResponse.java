package com.dadot.server.domain.job.dto.response;

import org.springframework.data.domain.Page;

import java.util.List;

public record JobSearchResponse(
        List<JobItem> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext,
        List<CategoryItem> categories
) {

    public static JobSearchResponse from(
            Page<JobItem> page,
            List<CategoryItem> categories
    ) {
        // page + 1 대신 long offset을 사용해 int 오버플로를 피한다.
        boolean hasNext = page.getPageable().getOffset()
                + page.getSize() < page.getTotalElements();

        return new JobSearchResponse(
                page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(),
                hasNext, List.copyOf(categories)
        );
    }

    public record JobItem(
            long jobId,
            String jobName,
            long categoryId,
            String categoryName,
            boolean popular
    ) {
    }

    public record CategoryItem(long categoryId, String categoryName) {
    }
}