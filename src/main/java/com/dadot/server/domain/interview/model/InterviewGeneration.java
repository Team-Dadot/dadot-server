package com.dadot.server.domain.interview.model;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class InterviewGeneration {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public static final String PROMPT_VERSION = "interview-generation-v1";
    public static final int MAX_QUESTION_LENGTH = 60;
    public static final int MAX_INTERPRETATION_LENGTH = 180;
    // 존댓말 질문형·요청형을 폭넓게 허용한다. 검사만 하고 원문을 보정하거나 자르지 않는다.
    private static final Pattern QUESTION_ENDING =
            Pattern.compile("(?:요|까|십시오|바랍니다)[?？.。!！]?$");
    public static final Set<String> REPORT_TYPES =
            Set.of("ANNUAL", "HALF_YEAR", "QUARTERLY");

    private InterviewGeneration() {}

    public record Accepted(String taskId, String status) {}
    public record Problem(String code, String message) {}

    public record Task(
            String taskId,
            String status,
            Problem error,
            Instant createdAt,
            Instant completedAt,
            Long questionId
    ) {}

    public record Daily(
            LocalDate businessDate,
            int limit,
            int used,
            int remaining,
            Instant resetAt,
            int questionLimit,
            int questionUsed,
            int questionRemaining
    ) {
        public static Daily of(Instant now, int used, int questions) {
            LocalDate day = now.atZone(KST).toLocalDate();

            return new Daily(
                    day,
                    9,
                    used,
                    Math.max(0, 9 - used),
                    day.plusDays(1).atStartOfDay(KST).toInstant(),
                    3,
                    questions,
                    Math.max(0, 3 - questions)
            );
        }
    }

    public record Usage(
            Daily daily,
            int questionLimit,
            int questionUsed,
            int questionRemaining,
            boolean canSubmit,
            String blockedReason,
            boolean questionIncludedToday
    ) {}

    public record Entry(
            long companyId,
            Long questionId,
            String nextAction,
            Task generationTask,
            Daily dailyUsage
    ) {}

    public record Company(
            long companyId,
            String companyName,
            String industry,
            String logoUrl
    ) {}

    public record Job(Long jobId, String jobName, boolean isCustom) {}
    public record Draft(String text, long version, Instant savedAt) {}

    public record Attempt(
            long attemptId,
            long questionId,
            int attemptNumber,
            String text,
            Instant submittedAt,
            String evaluationTaskId,
            String evaluationStatus
    ) {}

    public record Evidence(
            long disclosureId,
            long disclosureVersionId,
            String reportTitle,
            String sourceUrl,
            String sectionTitle,
            String quote,
            boolean isSuperseded,
            LocalDate publishedAt,
            LocalDate periodStart,
            LocalDate periodEnd,
            String interpretation
    ) {}

    public record Detail(
            long questionId,
            Company company,
            Job jobSnapshot,
            String question,
            Instant createdAt,
            List<Evidence> evidence,
            Draft draft,
            Attempt latestAttempt,
            Usage usage
    ) {}

    public record Preparation(
            long userId,
            long companyId,
            String companyName,
            Long jobId,
            String jobName,
            boolean active,
            long personalizationVersion
    ) {}

    public record Material(
            long materialId,
            long versionId,
            int version,
            String title,
            String content
    ) {}

    public record Source(
            long disclosureId,
            long versionId,
            String reportType,
            String title,
            LocalDate submittedDate,
            LocalDate periodStart,
            LocalDate periodEnd,
            String hash
    ) {}

    public record Section(
            long sectionId,
            long disclosureVersionId,
            String heading,
            String content
    ) {}

    public record Plan(
            List<Source> sources,
            List<Section> sections,
            int scannedSections,
            long scannedCharacters
    ) {}

    public record Policy(long versionId, String rules) {}

    public record Input(
            String companyName,
            Job job,
            List<Source> sources,
            List<Section> sections,
            List<Material> experiences,
            List<String> previousQuestions
    ) {}

    public record Lease(
            String taskId,
            long userId,
            long companyId,
            String contextId,
            long policyVersionId,
            String owner,
            long fence,
            long runId,
            int attempt,
            String model,
            String promptHash,
            String rules,
            Input input
    ) {}

    public record Metadata(
            String requestId,
            String model,
            Integer inputTokens,
            Integer outputTokens
    ) {}

    public record Quote(long disclosureVersionId, long sectionId, String quote, String interpretation) {
        // 기존 데이터·저장 컨텍스트에 해석이 없는 경우의 호환 생성자.
        public Quote(long disclosureVersionId, long sectionId, String quote) {
            this(disclosureVersionId, sectionId, quote, null);
        }
    }
    public record Generated(String question, List<Quote> evidence) {}
    public record Output(Generated generated, Metadata metadata) {}

    public static final class Failure extends RuntimeException {
        private final String code;
        private final boolean retryable;
        private final long retryAfter;
        private final Metadata metadata;
        private final String reason;

        public Failure(String code, boolean retryable) {
            this(code, retryable, 0, null);
        }

        public Failure(
                String code,
                boolean retryable,
                long retryAfter,
                Metadata metadata
        ) {
            this(code, retryable, retryAfter, metadata, code);
        }

        public Failure(
                String code,
                boolean retryable,
                long retryAfter,
                Metadata metadata,
                String reason
        ) {
            super(code);
            this.code = code;
            this.retryable = retryable;
            this.retryAfter = Math.clamp(retryAfter, 0, 60);
            this.metadata = metadata;
            this.reason = reason;
        }

        public String code() { return code; }
        public boolean retryable() { return retryable; }
        public long retryAfter() { return retryAfter; }
        public Metadata metadata() { return metadata; }
        public String reason() { return reason; }

        public Failure withMetadata(Metadata value) {
            return new Failure(code, retryable, retryAfter, value, reason);
        }
    }

    public static Generated validate(JsonNode value, Input input) {
        fields(value, Set.of("question", "evidence"));

        String question = string(value.get("question"), MAX_QUESTION_LENGTH, "QUESTION");
        JsonNode evidence = value.get("evidence");

        if (!evidence.isArray() || evidence.isEmpty() || evidence.size() > 3) {
            throw invalidOutput("EVIDENCE_COUNT");
        }

        var checked = new ArrayList<Quote>();
        var ids = new HashSet<Long>();

        for (JsonNode item : evidence) {
            fields(item, Set.of("disclosureVersionId", "sectionId", "quote"), Set.of("interpretation"));

            long version = outputId(item.get("disclosureVersionId"));
            long section = outputId(item.get("sectionId"));
            String quote = string(item.get("quote"), 500, "EVIDENCE_QUOTE");

            if (!ids.add(section)) {
                throw invalidOutput("EVIDENCE_SECTION_DUPLICATE");
            }

            Section source = input.sections().stream()
                    .filter(s -> s.sectionId() == section)
                    .findFirst()
                    .orElseThrow(
                            () -> invalidOutput("EVIDENCE_SECTION_UNKNOWN")
                    );

            if (source.disclosureVersionId() != version) {
                throw invalidOutput("EVIDENCE_VERSION_MISMATCH");
            }

            if (!source.content().contains(quote)) {
                throw invalidOutput("EVIDENCE_QUOTE_MISMATCH");
            }

            JsonNode explanation = item.get("interpretation");
            String interpretation = explanation == null || explanation.isNull()
                    ? null : validateInterpretation(explanation);
            checked.add(new Quote(version, section, quote, interpretation));
        }

        return new Generated(question, List.copyOf(checked));
    }

    // 새 AI 응답에서만 호출한다. 기존 저장 질문의 조회나 종결형을 소급 변경하지 않는다.
    public static Generated validateComplete(Generated generated) {
        if (!QUESTION_ENDING.matcher(generated.question().stripTrailing()).find()) {
            throw invalidOutput("QUESTION_INCOMPLETE");
        }
        for (Quote evidence : generated.evidence()) {
            if (evidence.interpretation() == null) {
                throw invalidOutput("EVIDENCE_INTERPRETATION_REQUIRED");
            }
        }
        return generated;
    }

    private static String validateInterpretation(JsonNode value) {
        String text = string(value, MAX_INTERPRETATION_LENGTH, "EVIDENCE_INTERPRETATION");
        // 완결된 존댓말 뒤에서 나눈다. 소수점·영문 약어는 문장 경계로 세지 않는다.
        String[] sentences = text.strip().split("(?<=[요다][.。])\\s*");
        if (sentences.length > 2) {
            throw invalidOutput("EVIDENCE_INTERPRETATION_SENTENCES");
        }
        for (String sentence : sentences) {
            if (!sentence.matches("(?s).*(?:요|다)[.。]")) {
                throw invalidOutput("EVIDENCE_INTERPRETATION_INCOMPLETE");
            }
        }
        return text;
    }

    public static String fingerprint(String question) {
        return sha256(
                Normalizer.normalize(question, Normalizer.Form.NFC)
                        .strip()
                        .replaceAll("(?U)\\s+", " ")
        );
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static String uuid(String value) {
        if (value == null || !value.matches(
                "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        )) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        return UUID.fromString(value).toString();
    }

    public static Failure invalidOutput() {
        return invalidOutput("INVALID_OUTPUT");
    }

    private static Failure invalidOutput(String reason) {
        return new Failure(
                "QUESTION_GENERATION_FAILED", false, 0, null, reason
        );
    }

    private static String string(JsonNode node, int max, String field) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw invalidOutput(field + "_EMPTY");
        }

        String value = node.asText();

        if (value.codePointCount(0, value.length()) > max) {
            throw invalidOutput(field + "_TOO_LONG");
        }

        return value;
    }

    private static long outputId(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()
                || node.longValue() < 1
                || node.longValue() > InterviewPolicy.MAX_ID) {
            throw invalidOutput("EVIDENCE_ID_INVALID");
        }

        return node.longValue();
    }

    public static void fields(JsonNode node, Set<String> names) {
        fields(node, names, Set.of());
    }

    private static void fields(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject() || !node.propertyNames().containsAll(required)) {
            throw invalidOutput("JSON_STRUCTURE_INVALID");
        }
        for (String name : node.propertyNames()) {
            if (!required.contains(name) && !optional.contains(name)) {
                throw invalidOutput("JSON_STRUCTURE_INVALID");
            }
        }
    }
}