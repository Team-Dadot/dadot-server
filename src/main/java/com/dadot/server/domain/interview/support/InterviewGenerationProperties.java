package com.dadot.server.domain.interview.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record InterviewGenerationProperties(
        boolean enabled,
        boolean workerEnabled,
        String model,
        String apiKey,
        int maxSourceCharacters,
        int maxExperienceCharacters
) {
    public InterviewGenerationProperties(
            @Value("${app.interview-generation.enabled:false}") boolean enabled,
            @Value("${app.interview-generation.worker-enabled:false}") boolean workerEnabled,
            @Value("${app.interview-generation.model:gpt-6-luna}") String model,
            @Value("${OPENAI_API_KEY:${app.openai.api-key:}}") String apiKey,
            @Value("${app.interview-generation.max-source-characters:3000000}") int maxSourceCharacters,
            @Value("${app.interview-generation.max-experience-characters:40000}") int maxExperienceCharacters
    ) {
        this.enabled = enabled;
        this.workerEnabled = workerEnabled;
        this.model = model;
        this.apiKey = apiKey;
        this.maxSourceCharacters = maxSourceCharacters;
        this.maxExperienceCharacters = maxExperienceCharacters;

        if (!model.matches("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,99}")
                || maxSourceCharacters < 1000 || maxSourceCharacters > 12_000_000
                || maxExperienceCharacters < 5000 || maxExperienceCharacters > 100_000
                || (enabled && (apiKey == null || !apiKey.matches("[!-~]{20,512}")))) {
            throw new IllegalStateException("Invalid interview generation configuration");
        }
    }

    @Override
    public String toString() {
        return "InterviewGenerationProperties[enabled=" + enabled
                + ", workerEnabled=" + workerEnabled
                + ", model=" + model + "]";
    }
}