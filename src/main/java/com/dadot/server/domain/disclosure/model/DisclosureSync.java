package com.dadot.server.domain.disclosure.model;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class DisclosureSync {
    private DisclosureSync() {}

    public enum ReportType { ANNUAL, HALF_YEAR, QUARTERLY }

    public record Request(LocalDate fromDate, LocalDate toDate, List<ReportType> reportTypes) {
        public Request {
            if (fromDate == null || toDate == null || fromDate.getYear() < 1900
                    || fromDate.isAfter(toDate) || ChronoUnit.DAYS.between(fromDate, toDate) >= 366
                    || reportTypes == null || reportTypes.isEmpty() || reportTypes.size() > 3
                    || reportTypes.stream().anyMatch(java.util.Objects::isNull)
                    || Set.copyOf(reportTypes).size() != reportTypes.size()) throw invalid();
            reportTypes = List.copyOf(EnumSet.copyOf(reportTypes));
        }

        public static Request parse(JsonNode node) {
            if (node == null || !node.isObject() || node.size() != 3
                    || !node.has("fromDate") || !node.has("toDate") || !node.has("reportTypes")) throw invalid();
            var types = node.get("reportTypes");
            if (!types.isArray() || types.isEmpty() || types.size() > 3) throw invalid();
            try {
                var values = new java.util.ArrayList<ReportType>();
                for (var type : types) {
                    if (!type.isTextual()) throw invalid();
                    values.add(ReportType.valueOf(type.asText()));
                }
                return new Request(date(node.get("fromDate")), date(node.get("toDate")), values);
            } catch (IllegalArgumentException ex) { throw invalid(); }
        }

        private static LocalDate date(JsonNode node) {
            if (node == null || !node.isTextual() || !node.asText().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
            try { return LocalDate.parse(node.asText()); }
            catch (DateTimeParseException ex) { throw invalid(); }
        }
    }

    // 원 요청은 불변으로 보존하고, 페이지 체크포인트만 같은 트랜잭션에서 갱신한다.
    public record Parameters(Request request, Cursor cursor) {
        public static Parameters initial(Request request) {
            return new Parameters(request, new Cursor(request.fromDate(), 1, null, null, 0));
        }
    }

    public record Cursor(LocalDate fromDate, int page, Integer totalCount, String previousHash, int retries) {
        public LocalDate end(LocalDate requestedEnd) {
            var end = fromDate.plusDays(29);
            return end.isAfter(requestedEnd) ? requestedEnd : end;
        }
    }

    public record Filing(String receiptNo, String reportName, ReportType reportType, LocalDate disclosedOn) {}
    public record Page(int totalCount, int totalPages, String hash, List<Filing> filings) {}
    public record Lease(String taskId, long companyId, String corpCode, String owner, long version, Parameters parameters) {}
    public record Accepted(String taskId, String status) {}
    public record Failure(String sourceReceiptNo, String code, String message) {}
    public record TaskError(String code, String message) {}

    public record Task(String taskId, String status, TaskError error, java.time.Instant createdAt,
                       java.time.Instant completedAt) {}

    public record TaskResult(Task task, long companyId, long collectedCount, long updatedCount,
                             long failedCount, List<Failure> failures) {}

    public record Company(long companyId, String companyName, String industry, String logoUrl) {}
    public record Section(long sectionId, String title, String text) {}

    public record Version(long disclosureId, long disclosureVersionId, Company company, String sourceReceiptNo,
                          String reportTitle, String reportType, LocalDate submittedDate, String sourceUrl,
                          String status, long version, List<Section> sections, Long correctionOf, String reviewNote,
                          Long reviewedBy, java.time.Instant reviewedAt, java.time.Instant updatedAt) {
        public Version withSections(List<Section> value) {
            return new Version(disclosureId, disclosureVersionId, company, sourceReceiptNo, reportTitle, reportType,
                    submittedDate, sourceUrl, status, version, List.copyOf(value), correctionOf, reviewNote, reviewedBy, reviewedAt, updatedAt);
        }
    }

    public record Result(List<Version> content, int page, int size, long totalElements, long totalPages,
                         boolean hasNext, TaskResult syncTask) {}

    public record Search(int page, int size, Long companyId, String status, String syncTaskId) {
        public Search {
            if (page < 0 || size < 1 || size > 100 || (companyId != null && (companyId < 1 || companyId > 9_007_199_254_740_991L))
                    || (status != null && !Arrays.asList("DRAFT", "IN_REVIEW", "APPROVED", "PUBLISHED", "SUSPENDED", "SUPERSEDED").contains(status))) throw invalid();
            if (syncTaskId != null) uuid(syncTaskId);
        }
    }

    public static String uuid(String value) {
        try {
            if (value == null || !java.util.UUID.fromString(value).toString().equals(value)) throw invalid();
            return value;
        } catch (IllegalArgumentException ex) { throw invalid(); }
    }

    public static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_INPUT);
    }
}