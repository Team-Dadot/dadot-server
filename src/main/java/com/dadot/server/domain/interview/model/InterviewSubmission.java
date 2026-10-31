package com.dadot.server.domain.interview.model;

import com.dadot.server.domain.interview.model.InterviewGeneration.Draft;
import com.dadot.server.domain.interview.model.InterviewGeneration.Usage;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class InterviewSubmission {
    private InterviewSubmission() {}

    public record SaveDraft(String text, Long version) {
        public SaveDraft {
            validateText(text, true);
            if (version != null) InterviewPolicy.id(version);
        }

        public static SaveDraft parse(JsonNode body) {
            object(body, Set.of("text", "version"), Set.of());
            return new SaveDraft(string(body.get("text")), InterviewSubmission.version(body.get("version")));
        }
    }

    public record Submit(String text, Long draftVersion, boolean draftVersionPresent) {
        public Submit {
            validateText(text, false);
            if (draftVersion != null) InterviewPolicy.id(draftVersion);
            if (!draftVersionPresent && draftVersion != null) throw InterviewPolicy.invalid();
        }

        public static Submit parse(JsonNode body) {
            object(body, Set.of("text"), Set.of("draftVersion"));
            return new Submit(string(body.get("text")),
                    body.has("draftVersion") ? InterviewSubmission.version(body.get("draftVersion")) : null,
                    body.has("draftVersion"));
        }

        // 생략과 명시적 null을 구분하고, 문자열 원문을 그대로 HMAC 입력에 사용한다.
        public Map<String, Object> canonicalBody() {
            var fields = new TreeMap<String, Object>();
            fields.put("text", text);
            if (draftVersionPresent) fields.put("draftVersion", draftVersion);
            return fields;
        }
    }

    public record Saved(long questionId, Draft draft) {}
    public record Accepted(String taskId, String status, long attemptId,
                           int attemptNumber, Instant submittedAt, Usage usage) {}
    public record Question(long id, long companyId, Long jobId, String jobName,
                           boolean customJob, long personalizationVersion) {}

    private static void object(JsonNode body, Set<String> required, Set<String> optional) {
        if (body == null || !body.isObject()) throw InterviewPolicy.invalid();
        for (String name : required) if (!body.has(name)) throw InterviewPolicy.invalid();
        for (String name : body.propertyNames()) {
            if (!required.contains(name) && !optional.contains(name)) throw InterviewPolicy.invalid();
        }
    }

    private static String string(JsonNode node) {
        if (node == null || !node.isTextual()) throw InterviewPolicy.invalid();
        return node.asText();
    }

    private static Long version(JsonNode node) {
        if (node == null) throw InterviewPolicy.invalid();
        if (node.isNull()) return null;
        if (!node.isIntegralNumber() || !node.canConvertToLong()) throw InterviewPolicy.invalid();
        return InterviewPolicy.id(node.longValue());
    }

    private static void validateText(String text, boolean draft) {
        if (text == null || (!draft && text.isBlank())
                || text.codePointCount(0, text.length()) > 2000) throw InterviewPolicy.invalid();
        for (int offset = 0; offset < text.length();) {
            int point = text.codePointAt(offset);
            if ((point >= 0xD800 && point <= 0xDFFF)
                    || (Character.isISOControl(point) && point != '\n' && point != '\r' && point != '\t')) {
                throw InterviewPolicy.invalid();
            }
            offset += Character.charCount(point);
        }
    }
}