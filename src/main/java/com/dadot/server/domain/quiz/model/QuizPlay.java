package com.dadot.server.domain.quiz.model;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public final class QuizPlay {

    public static final int SIZE = 5;

    private QuizPlay() {}

    public record Answer(long itemId, long selectedOptionId) {
        public Answer {
            QuizBank.id(itemId);
            QuizBank.id(selectedOptionId);
        }

        public static Answer parse(JsonNode body) {
            if (body == null || !body.isObject() || body.size() != 2
                    || !body.propertyNames().equals(Set.of("itemId", "selectedOptionId"))) {
                throw QuizBank.invalid();
            }
            return new Answer(number(body.get("itemId")), number(body.get("selectedOptionId")));
        }

        private static long number(JsonNode node) {
            if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
                throw QuizBank.invalid();
            }
            return QuizBank.id(node.longValue());
        }
    }

    public record Summary(long quizSetId, long companyId, LocalDate businessDate,
                          String status, int submittedCount, int totalCount,
                          Integer nextItemPosition, String learningMode) {}

    public record Prepared(int httpStatus, Summary body) {}

    public record Option(long optionId, int position, String text) {}

    public record Question(long itemId, long questionId, long questionVersionId,
                           int position, String question, List<Option> options,
                           boolean isReview) {}

    public record SavedAnswer(long itemId, long selectedOptionId, Instant submittedAt) {}

    public record Detail(long quizSetId, long companyId, LocalDate businessDate,
                         String status, int submittedCount, int totalCount,
                         Integer nextItemPosition, String learningMode,
                         Question currentItem, List<SavedAnswer> savedAnswers) {}

    public record Submitted(long answerId, int submittedCount,
                            Integer nextItemPosition, String status) {}

    public record Accuracy(int firstAnsweredCount, int firstCorrectCount, Integer percent) {
        public static Accuracy of(int total, int correct) {
            return new Accuracy(total, correct,
                    total == 0 ? null : (int) Math.round(correct * 100.0 / total));
        }
    }

    public record Company(long companyId, String companyName, String industry, String logoUrl) {}

    public record Evidence(long disclosureId, long disclosureVersionId,
                           String reportTitle, String sourceUrl, String sectionTitle,
                           String quote, boolean isSuperseded) {}

    public record ResultItem(Question question, long answerId, long selectedOptionId,
                             long correctOptionId, boolean isCorrect, Instant submittedAt,
                             String explanation, List<Evidence> evidence,
                             String revisionNotice) {}

    public record Result(long quizSetId, Company company, LocalDate businessDate,
                         int correctCount, int totalCount, Accuracy accuracyBefore,
                         Accuracy accuracyAfter, List<ResultItem> items) {}

    public static BusinessException fail(ErrorCode code) {
        return new BusinessException(code);
    }
}