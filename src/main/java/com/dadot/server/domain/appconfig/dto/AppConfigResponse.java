package com.dadot.server.domain.appconfig.dto;

import java.time.Instant;
import java.util.List;

public record AppConfigResponse(
        String privacyPolicyUrl,
        String termsUrl,
        String inquiryFormUrl,
        boolean signupAvailable,
        List<SignupTerm> signupTerms
) {
    public record SignupTerm(
            long termsId,
            String code,
            String version,
            String title,
            String url,
            boolean required,
            Instant effectiveAt
    ) {
    }
}