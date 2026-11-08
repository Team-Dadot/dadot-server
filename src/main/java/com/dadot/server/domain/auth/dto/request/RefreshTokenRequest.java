package com.dadot.server.domain.auth.dto.request;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import tools.jackson.databind.JsonNode;

public record RefreshTokenRequest(String refreshToken) {
    public RefreshTokenRequest {
        if (refreshToken == null || !refreshToken.matches("[A-Za-z0-9_-]{43}")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    public static RefreshTokenRequest parse(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 1
                || !body.has("refreshToken") || !body.get("refreshToken").isTextual()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        return new RefreshTokenRequest(body.get("refreshToken").asText());
    }

    @Override public String toString() { return "RefreshTokenRequest[REDACTED]"; }
}
