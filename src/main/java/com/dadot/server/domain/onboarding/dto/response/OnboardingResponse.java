package com.dadot.server.domain.onboarding.dto.response;

import com.dadot.server.domain.onboarding.model.OnboardingDraft.Assignment;
import com.dadot.server.domain.onboarding.model.OnboardingStep;

import java.util.List;

public record OnboardingResponse(
        long version,
        OnboardingStep currentStep,
        List<OnboardingStep> completedSteps,
        List<Long> companyIds,
        List<Assignment> assignments,
        long experienceCount,
        NotificationSettings notificationSettings,
        boolean completed
) {
    public record NotificationSettings(
            boolean enabled,
            String scheduledTime,
            String timeZone,
            String deliveryPolicyStatus,
            CurrentDevice currentDevice,
            long version
    ) {
    }

    public record CurrentDevice(
            String installationId,
            String platform,
            String permission,
            boolean registered
    ) {
    }
}