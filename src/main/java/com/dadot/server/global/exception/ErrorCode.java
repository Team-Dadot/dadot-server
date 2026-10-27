package com.dadot.server.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    ONBOARDING_REQUIRED(HttpStatus.CONFLICT, "온보딩을 먼저 완료해 주세요."),

    COMPANY_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "준비 기업은 최대 5개입니다."),
    COMPANY_ALREADY_ACTIVE(HttpStatus.CONFLICT, "이미 준비 중인 기업입니다."),
    MIN_COMPANY_REQUIRED(HttpStatus.CONFLICT, "준비 기업은 최소 1개가 필요합니다."),

    QUIZ_BANK_INSUFFICIENT(HttpStatus.CONFLICT, "오늘 퀴즈를 준비 중입니다. 잠시 후 다시 시도해 주세요."),
    QUIZ_SET_NOT_COMPLETED(HttpStatus.CONFLICT, "모든 문항을 제출한 뒤 결과를 확인해 주세요."),
    QUIZ_SET_EXPIRED(HttpStatus.CONFLICT, "오늘의 새 퀴즈를 시작해 주세요."),
    ANSWER_ALREADY_SUBMITTED(HttpStatus.CONFLICT, "이미 제출한 답변입니다."),
    INVALID_ITEM_ORDER(HttpStatus.CONFLICT, "현재 문항부터 제출해 주세요."),

    CONTENT_UNAVAILABLE(
            HttpStatus.CONFLICT,
            "근거 자료가 변경되었거나 현재 제공할 수 없습니다."
    ),
    INVALID_STATUS_TRANSITION(
            HttpStatus.CONFLICT,
            "허용되지 않은 상태 변경입니다."
    ),
    ONBOARDING_INCOMPLETE(
            HttpStatus.CONFLICT,
            "기업과 직무 등 필수 단계를 확인해 주세요."
    ),
    VERSION_CONFLICT(
            HttpStatus.CONFLICT,
            "정보가 변경되었습니다. 다시 조회해 주세요."
    ),
    IDEMPOTENCY_KEY_EXPIRED(
            HttpStatus.CONFLICT,
            "요청 키가 만료되었습니다. 새 요청 키를 사용해 주세요."
    ),
    PASSWORD_AUTH_UNAVAILABLE(
            HttpStatus.CONFLICT,
            "이메일 로그인 계정에서만 변경할 수 있습니다."
    ),
    INVALID_REGISTRATION_TOKEN(
            HttpStatus.UNAUTHORIZED,
            "소셜 인증을 다시 진행해 주세요."
    ),
    SOCIAL_ACCOUNT_ALREADY_REGISTERED(
            HttpStatus.CONFLICT,
            "이미 가입된 계정입니다. 소셜 로그인을 다시 진행해 주세요."
    ),
    INVALID_SOCIAL_CREDENTIAL(
            HttpStatus.UNAUTHORIZED,
            "소셜 로그인을 다시 진행해 주세요."
    ),
    INVALID_CREDENTIALS(
            HttpStatus.UNAUTHORIZED,
            "이메일 또는 비밀번호를 확인해 주세요."
    ),
    INVALID_VERIFICATION_TOKEN(
            HttpStatus.UNAUTHORIZED,
            "이메일 인증을 다시 진행해 주세요."
    ),
    TERMS_NOT_ACCEPTED(
            HttpStatus.BAD_REQUEST,
            "필수 이용약관에 동의해 주세요."
    ),
    TERMS_CHANGED(
            HttpStatus.CONFLICT,
            "가입 약관이 변경되었습니다. 최신 내용을 확인해 주세요."
    ),
    SIGNUP_TERMS_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "가입 약관을 준비 중입니다. 잠시 후 다시 시도해 주세요."
    ),
    INVALID_VERIFICATION_CODE(
            HttpStatus.BAD_REQUEST,
            "인증번호가 올바르지 않습니다."
    ),
    VERIFICATION_EXPIRED(
            HttpStatus.GONE,
            "인증번호 또는 인증 증빙이 만료되었거나 사용할 수 없습니다."
    ),
    VERIFICATION_ATTEMPTS_EXCEEDED(
            HttpStatus.TOO_MANY_REQUESTS,
            "인증 시도 횟수를 초과했습니다."
    ),
    IMAGE_TOO_LARGE(
            HttpStatus.CONTENT_TOO_LARGE,
            "프로필 이미지는 5MiB 이하여야 합니다."
    ),
    INVALID_INPUT(
            HttpStatus.BAD_REQUEST,
            "요청 입력값을 확인해 주세요."
    ),
    UNAUTHENTICATED(
            HttpStatus.UNAUTHORIZED,
            "로그인이 필요합니다."
    ),
    FORBIDDEN(
            HttpStatus.FORBIDDEN,
            "이 작업을 수행할 권한이 없습니다."
    ),
    RESOURCE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "요청한 리소스를 찾을 수 없습니다."
    ),
    METHOD_NOT_ALLOWED(
            HttpStatus.METHOD_NOT_ALLOWED,
            "지원하지 않는 요청 방식입니다."
    ),
    NOT_ACCEPTABLE(
            HttpStatus.NOT_ACCEPTABLE,
            "지원하지 않는 응답 형식입니다."
    ),
    PAYLOAD_TOO_LARGE(
            HttpStatus.CONTENT_TOO_LARGE,
            "요청 크기가 허용 범위를 초과했습니다."
    ),
    UNSUPPORTED_MEDIA_TYPE(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "지원하지 않는 요청 형식입니다."
    ),
    RATE_LIMIT_EXCEEDED(
            HttpStatus.TOO_MANY_REQUESTS,
            "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요."
    ),
    INTERNAL_SERVER_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."
    ),
    EMAIL_ALREADY_REGISTERED(
            HttpStatus.CONFLICT,
            "가입된 이메일입니다."
    ),
    RATE_LIMITED(
            HttpStatus.TOO_MANY_REQUESTS,
            "요청이 많습니다. 잠시 후 다시 시도해 주세요."
    ),
    IDEMPOTENCY_CONFLICT(
            HttpStatus.CONFLICT,
            "동일 요청 키에 다른 내용이 전달되었거나 재사용할 수 없는 키입니다."
    ),
    SERVICE_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."
    );

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return name();
    }

    public String getMessage() {
        return message;
    }
}