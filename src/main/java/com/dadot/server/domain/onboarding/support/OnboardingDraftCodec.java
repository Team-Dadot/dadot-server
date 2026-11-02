package com.dadot.server.domain.onboarding.support;

import com.dadot.server.domain.onboarding.model.OnboardingDraft;
import com.dadot.server.domain.onboarding.model.OnboardingStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;

@Component
@RequiredArgsConstructor
public class OnboardingDraftCodec {

    private final JsonMapper mapper;

    public String encode(OnboardingDraft draft) {
        validate(draft);
        return mapper.writeValueAsString(draft);
    }

    public OnboardingDraft decode(String json) {
        if (json == null) {
            return OnboardingDraft.initial();
        }

        try {
            var draft = mapper.readValue(json, OnboardingDraft.class);
            validate(draft);
            return draft;
        } catch (JacksonException | IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid stored onboarding draft");
        }
    }

    private void validate(OnboardingDraft draft) {
        if (draft == null || draft.companyIds().size() > 5
                || draft.experienceIds().size() > 100
                || new HashSet<>(draft.companyIds()).size() != draft.companyIds().size()
                || new HashSet<>(draft.experienceIds()).size() != draft.experienceIds().size()
                || new HashSet<>(draft.completedSteps()).size() != draft.completedSteps().size()
                || !draft.companyIds().equals(draft.assignments().stream()
                .map(OnboardingDraft.Assignment::companyId).toList())) {
            throw new IllegalStateException("Invalid stored onboarding draft");
        }

        for (var step : draft.completedSteps()) {
            for (int i = 0; i < step.ordinal(); i++) {
                if (!draft.completedSteps().contains(OnboardingStep.values()[i])) {
                    throw new IllegalStateException("Invalid stored onboarding steps");
                }
            }
        }

        if (draft.completedSteps().contains(OnboardingStep.COMPANIES)
                && draft.companyIds().isEmpty()) {
            throw new IllegalStateException("Missing stored onboarding companies");
        }

        for (var assignment : draft.assignments()) {
            var job = assignment.job();

            if ((draft.completedSteps().contains(OnboardingStep.JOBS) && job == null)
                    || (job != null && (job.jobName() == null || job.jobName().isBlank()
                    || (job.isCustom() != (job.jobId() == null))))) {
                throw new IllegalStateException("Invalid stored onboarding job");
            }
        }
    }
}