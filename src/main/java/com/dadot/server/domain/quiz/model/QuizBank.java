package com.dadot.server.domain.quiz.model;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class QuizBank {

    public static final long MAX_ID = 9_007_199_254_740_991L;
    public static final int MAX_EVIDENCE = 20;

    private QuizBank() {}

    public enum Status {
        DRAFT,
        IN_REVIEW,
        APPROVED,
        PUBLISHED,
        SUSPENDED,
        SUPERSEDED;

        public boolean canChangeTo(Status next) {
            return switch (this) {
                case DRAFT ->
                        next == IN_REVIEW;

                case IN_REVIEW ->
                        next == DRAFT || next == APPROVED;

                case APPROVED ->
                        next == DRAFT || next == PUBLISHED;

                case PUBLISHED ->
                        next == SUSPENDED || next == SUPERSEDED;

                case SUSPENDED ->
                        next == IN_REVIEW || next == SUPERSEDED;

                case SUPERSEDED ->
                        false;
            };
        }
    }

    public record InputOption(
            int position,
            String text
    ) {
        public InputOption {
            if (position < 1 || position > 4) {
                throw invalid();
            }

            QuizBank.text(text, 1000);
        }
    }

    public record InputEvidence(
            long disclosureVersionId,
            long sectionId,
            String quote
    ) {
        public InputEvidence {
            id(disclosureVersionId);
            id(sectionId);
            text(quote, 5000);
        }
    }

    public record Content(
            long companyId,
            String question,
            List<InputOption> options,
            int correctOptionPosition,
            String explanation,
            List<InputEvidence> evidence
    ) {
        public Content {
            id(companyId);
            text(question, 2000);
            text(explanation, 5000);

            if (options == null
                    || options.size() != 4
                    || correctOptionPosition < 1
                    || correctOptionPosition > 4
                    || evidence == null
                    || evidence.isEmpty()
                    || evidence.size() > MAX_EVIDENCE) {
                throw invalid();
            }

            var positions = new HashSet<Integer>();

            for (var option : options) {
                if (option == null || !positions.add(option.position())) {
                    throw invalid();
                }
            }

            var sections = new HashSet<Long>();

            for (var item : evidence) {
                if (item == null || !sections.add(item.sectionId())) {
                    throw invalid();
                }
            }

            options = List.copyOf(options);
            evidence = List.copyOf(evidence);
        }

        public static Content parse(JsonNode node) {
            object(
                    node,
                    Set.of(
                            "companyId",
                            "question",
                            "options",
                            "correctOptionPosition",
                            "explanation",
                            "evidence"
                    ),
                    Set.of()
            );

            if (!node.get("options").isArray()
                    || !node.get("evidence").isArray()
                    || node.get("options").size() != 4
                    || node.get("evidence").isEmpty()
                    || node.get("evidence").size() > MAX_EVIDENCE) {
                throw invalid();
            }

            var options = new java.util.ArrayList<InputOption>();

            for (var option : node.get("options")) {
                object(
                        option,
                        Set.of("position", "text"),
                        Set.of()
                );

                options.add(new InputOption(
                        small(option.get("position")),
                        string(option.get("text"))
                ));
            }

            var evidence = new java.util.ArrayList<InputEvidence>();

            for (var item : node.get("evidence")) {
                object(
                        item,
                        Set.of(
                                "disclosureVersionId",
                                "sectionId",
                                "quote"
                        ),
                        Set.of()
                );

                evidence.add(new InputEvidence(
                        number(item.get("disclosureVersionId")),
                        number(item.get("sectionId")),
                        string(item.get("quote"))
                ));
            }

            return new Content(
                    number(node.get("companyId")),
                    string(node.get("question")),
                    options,
                    small(node.get("correctOptionPosition")),
                    string(node.get("explanation")),
                    evidence
            );
        }
    }

    public record Patch(
            long expectedVersion,
            long questionVersionId,
            Status status,
            String reviewNote,
            Content content
    ) {
        public Patch {
            id(expectedVersion);
            id(questionVersionId);
            text(reviewNote, 1000);

            if (status == null && content == null) {
                throw invalid();
            }

            if (content != null
                    && status != null
                    && status != Status.DRAFT) {
                throw invalid();
            }
        }

        public static Patch parse(JsonNode node) {
            object(
                    node,
                    Set.of(
                            "expectedVersion",
                            "questionVersionId",
                            "reviewNote"
                    ),
                    Set.of(
                            "status",
                            "content"
                    )
            );

            return new Patch(
                    number(node.get("expectedVersion")),
                    number(node.get("questionVersionId")),
                    node.has("status")
                            ? state(string(node.get("status")))
                            : null,
                    string(node.get("reviewNote")),
                    node.has("content")
                            ? Content.parse(node.get("content"))
                            : null
            );
        }
    }

    public record Search(
            int page,
            int size,
            Long companyId,
            Status status
    ) {
        public Search {
            if (page < 0 || size < 1 || size > 100) {
                throw invalid();
            }

            if (companyId != null) {
                id(companyId);
            }
        }
    }

    public record Option(
            long optionId,
            int position,
            String text
    ) {}

    public record Evidence(
            long disclosureId,
            long disclosureVersionId,
            long sectionId,
            String reportTitle,
            String sourceUrl,
            String sectionTitle,
            String quote,
            boolean isSuperseded
    ) {}

    public record View(
            long questionId,
            long questionVersionId,
            long companyId,
            long version,
            Status status,
            String question,
            List<Option> options,
            long correctOptionId,
            String explanation,
            List<Evidence> evidence,
            String reviewNote,
            Instant updatedAt
    ) {}

    public record Page(
            List<View> content,
            int page,
            int size,
            long totalElements,
            long totalPages,
            boolean hasNext
    ) {}

    public static long id(long value) {
        if (value < 1 || value > MAX_ID) {
            throw invalid();
        }

        return value;
    }

    public static Status state(String value) {
        try {
            return Status.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
    }

    public static String text(
            String value,
            int maximum
    ) {
        if (value == null
                || value.isBlank()
                || value.codePointCount(0, value.length()) > maximum) {
            throw invalid();
        }

        return value;
    }

    public static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_INPUT);
    }

    public static BusinessException missing() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    public static BusinessException transition() {
        return new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
    }

    public static BusinessException unavailable() {
        return new BusinessException(ErrorCode.CONTENT_UNAVAILABLE);
    }

    private static void object(
            JsonNode node,
            Set<String> required,
            Set<String> optional
    ) {
        if (node == null || !node.isObject()) {
            throw invalid();
        }

        for (String name : required) {
            if (!node.has(name)) {
                throw invalid();
            }
        }

        for (String name : node.propertyNames()) {
            if (!required.contains(name) && !optional.contains(name)) {
                throw invalid();
            }
        }
    }

    private static String string(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw invalid();
        }

        return node.asText();
    }

    private static long number(JsonNode node) {
        if (node == null
                || !node.isIntegralNumber()
                || !node.canConvertToLong()) {
            throw invalid();
        }

        return id(node.longValue());
    }

    private static int small(JsonNode node) {
        long value = number(node);

        if (value > Integer.MAX_VALUE) {
            throw invalid();
        }

        return (int) value;
    }
}