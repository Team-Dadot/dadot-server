package com.dadot.server.domain.member.model;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Set;

public final class Member {
    private Member() {}

    public record Profile(long userId, String nickname) {}

    public record Summary(
            long userId,
            String nickname,
            String provider,
            String email,
            boolean onboardingCompleted,
            int experienceCount,
            int coverLetterCount,
            Notifications notificationSettings
    ) {}

    public record Notifications(
            boolean enabled,
            String scheduledTime,
            String timeZone,
            String deliveryPolicyStatus,
            Device currentDevice,
            long version
    ) {}

    public record Device(
            String installationId,
            String platform,
            String permission,
            boolean registered
    ) {}

    public record Nickname(String nickname) {
        public Nickname {
            if (nickname == null
                    || nickname.isBlank()
                    || !nickname.equals(nickname.strip())
                    || nickname.codePointCount(0, nickname.length()) > 20) {
                throw invalid();
            }

            for (int offset = 0; offset < nickname.length();) {
                int point = nickname.codePointAt(offset);

                if (point >= 0xD800 && point <= 0xDFFF
                        || Character.isISOControl(point)
                        || Character.getType(point) == Character.FORMAT) {
                    throw invalid();
                }

                offset += Character.charCount(point);
            }
        }

        public static Nickname parse(JsonNode body) {
            object(body, Set.of("nickname"));
            return new Nickname(text(body, "nickname"));
        }
    }

    public enum Provider {
        EMAIL,
        KAKAO,
        APPLE
    }

    public record Delete(
            Provider provider,
            String password,
            String accessToken,
            String identityToken,
            String authorizationCode,
            String nonce
    ) {
        public Delete {
            if (provider == null) {
                throw invalid();
            }

            switch (provider) {
                case EMAIL -> {
                    if (password == null
                            || !password.matches(
                            "(?s)(?=.*[A-Za-z])(?=.*[0-9])[^\\s]{8,64}"
                    )
                            || accessToken != null
                            || identityToken != null
                            || authorizationCode != null
                            || nonce != null) {
                        throw invalid();
                    }
                }
                case KAKAO -> {
                    if (!matches(accessToken, "[!~\\x21-\\x7E]{1,2048}")
                            || password != null
                            || identityToken != null
                            || authorizationCode != null
                            || nonce != null) {
                        throw invalid();
                    }
                }
                case APPLE -> {
                    if (password != null
                            || accessToken != null
                            || identityToken == null
                            || identityToken.length() > 8192
                            || !identityToken.matches(
                            "[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"
                    )
                            || !matches(
                            authorizationCode,
                            "[A-Za-z0-9._~+/=-]{1,2048}"
                    )
                            || !matches(nonce, "[A-Za-z0-9_-]{43}")) {
                        throw invalid();
                    }
                }
            }
        }

        public static Delete parse(JsonNode body) {
            if (body == null
                    || !body.isObject()
                    || !body.path("confirm").isBoolean()
                    || !body.path("confirm").booleanValue()) {
                throw invalid();
            }

            if (body.has("password")) {
                object(body, Set.of("confirm", "password"));

                return new Delete(
                        Provider.EMAIL,
                        text(body, "password"),
                        null,
                        null,
                        null,
                        null
                );
            }

            object(body, Set.of("confirm", "socialCredential"));

            JsonNode social = body.get("socialCredential");

            if (social != null && social.has("accessToken")) {
                object(social, Set.of("accessToken"));

                return new Delete(
                        Provider.KAKAO,
                        null,
                        text(social, "accessToken"),
                        null,
                        null,
                        null
                );
            }

            object(
                    social,
                    Set.of("identityToken", "authorizationCode", "nonce")
            );

            return new Delete(
                    Provider.APPLE,
                    null,
                    null,
                    text(social, "identityToken"),
                    text(social, "authorizationCode"),
                    text(social, "nonce")
            );
        }

        @Override
        public String toString() {
            return "Delete[credentials=REDACTED]";
        }
    }

    public record Identity(
            Provider provider,
            String subject,
            String email,
            String passwordHash
    ) {
        @Override
        public String toString() {
            return "Identity[REDACTED]";
        }
    }

    public record Reauthenticated(
            Identity identity,
            Instant validUntil,
            String revocationToken
    ) {
        @Override
        public String toString() {
            return "Reauthenticated[REDACTED]";
        }
    }

    public static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_INPUT);
    }

    public static BusinessException unauthenticated() {
        return new BusinessException(ErrorCode.UNAUTHENTICATED);
    }

    public static BusinessException reauthenticationFailed() {
        return new BusinessException(ErrorCode.REAUTHENTICATION_FAILED);
    }

    private static boolean matches(String value, String pattern) {
        return value != null && value.matches(pattern);
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.get(field);

        if (node == null || !node.isString()) {
            throw invalid();
        }

        return node.asString();
    }

    private static void object(JsonNode node, Set<String> fields) {
        if (node == null
                || !node.isObject()
                || node.size() != fields.size()
                || !node.propertyNames().equals(fields)) {
            throw invalid();
        }
    }
}