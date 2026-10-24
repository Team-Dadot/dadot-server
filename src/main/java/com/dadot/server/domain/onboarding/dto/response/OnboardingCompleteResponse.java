package com.dadot.server.domain.onboarding.dto.response;

public record OnboardingCompleteResponse(boolean completed, long selectedCompanyId) {
}