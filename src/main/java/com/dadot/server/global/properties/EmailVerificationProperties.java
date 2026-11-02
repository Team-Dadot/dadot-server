package com.dadot.server.global.properties;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.email-verification")
public record EmailVerificationProperties(

        @NotBlank
        String hmacSecretBase64,

        @NotBlank
        @Email
        String fromAddress,

        @Min(1)
        @Max(1000)
        int ipPerMinute,

        @Min(1)
        @Max(100000)
        int ipPerHour
) {

    @Override
    public String toString() {
        return "EmailVerificationProperties[redacted]";
    }
}