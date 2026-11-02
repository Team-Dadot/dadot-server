package com.dadot.server.domain.quiz.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record QuizGenerationProperties(boolean enabled, boolean workerEnabled, String model,
                                       String apiKey, int maxOutputTokens, int maxSourceCharacters,
                                       int maxBatches) {
    public QuizGenerationProperties(
            @Value("${app.quiz-generation.enabled:false}") boolean enabled,
            @Value("${app.quiz-generation.worker-enabled:false}") boolean workerEnabled,
            @Value("${app.quiz-generation.model:gpt-6-luna}") String model,
            @Value("${OPENAI_API_KEY:${app.openai.api-key:}}") String apiKey,
            @Value("${app.quiz-generation.max-output-tokens:4096}") int maxOutputTokens,
            @Value("${app.quiz-generation.max-source-characters:3000000}") int maxSourceCharacters,
            @Value("${app.quiz-generation.max-batches:500}") int maxBatches) {
        this.enabled = enabled;
        this.workerEnabled = workerEnabled;
        this.model = model;
        this.apiKey = apiKey;
        this.maxOutputTokens = maxOutputTokens;
        this.maxSourceCharacters = maxSourceCharacters;
        this.maxBatches = maxBatches;
        if (!model.matches("[a-zA-Z0-9][a-zA-Z0-9._:-]{0,99}")
                || maxOutputTokens < 1024 || maxOutputTokens > 8192
                || maxSourceCharacters < 1000 || maxSourceCharacters > 12000000
                || maxBatches < 1 || maxBatches > 1000) {
            throw new IllegalStateException("Invalid quiz generation configuration");
        }
        if (enabled && !apiKey.matches("[!-~]{20,512}")) {
            throw new IllegalStateException("Quiz generation API key is required");
        }
    }

    @Override
    public String toString() {
        return "QuizGenerationProperties[enabled=" + enabled + ", model=" + model + "]";
    }
}