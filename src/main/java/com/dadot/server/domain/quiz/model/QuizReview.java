package com.dadot.server.domain.quiz.model;

import java.time.Instant;
import java.util.List;

public final class QuizReview {

    private QuizReview() {}

    public enum Filter {
        ALL, INCORRECT
    }

    public record Paging(int page, int size) {
        public Paging {
            if (page < 0 || size < 1 || size > 100) {
                throw QuizBank.invalid();
            }
        }

        public long offset() {
            return (long) page * size;
        }

        public long totalPages(long total) {
            return total / size + (total % size == 0 ? 0 : 1);
        }

        public boolean hasNext(long total) {
            return offset() + size < total;
        }
    }

    public record Search(long companyId, Filter filter, Paging paging) {
        public Search {
            QuizBank.id(companyId);
            if (filter == null || paging == null) {
                throw QuizBank.invalid();
            }
        }
    }

    public record CompanyItem(
            QuizPlay.Company company,
            boolean isActive,
            long quizQuestionCount,
            long interviewQuestionCount
    ) {}

    public record CompanyPage(
            List<CompanyItem> content,
            int page,
            int size,
            long totalElements,
            long totalPages,
            boolean hasNext
    ) {}

    public record QuestionItem(
            long questionId,
            long questionVersionId,
            String question,
            long latestAnswerId,
            boolean isCorrect,
            Instant lastAnsweredAt
    ) {}

    public record QuestionPage(
            List<QuestionItem> content,
            int page,
            int size,
            long totalElements,
            long totalPages,
            boolean hasNext,
            long allCount,
            long incorrectCount
    ) {}
}