package com.dadot.server.domain.onboarding.dto.request;

import com.dadot.server.domain.onboarding.model.OnboardingStep;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

public record OnboardingPatchRequest(
        OnboardingStep step,
        long version,
        JsonNode payload
) {
    private static final long MAX_ID = 9007199254740991L;

    public static OnboardingPatchRequest parse(JsonNode body) {
        exactFields(body, Set.of("step", "version", "payload"));
        require(body.get("step").isString());

        OnboardingStep step;
        try {
            step = OnboardingStep.valueOf(body.get("step").stringValue());
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }

        require(step != OnboardingStep.COMPLETE);
        long version = positiveLong(body.get("version"), Long.MAX_VALUE);

        String field = switch (step) {
            case COMPANIES -> "companyIds";
            case JOBS -> "assignments";
            case DATES -> "dates";
            case EXPERIENCE -> "experienceIds";
            case NOTIFICATION_GUIDANCE -> "guidanceCompleted";
            case COMPLETE -> throw invalid();
        };

        exactFields(body.get("payload"), Set.of(field));

        return new OnboardingPatchRequest(
                step, version, body.get("payload").deepCopy()
        );
    }

    public List<Long> companyIds() {
        return ids(payload.get("companyIds"), 1, 5);
    }

    public List<JobInput> jobs() {
        return array(payload.get("assignments"), 1, 5).stream().map(value -> {
            exactFields(value, Set.of("companyId", "job"));
            long companyId = id(value.get("companyId"));

            JsonNode job = value.get("job");
            require(job != null && job.isObject());
            require(Set.of("jobId", "customName").containsAll(job.propertyNames()));

            JsonNode idNode = job.get("jobId");
            JsonNode customNode = job.get("customName");

            Long jobId = idNode == null || idNode.isNull() ? null : id(idNode);
            String customName = null;

            if (customNode != null && !customNode.isNull()) {
                require(customNode.isString());
                customName = customNode.stringValue().strip();

                int length = customName.codePointCount(0, customName.length());
                require(length >= 1 && length <= 50);
            }

            require((jobId == null) != (customName == null));

            return new JobInput(companyId, jobId, customName);
        }).toList();
    }

    public List<DateInput> dates() {
        return array(payload.get("dates"), 1, 5).stream().map(value -> {
            exactFields(value, Set.of("companyId", "interviewDate"));

            JsonNode date = value.get("interviewDate");
            LocalDate parsed = null;

            if (!date.isNull()) {
                require(date.isString()
                        && date.stringValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"));

                try {
                    parsed = LocalDate.parse(date.stringValue());
                } catch (DateTimeParseException ex) {
                    throw invalid();
                }

                require(parsed.getYear() >= 1000);
            }

            return new DateInput(id(value.get("companyId")), parsed);
        }).toList();
    }

    public List<Long> experienceIds() {
        return ids(payload.get("experienceIds"), 0, 100);
    }

    public void requireGuidanceCompleted() {
        JsonNode value = payload.get("guidanceCompleted");
        require(value != null && value.isBoolean() && value.booleanValue());
    }

    private static List<Long> ids(JsonNode node, int min, int max) {
        var values = array(node, min, max).stream()
                .map(OnboardingPatchRequest::id).toList();

        require(new HashSet<>(values).size() == values.size());

        return values;
    }

    private static List<JsonNode> array(JsonNode node, int min, int max) {
        require(node != null && node.isArray()
                && node.size() >= min && node.size() <= max);

        return StreamSupport.stream(node.spliterator(), false).toList();
    }

    private static long id(JsonNode node) {
        return positiveLong(node, MAX_ID);
    }

    private static long positiveLong(JsonNode node, long maximum) {
        require(node != null && node.isIntegralNumber() && node.canConvertToLong());

        long value = node.longValue();
        require(value >= 1 && value <= maximum);

        return value;
    }

    private static void exactFields(JsonNode node, Set<String> fields) {
        require(node != null && node.isObject()
                && new HashSet<>(node.propertyNames()).equals(fields));
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_INPUT);
    }

    @Override
    public String toString() {
        return "OnboardingPatchRequest[step=" + step + "]";
    }

    public record JobInput(long companyId, Long jobId, String customName) {
    }

    public record DateInput(long companyId, LocalDate interviewDate) {
    }
}