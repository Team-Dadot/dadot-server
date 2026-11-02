package com.dadot.server.global.security;

public record UserPrincipal(long userId) {

    public UserPrincipal {
        if (userId <= 0) {
            throw new IllegalArgumentException("Invalid user ID");
        }
    }
}