package com.dadot.server.domain.interview.model;

import com.dadot.server.domain.interview.model.InterviewGeneration.Attempt;

import java.time.Instant;
import java.util.List;

public final class InterviewReview {
    private InterviewReview() {}

    public record Paging(int page, int size) {
        public Paging {
            if (page < 0 || size < 1 || size > 100) throw InterviewPolicy.invalid();
        }

        public long offset() { return (long) page * size; }
        public long totalPages(long total) { return total / size + (total % size == 0 ? 0 : 1); }
        public boolean hasNext(long total) { return offset() + size < total; }
    }

    public record Page<T>(List<T> content, int page, int size, long totalElements,
                          long totalPages, boolean hasNext) {
        public static <T> Page<T> of(List<T> content, Paging paging, long total) {
            return new Page<>(List.copyOf(content), paging.page(), paging.size(), total,
                    paging.totalPages(total), paging.hasNext(total));
        }
    }

    public record Question(long questionId, String question, Instant createdAt, boolean hasDraft,
                           Attempt latestAttempt, Integer latestScorePercent, Boolean completionDecision,
                           int questionUsed, int questionRemaining, String readinessStatus) {}
}