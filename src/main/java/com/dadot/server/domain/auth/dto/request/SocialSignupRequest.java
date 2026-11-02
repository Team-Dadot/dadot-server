package com.dadot.server.domain.auth.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record SocialSignupRequest(
        @NotBlank
        @Pattern(regexp = "\\A[A-Za-z0-9_-]{43}\\z")
        String registrationToken,

        @NotNull
        @Size(max = 20)
        List<@NotNull @Positive Long> acceptedTermsIds
) {

    public SocialSignupRequest {
        if (acceptedTermsIds != null) {
            acceptedTermsIds = Collections.unmodifiableList(
                    new ArrayList<>(acceptedTermsIds)
            );
        }
    }

    // 제공자·사용자 식별자·이메일은 서버의 가입 증빙에서만 읽는다.
    @JsonAnySetter
    public void rejectUnknownField(
            String name,
            Object value
    ) {
        throw new IllegalArgumentException(
                "Unknown social signup field"
        );
    }

    @Override
    public String toString() {
        return "SocialSignupRequest[registrationToken=REDACTED]";
    }
}