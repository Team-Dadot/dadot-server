package com.dadot.server.global.response;

public record ErrorResponse(
        String code,
        String message
) {
}