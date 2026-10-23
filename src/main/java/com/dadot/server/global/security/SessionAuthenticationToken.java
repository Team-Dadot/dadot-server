package com.dadot.server.global.security;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

public final class SessionAuthenticationToken
        extends UsernamePasswordAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final String sessionId;
    private final int authVersion;

    public SessionAuthenticationToken(
            UserPrincipal principal,
            String sessionId,
            int authVersion,
            Collection<? extends GrantedAuthority> authorities
    ) {
        super(Objects.requireNonNull(principal), null, authorities);

        String canonical = UUID.fromString(sessionId).toString();

        if (!canonical.equals(sessionId) || authVersion < 0) {
            throw new IllegalArgumentException(
                    "Invalid session authentication"
            );
        }

        this.sessionId = canonical;
        this.authVersion = authVersion;
    }

    @Override
    public UserPrincipal getPrincipal() {
        return (UserPrincipal) super.getPrincipal();
    }

    @Override
    public String getName() {
        return Long.toString(getPrincipal().userId());
    }

    public String getSessionId() {
        return sessionId;
    }

    public int getAuthVersion() {
        return authVersion;
    }
}