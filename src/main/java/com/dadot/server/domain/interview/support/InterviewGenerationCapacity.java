package com.dadot.server.domain.interview.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record InterviewGenerationCapacity(int maxConcurrent, int maxOutstanding) {
    public InterviewGenerationCapacity(
            @Value("${app.interview-generation.max-concurrent:3}") int maxConcurrent,
            @Value("${app.interview-generation.max-outstanding:20}") int maxOutstanding
    ) {
        if (maxConcurrent < 1 || maxConcurrent > 16
                || maxOutstanding < maxConcurrent || maxOutstanding > 1000) {
            throw new IllegalStateException("Invalid interview generation capacity configuration");
        }
        this.maxConcurrent = maxConcurrent;
        this.maxOutstanding = maxOutstanding;
    }
}
