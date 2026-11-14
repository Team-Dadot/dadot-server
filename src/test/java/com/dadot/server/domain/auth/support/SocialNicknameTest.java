package com.dadot.server.domain.auth.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SocialNicknameTest {
    @Test
    void preservesNameIncludingInternalSpacesAndUnicodeCodePoints() {
        assertEquals("홍 길동", SocialNickname.normalize(" 홍 길동 "));
        assertEquals("🐱".repeat(20), SocialNickname.normalize("🐱".repeat(20)));
        assertEquals("José", SocialNickname.initial("José"));
    }

    @Test
    void unsuitableNamesDoNotFailSignupAndAreNotSilentlyTruncated() {
        for (String name : new String[]{null, "", " ", "가".repeat(21),
                "홍\n길동", "홍\u0000길동", "홍\u202E길동", "\uD800"}) {
            assertNull(SocialNickname.normalize(name));
            assertTrue(SocialNickname.initial(name).matches("다닷[0-9a-f]{12}"));
        }
    }
}
