package com.dadot.server.domain.disclosure.model;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import tools.jackson.databind.JsonNode;

import java.util.Set;

public final class DisclosureReview {

    public static final long MAX_ID = 9_007_199_254_740_991L;

    private static final Set<String> FIELDS = Set.of(
            "expectedVersion",
            "disclosureVersionId",
            "status",
            "correctionOf",
            "reason"
    );

    private DisclosureReview() {}

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

    public record Request(
            long expectedVersion,
            long disclosureVersionId,
            Status status,
            boolean correctionSpecified,
            Long correctionOf,
            String reason
    ) {
        public Request {
            id(expectedVersion);
            id(disclosureVersionId);

            if (correctionOf != null) {
                id(correctionOf);
            }

            if (status == null
                    || reason == null
                    || reason.isBlank()
                    || reason.codePointCount(0, reason.length()) > 1000) {
                throw DisclosureSync.invalid();
            }
        }

        public static Request parse(JsonNode body) {
            if (body == null
                    || !body.isObject()
                    || !body.has("expectedVersion")
                    || !body.has("disclosureVersionId")
                    || !body.has("status")
                    || !body.has("reason")) {
                throw DisclosureSync.invalid();
            }

            for (String name : body.propertyNames()) {
                if (!FIELDS.contains(name)) {
                    throw DisclosureSync.invalid();
                }
            }

            if (!body.get("status").isTextual()
                    || !body.get("reason").isTextual()) {
                throw DisclosureSync.invalid();
            }

            try {
                return new Request(
                        number(body.get("expectedVersion")),
                        number(body.get("disclosureVersionId")),
                        Status.valueOf(body.get("status").asText()),
                        body.has("correctionOf"),
                        !body.has("correctionOf")
                                || body.get("correctionOf").isNull()
                                ? null
                                : number(body.get("correctionOf")),
                        body.get("reason").asText()
                );

            } catch (IllegalArgumentException ex) {
                throw DisclosureSync.invalid();
            }
        }

        private static long number(JsonNode value) {
            if (value == null
                    || !value.isIntegralNumber()
                    || !value.canConvertToLong()) {
                throw DisclosureSync.invalid();
            }

            return id(value.longValue());
        }
    }

    public record Result(
            DisclosureSync.Version disclosure,
            long affectedQuizCount,
            long affectedInterviewCount
    ) {}

    public static long id(long value) {
        if (value < 1 || value > MAX_ID) {
            throw DisclosureSync.invalid();
        }

        return value;
    }

    public static BusinessException transition() {
        return new BusinessException(
                ErrorCode.INVALID_STATUS_TRANSITION
        );
    }
}