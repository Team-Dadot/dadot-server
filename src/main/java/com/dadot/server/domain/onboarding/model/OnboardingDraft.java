package com.dadot.server.domain.onboarding.model;

import java.time.LocalDate;
import java.util.List;

public record OnboardingDraft(
        List<Long> companyIds,
        List<Assignment> assignments,
        List<Long> experienceIds,
        List<OnboardingStep> completedSteps
) {
    public OnboardingDraft {
        companyIds = List.copyOf(companyIds);
        assignments = List.copyOf(assignments);
        experienceIds = List.copyOf(experienceIds);
        completedSteps = List.copyOf(completedSteps);
    }

    public static OnboardingDraft initial() {
        return new OnboardingDraft(List.of(), List.of(), List.of(), List.of());
    }

    public OnboardingStep currentStep() {
        for (var step : OnboardingStep.values()) {
            if (!completedSteps.contains(step)) {
                return step;
            }
        }
        return OnboardingStep.COMPLETE;
    }

    public Assignment assignment(long companyId) {
        return assignments.stream()
                .filter(value -> value.companyId() == companyId)
                .findFirst().orElseThrow();
    }

    public record Assignment(long companyId, Job job, LocalDate interviewDate) {
    }

    public record Job(Long jobId, String jobName, boolean isCustom) {
    }
}