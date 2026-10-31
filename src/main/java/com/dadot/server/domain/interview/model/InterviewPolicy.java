package com.dadot.server.domain.interview.model;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class InterviewPolicy {

    public static final long MAX_ID = 9_007_199_254_740_991L;
    public static final String AGGREGATION = "SUM_4_X_25_THRESHOLD_80";
    public static final List<String> CODES = List.of(
            "COMPANY_UNDERSTANDING", "EXPERIENCE_SPECIFICITY",
            "ANSWER_LOGIC_STRUCTURE", "EXPRESSION_CLARITY"
    );
    public static final List<String> LABELS = List.of(
            "기업에 대한 이해", "경험의 구체성", "답변의 논리와 구성", "표현의 명확성"
    );

    private InterviewPolicy() {}

    public enum Type { GENERATION, EVALUATION }

    public enum Status {
        DRAFT, IN_REVIEW, APPROVED, PUBLISHED, SUSPENDED, SUPERSEDED;

        public boolean canChangeTo(Status next) {
            return switch (this) {
                case DRAFT -> next == IN_REVIEW;
                case IN_REVIEW -> next == DRAFT || next == APPROVED;
                case APPROVED -> next == DRAFT || next == PUBLISHED;
                case PUBLISHED -> next == SUSPENDED;
                case SUSPENDED -> next == IN_REVIEW;
                case SUPERSEDED -> false;
            };
        }
    }

    public record Scope(Long companyId, Long jobId) {
        public Scope {
            if (companyId != null) id(companyId);
            if (jobId != null) id(jobId);
        }

        public String key(Type type) {
            return type + ":" + (companyId == null ? 0 : companyId)
                    + ":" + (jobId == null ? 0 : jobId);
        }
    }

    public record Criterion(String code, String label, int maxScore, String description) {
        public Criterion {
            if (code == null || label == null || maxScore != 25) throw invalid();
            text(description, 5000);
        }
    }

    public record Create(Type type, Scope scope, String rules, List<Criterion> criteria,
                         String aggregationRule, Long sourcePolicyId) {
        public Create {
            if (type == null || scope == null || criteria == null) throw invalid();
            text(rules, 20_000);
            validateCriteria(type, criteria, aggregationRule);
            criteria = List.copyOf(criteria);
            if (sourcePolicyId != null) id(sourcePolicyId);
        }

        public static Create parse(JsonNode node) {
            object(node, Set.of("type", "scope", "rules", "criteria", "aggregationRule"),
                    Set.of("sourcePolicyId"));
            JsonNode scope = node.get("scope");
            object(scope, Set.of("companyId", "jobId"), Set.of());
            JsonNode items = node.get("criteria");
            if (!items.isArray() || items.size() > 4) throw invalid();
            var criteria = new ArrayList<Criterion>();
            for (JsonNode item : items) {
                object(item, Set.of("code", "label", "maxScore", "description"), Set.of());
                long maximum = number(item.get("maxScore"));
                if (maximum != 25) throw invalid();
                criteria.add(new Criterion(string(item.get("code")), string(item.get("label")),
                        25, string(item.get("description"))));
            }
            return new Create(enumValue(Type.class, string(node.get("type"))),
                    new Scope(nullableId(scope.get("companyId")), nullableId(scope.get("jobId"))),
                    string(node.get("rules")), criteria, nullableString(node.get("aggregationRule")),
                    node.has("sourcePolicyId") ? nullableId(node.get("sourcePolicyId")) : null);
        }
    }

    public record Patch(long expectedVersion, long policyVersionId, Status status, String reviewNote) {
        public Patch {
            id(expectedVersion);
            id(policyVersionId);
            if (status == null || status == Status.SUPERSEDED) throw invalid();
            text(reviewNote, 1000);
        }

        public static Patch parse(JsonNode node) {
            object(node, Set.of("expectedVersion", "policyVersionId", "status", "reviewNote"), Set.of());
            return new Patch(number(node.get("expectedVersion")), number(node.get("policyVersionId")),
                    enumValue(Status.class, string(node.get("status"))), string(node.get("reviewNote")));
        }
    }

    public record Search(int page, int size, Long companyId, Long jobId, Type type) {
        public Search {
            if (page < 0 || size < 1 || size > 100) throw invalid();
            if (companyId != null) id(companyId);
            if (jobId != null) id(jobId);
        }
    }

    public record View(long policyId, long policyVersionId, long version, Type type, Scope scope,
                       Status status, String rules, List<Criterion> criteria, String aggregationRule,
                       String reviewNote, Instant createdAt) {}

    public record Page(List<View> content, int page, int size, long totalElements,
                       long totalPages, boolean hasNext) {}

    public record Score(int totalScore, int scorePercent, boolean completionDecision) {}

    public static Score score(List<Integer> values) {
        if (values == null || values.size() != 4) throw invalid();
        int total = 0;
        for (Integer value : values) {
            if (value == null || value < 0 || value > 25) throw invalid();
            total += value;
        }
        return new Score(total, total, total >= 80);
    }

    public static void validateCriteria(Type type, List<Criterion> criteria, String aggregation) {
        if (type == Type.GENERATION) {
            if (!criteria.isEmpty() || aggregation != null) throw invalid();
            return;
        }
        if (type != Type.EVALUATION || criteria.size() != 4 || !AGGREGATION.equals(aggregation)) {
            throw invalid();
        }
        for (int i = 0; i < 4; i++) {
            Criterion item = criteria.get(i);
            if (item == null || !CODES.get(i).equals(item.code())
                    || !LABELS.get(i).equals(item.label()) || item.maxScore() != 25) {
                throw invalid();
            }
            text(item.description(), 5000);
        }
    }

    public static long id(long value) {
        if (value < 1 || value > MAX_ID) throw invalid();
        return value;
    }

    public static String text(String value, int maximum) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maximum) {
            throw invalid();
        }
        return value;
    }

    public static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw invalid();
        }
    }

    public static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_INPUT); }
    public static BusinessException missing() { return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND); }
    public static BusinessException transition() { return new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION); }
    public static BusinessException notConfigured() { return new BusinessException(ErrorCode.POLICY_NOT_CONFIGURED); }

    private static void object(JsonNode node, Set<String> required, Set<String> optional) {
        if (node == null || !node.isObject()) throw invalid();
        for (String name : required) if (!node.has(name)) throw invalid();
        for (String name : node.propertyNames()) {
            if (!required.contains(name) && !optional.contains(name)) throw invalid();
        }
    }

    private static String string(JsonNode node) {
        if (node == null || !node.isTextual()) throw invalid();
        return node.asText();
    }

    private static String nullableString(JsonNode node) {
        return node != null && node.isNull() ? null : string(node);
    }

    private static Long nullableId(JsonNode node) {
        return node != null && node.isNull() ? null : number(node);
    }

    private static long number(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) throw invalid();
        return id(node.longValue());
    }
}