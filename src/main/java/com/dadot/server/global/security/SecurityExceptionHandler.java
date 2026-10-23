package com.dadot.server.global.security;

import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class SecurityExceptionHandler
        implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper jsonMapper;

    public SecurityExceptionHandler(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException ex
    ) throws IOException {

        if (ex instanceof AuthenticationServiceException) {
            writeError(
                    response,
                    ErrorCode.SERVICE_UNAVAILABLE
            );
            return;
        }

        response.setHeader(
                HttpHeaders.WWW_AUTHENTICATE,
                "Bearer"
        );

        writeError(
                response,
                ErrorCode.UNAUTHENTICATED
        );
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException ex
    ) throws IOException {

        writeError(
                response,
                ErrorCode.FORBIDDEN
        );
    }

    private void writeError(
            HttpServletResponse response,
            ErrorCode errorCode
    ) throws IOException {

        if (response.isCommitted()) {
            return;
        }

        response.setHeader(
                HttpHeaders.CACHE_CONTROL,
                "no-store"
        );

        response.setStatus(
                errorCode.getStatus().value()
        );

        response.setContentType(
                MediaType.APPLICATION_JSON_VALUE
        );

        response.setCharacterEncoding(
                StandardCharsets.UTF_8.name()
        );

        jsonMapper.writeValue(
                response.getOutputStream(),
                ApiResponse.failure(
                        errorCode.getCode(),
                        errorCode.getMessage()
                )
        );
    }
}