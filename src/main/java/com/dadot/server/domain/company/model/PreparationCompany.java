package com.dadot.server.domain.company.model;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class PreparationCompany {

    public static final long MAX_ID = 9_007_199_254_740_991L;
    public static final int MAX_ACTIVE = 5;

    private PreparationCompany() {}

    public enum JobScope { THIS_COMPANY, ALL_ACTIVE }

    public record JobInput(Long jobId, String customName) {
        public JobInput {
            if (jobId != null) id(jobId);
            if (customName != null) {
                customName = customName.strip();
                int length = customName.codePointCount(0, customName.length());
                require(!customName.isBlank() && length <= 50);
            }
            require((jobId == null) != (customName == null));
        }
    }

    public record Add(long companyId, JobInput job, LocalDate interviewDate) {
        public Add {
            id(companyId);
            require(job != null);
            validDate(interviewDate);
        }

        public static Add parse(JsonNode body) {
            object(body, Set.of("companyId", "job"), Set.of("interviewDate"));
            return new Add(number(body.get("companyId"), MAX_ID),
                    PreparationCompany.job(body.get("job")),
                    date(body.get("interviewDate")));
        }
    }

    public record Expected(long companyId, long version) {
        public Expected {
            id(companyId);
            require(version > 0);
        }
    }

    public record Patch(long version, JobInput job,
                        @Schema(hidden = true) boolean dateSpecified,
                        LocalDate interviewDate, JobScope jobScope,
                        List<Expected> expectedCompanyVersions) {
        public Patch {
            require(version > 0 && jobScope != null
                    && expectedCompanyVersions != null);
            require(job != null || dateSpecified);
            require(dateSpecified || interviewDate == null);
            validDate(interviewDate);

            if (jobScope == JobScope.ALL_ACTIVE) {
                require(job != null && !expectedCompanyVersions.isEmpty()
                        && expectedCompanyVersions.size() <= MAX_ACTIVE);
            } else {
                require(expectedCompanyVersions.isEmpty());
            }

            var ids = new HashSet<Long>();
            for (var expected : expectedCompanyVersions) {
                require(expected != null && ids.add(expected.companyId()));
            }
            expectedCompanyVersions = List.copyOf(expectedCompanyVersions);
        }

        public static Patch parse(JsonNode body) {
            object(body, Set.of("version"),
                    Set.of("job", "interviewDate", "jobScope",
                            "expectedCompanyVersions"));

            JobScope scope = JobScope.THIS_COMPANY;
            if (body.has("jobScope")) {
                try {
                    scope = JobScope.valueOf(string(body.get("jobScope")));
                } catch (IllegalArgumentException ex) {
                    throw invalid();
                }
            }

            List<Expected> expected = List.of();
            if (body.has("expectedCompanyVersions")) {
                require(scope == JobScope.ALL_ACTIVE);

                var array = body.get("expectedCompanyVersions");
                require(array.isArray() && !array.isEmpty()
                        && array.size() <= MAX_ACTIVE);

                var values = new java.util.ArrayList<Expected>();
                for (var item : array) {
                    object(item, Set.of("companyId", "version"), Set.of());
                    values.add(new Expected(
                            number(item.get("companyId"), MAX_ID),
                            number(item.get("version"), Long.MAX_VALUE)));
                }
                expected = List.copyOf(values);
            }

            return new Patch(
                    number(body.get("version"), Long.MAX_VALUE),
                    body.has("job")
                            ? PreparationCompany.job(body.get("job"))
                            : null,
                    body.has("interviewDate"),
                    date(body.get("interviewDate")),
                    scope,
                    expected);
        }
    }

    public record Paging(int page, int size) {
        public Paging {
            require(page >= 0 && size >= 1 && size <= 100);
        }

        public long offset() {
            return (long) page * size;
        }

        public long totalPages(long total) {
            return total / size + (total % size == 0 ? 0 : 1);
        }
    }

    public record Company(
            long companyId,
            String companyName,
            String industry,
            String logoUrl
    ) {}

    public record Job(
            Long jobId,
            String jobName,
            boolean isCustom
    ) {}

    public record QuizToday(
            Long quizSetId,
            int submittedCount,
            int totalCount,
            boolean completed
    ) {}

    // 면접 저장 기능 도입 시 실제 원장 집계로 교체한다. 현재 제출 이력은 없다.
    public record InterviewToday(
            LocalDate businessDate,
            int limit,
            int used,
            int remaining,
            Instant resetAt,
            int questionLimit,
            int questionUsed,
            int questionRemaining
    ) {}

    public record Item(
            Company company,
            Job job,
            LocalDate interviewDate,
            Long daysUntilInterview,
            String scheduleStatus,
            String learningMode,
            boolean selected,
            long version,
            QuizToday quizToday,
            InterviewToday interviewToday
    ) {}

    public record Page(
            List<Item> content,
            int page,
            int size,
            long totalElements,
            long totalPages,
            boolean hasNext
    ) {}

    public record Changed(List<Item> companies) {}

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

    private static JobInput job(JsonNode node) {
        object(node, Set.of(), Set.of("jobId", "customName"));

        var id = node.get("jobId");
        var custom = node.get("customName");

        return new JobInput(
                id == null || id.isNull() ? null : number(id, MAX_ID),
                custom == null || custom.isNull() ? null : string(custom));
    }

    private static LocalDate date(JsonNode node) {
        if (node == null || node.isNull()) return null;

        String value = string(node);
        require(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"));

        try {
            LocalDate date = LocalDate.parse(value);
            validDate(date);
            return date;
        } catch (DateTimeParseException ex) {
            throw invalid();
        }
    }

    private static void validDate(LocalDate date) {
        require(date == null
                || (date.getYear() >= 1000 && date.getYear() <= 9999));
    }

    private static long number(JsonNode node, long maximum) {
        require(node != null && node.isIntegralNumber()
                && node.canConvertToLong());

        long value = node.longValue();
        require(value >= 1 && value <= maximum);
        return value;
    }

    private static String string(JsonNode node) {
        require(node != null && node.isString());
        return node.stringValue();
    }

    private static void object(
            JsonNode node,
            Set<String> required,
            Set<String> optional
    ) {
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