package com.dadot.server.domain.auth.support;

import java.util.UUID;

/** 소셜 이름은 표시용으로만 사용하며 기존 닉네임 저장 규칙을 따른다. */
public final class SocialNickname {
    private SocialNickname() {}

    public static String normalize(String value) {
        if (value == null) return null;
        String name = value.strip();
        if (name.isBlank() || name.codePointCount(0, name.length()) > 20) {
            return null;
        }
        for (int offset = 0; offset < name.length();) {
            int point = name.codePointAt(offset);
            if (point >= 0xD800 && point <= 0xDFFF
                    || Character.isISOControl(point)
                    || Character.getType(point) == Character.FORMAT) {
                return null;
            }
            offset += Character.charCount(point);
        }
        return name;
    }

    public static String initial(String value) {
        String name = normalize(value);
        return name != null ? name : "다닷" + UUID.randomUUID()
                .toString().replace("-", "").substring(0, 12);
    }
}
