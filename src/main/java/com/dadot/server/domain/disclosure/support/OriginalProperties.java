package com.dadot.server.domain.disclosure.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record OriginalProperties(boolean enabled, String root) {

    public OriginalProperties(
            @Value("${app.dart.originals.enabled:false}") boolean enabled,
            @Value("${app.dart.originals.root:}") String root
    ) {
        this.enabled = enabled;
        this.root = root;

        if (enabled && root.isBlank()) {
            throw new IllegalStateException(
                    "DART original storage root is required"
            );
        }
    }
}