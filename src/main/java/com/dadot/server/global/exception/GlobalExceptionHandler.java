package com.dadot.server.global.exception;

import com.dadot.server.global.response.ApiResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(
            BusinessException ex
    ) {
        return toResponse(ex.getErrorCode());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpectedException(
            Exception ex
    ) {
        logServerError(ex);

        return toResponse(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex,
            @Nullable Object body,
            HttpHeaders headers,
            HttpStatusCode statusCode,
            WebRequest request
    ) {
        ErrorCode errorCode = resolveErrorCode(statusCode);

        if (statusCode.is5xxServerError()) {
            logServerError(ex);
        }

        ApiResponse<Void> response = ApiResponse.failure(
                errorCode.getCode(),
                errorCode.getMessage()
        );

        return super.handleExceptionInternal(
                ex,
                response,
                headers,
                statusCode,
                request
        );
    }

    private ResponseEntity<ApiResponse<Void>> toResponse(
            ErrorCode errorCode
    ) {
        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiResponse.failure(
                        errorCode.getCode(),
                        errorCode.getMessage()
                ));
    }

    private ErrorCode resolveErrorCode(HttpStatusCode statusCode) {
        return switch (statusCode.value()) {
            case 400, 422 -> ErrorCode.INVALID_INPUT;
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 429 -> ErrorCode.RATE_LIMIT_EXCEEDED;
            case 503 -> ErrorCode.SERVICE_UNAVAILABLE;
            default -> statusCode.is4xxClientError()
                    ? ErrorCode.INVALID_INPUT
                    : ErrorCode.INTERNAL_SERVER_ERROR;
        };
    }

    private void logServerError(Exception ex) {
        log.error(
                "Unhandled server error. exceptionType={}",
                ex.getClass().getName()
        );
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        ErrorCode errorCode = ErrorCode.IMAGE_TOO_LARGE;

        ApiResponse<Void> response = ApiResponse.failure(
                errorCode.getCode(),
                errorCode.getMessage()
        );

        return super.handleExceptionInternal(
                ex,
                response,
                headers,
                status,
                request
        );
    }
}