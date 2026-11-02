package com.dadot.server.domain.experience.model;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Set;

public final class Experience {

    public static final int LIMIT = 100;
    private static final long MAX_ID = 9_007_199_254_740_991L;

    private Experience() {}

    public record Create(String title, String content) {
        public Create {
            text(title, 50);
            text(content, 5000);
        }

        public static Create parse(JsonNode body) {
            object(body, Set.of("title", "content"), Set.of());
            return new Create(string(body.get("title")), string(body.get("content")));
        }
    }

    public record Patch(long version, String title, String content) {
        public Patch {
            require(version > 0 && (title != null || content != null));
            if (title != null) text(title, 50);
            if (content != null) text(content, 5000);
        }

        public static Patch parse(JsonNode body) {
            object(body, Set.of("version"), Set.of("title", "content"));
            JsonNode version = body.get("version");
            require(version.isIntegralNumber() && version.canConvertToLong());
            return new Patch(version.longValue(),
                    body.has("title") ? string(body.get("title")) : null,
                    body.has("content") ? string(body.get("content")) : null);
        }
    }

    public record Detail(long experienceId, String title, String content,
                         long version, Instant createdAt, Instant updatedAt) {}

    public record Summary(long experienceId, String title, String preview,
                          long version, Instant createdAt, Instant updatedAt) {}

    public record Paging(int page, int size) {
        public Paging {
            require(page >= 0 && size >= 1 && size <= 100);
        }

        public long offset() { return (long) page * size; }
    }

    public static long id(long value) {
        require(value > 0 && value <= MAX_ID);
        return value;
    }

    public static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_INPUT);
    }

    public static BusinessException missing() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private static void text(String value, int maximum) {
        require(value != null && !value.isEmpty());
        require(value.codePointCount(0, value.length()) <= maximum);
        require(value.codePoints().anyMatch(cp ->
                !Character.isWhitespace(cp) && !Character.isSpaceChar(cp)));
        // 단독 surrogate는 JDBC 인코딩 중 다른 문자로 바뀌지 않도록 거절한다.
        require(value.codePoints().noneMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF));
    }

    private static String string(JsonNode node) {
        require(node != null && node.isString());
        return node.stringValue();
    }

    private static void object(JsonNode node, Set<String> required, Set<String> optional) {
        require(node != null && node.isObject());
        for (String name : required) require(node.has(name));
        for (String name : node.propertyNames()) {
            require(required.contains(name) || optional.contains(name));
        }
    }

    private static void require(boolean condition) {
        if (!condition) throw invalid();
    }
}