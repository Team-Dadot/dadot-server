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

public final class InterviewGeneration {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    public static final String PROMPT_VERSION = "interview-generation-v1";
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
            boolean isSuperseded
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

    public record Quote(long disclosureVersionId, long sectionId, String quote) {}
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

        String question = string(value.get("question"), 100, "QUESTION");
        JsonNode evidence = value.get("evidence");

        if (!evidence.isArray() || evidence.isEmpty() || evidence.size() > 3) {
            throw invalidOutput("EVIDENCE_COUNT");
        }

        var checked = new ArrayList<Quote>();
        var ids = new HashSet<Long>();

        for (JsonNode item : evidence) {
            fields(item, Set.of("disclosureVersionId", "sectionId", "quote"));

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

            checked.add(new Quote(version, section, quote));
        }

        return new Generated(question, List.copyOf(checked));
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
        if (node == null || !node.isObject() || node.size() != names.size()
                || !node.propertyNames().containsAll(names)) {
            throw invalidOutput("JSON_STRUCTURE_INVALID");
        }
    }
}