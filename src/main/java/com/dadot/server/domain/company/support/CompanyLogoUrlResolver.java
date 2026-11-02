package com.dadot.server.domain.company.support;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;

@Component
public class CompanyLogoUrlResolver {

    private final URI baseUri;

    public CompanyLogoUrlResolver(
            @Value("${app.company-logo-base-url:}") String baseUrl
    ) {
        if (baseUrl.isBlank()) {
            this.baseUri = null;
            return;
        }

        URI uri = URI.create(baseUrl.strip());

        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalStateException(
                    "Company logo base URL must be an absolute HTTPS URL"
            );
        }

        this.baseUri = uri;
    }

    public String resolve(String key) {
        if (key == null) {
            return null;
        }

        if (baseUri == null) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }

        String[] segments = key.split("/", -1);

        for (String segment : segments) {
            if (segment.isBlank()
                    || segment.equals(".")
                    || segment.equals("..")
                    || segment.indexOf('\\') >= 0) {
                throw new IllegalStateException("Invalid company logo object key");
            }
        }

        return UriComponentsBuilder.fromUri(baseUri)
                .pathSegment(segments)
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUriString();
    }
}