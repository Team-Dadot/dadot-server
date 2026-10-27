package com.dadot.server.domain.home.model;

import com.dadot.server.domain.company.model.PreparationCompany;
import com.dadot.server.domain.quiz.model.QuizPlay.Accuracy;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;

public final class Home {

    private Home() {}

    public record Profile(long userId, String nickname, String profileImageUrl) {}

    public record EvaluationSummary(
            long evaluatedQuestionCount,
            long processingQuestionCount,
            long failedQuestionCount,
            String policyStatus,
            Integer averageScorePercent,
            long cancelledQuestionCount
    ) {
        // 면접 저장·평가 기능을 연결할 때 실제 사용량·최신 답변 집계로 교체한다.
        public static EvaluationSummary unavailable() {
            return new EvaluationSummary(0, 0, 0, "NOT_CONFIGURED", null, 0);
        }
    }

    public record Dashboard(
            Profile profile,
            PreparationCompany.Item selectedCompany,
            Accuracy quizAccuracy,
            EvaluationSummary interviewEvaluationSummary,
            LocalDate businessDate,
            Instant nextResetAt,
            String nextLearningAction
    ) {}

    public record Preferences(long selectedCompanyId) {
        public Preferences {
            PreparationCompany.id(selectedCompanyId);
        }

        public static Preferences parse(JsonNode body) {
            if (body == null || !body.isObject() || body.size() != 1) {
                throw PreparationCompany.invalid();
            }
            JsonNode id = body.get("selectedCompanyId");
            if (id == null || !id.isIntegralNumber() || !id.canConvertToLong()) {
                throw PreparationCompany.invalid();
            }
            return new Preferences(id.longValue());
        }
    }
}