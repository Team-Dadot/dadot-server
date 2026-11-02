package com.dadot.server.domain.interview.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record InterviewEvaluationProperties(boolean enabled, boolean workerEnabled, String model,
                                            String apiKey, int maxExperienceCharacters) {
    public InterviewEvaluationProperties(
            @Value("${app.interview-evaluation.enabled:false}") boolean enabled,
            @Value("${app.interview-evaluation.worker-enabled:false}") boolean workerEnabled,
            @Value("${app.interview-evaluation.model:gpt-6-luna}") String model,
            @Value("${OPENAI_API_KEY:${app.openai.api-key:}}") String apiKey,
            @Value("${app.interview-evaluation.max-experience-characters:40000}") int maxExperienceCharacters) {
        this.enabled = enabled;
        this.workerEnabled = workerEnabled;
        this.model = model;
        this.apiKey = apiKey;
        this.maxExperienceCharacters = maxExperienceCharacters;
        if (model == null || !model.matches("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,99}")
                || maxExperienceCharacters < 5000 || maxExperienceCharacters > 100_000
                || (enabled && (apiKey == null || !apiKey.matches("[!-~]{20,512}")))) {
            throw new IllegalStateException("Invalid interview evaluation configuration");
        }
    }

    @Override public String toString() {
        return "InterviewEvaluationProperties[enabled=" + enabled + ", workerEnabled=" + workerEnabled
                + ", model=" + model + "]";
    }
}