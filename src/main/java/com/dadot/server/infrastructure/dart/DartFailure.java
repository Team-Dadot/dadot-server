package com.dadot.server.infrastructure.dart;

public final class DartFailure extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public DartFailure(String code, boolean retryable) {
        super(code); // URL, 인증키, 외부 응답 원문을 예외에 포함하지 않는다.
        this.code = code;
        this.retryable = retryable;
    }

    public String code() { return code; }
    public boolean retryable() { return retryable; }
}