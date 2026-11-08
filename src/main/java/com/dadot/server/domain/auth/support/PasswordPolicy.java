package com.dadot.server.domain.auth.support;

public final class PasswordPolicy {
    public static final int MIN_LENGTH = 8;
    public static final int MAX_LENGTH = 20;
    public static final String PATTERN =
            "\\A(?=.*[A-Za-z])(?=.*[0-9])(?=.*[^A-Za-z0-9])[^\\s\\p{Z}\\p{C}]+\\z";
    public static final String DESCRIPTION =
            "8~20자, 영문·숫자·특수문자 포함, 공백·제어문자 금지";

    private PasswordPolicy() {}
}