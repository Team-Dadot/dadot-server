package com.dadot.server.domain.interview;

import com.dadot.server.domain.interview.model.InterviewEvaluation;
import com.dadot.server.domain.interview.model.InterviewGeneration.Failure;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

class InterviewEvaluationCriteriaOrderTest {
    private final JsonMapper mapper = new JsonMapper();

    private ObjectNode valid() {
        return (ObjectNode) mapper.valueToTree(
                EvaluationFixtures.output(EvaluationFixtures.input(), 20));
    }

    private ObjectNode reordered(ObjectNode source, int... order) {
        ObjectNode result = source.deepCopy();
        ArrayNode criteria = result.putArray("criteria");
        for (int index : order) {
            criteria.add(source.get("criteria").get(index).deepCopy());
        }
        return result;
    }

    @Test
    void acceptsAll24OrdersAndPreservesEachCriteriaContent() {
        ObjectNode source = valid();
        for (int i = 0; i < 4; i++) {
            ObjectNode criterion = (ObjectNode) source.get("criteria").get(i);
            criterion.put("score", i * 7);
            criterion.put("feedback", "항목별 피드백 " + i);
            criterion.put("reason", "항목별 근거 " + i);
        }

        var expected = InterviewEvaluation.validate(source, EvaluationFixtures.input());
        int checked = 0;

        for (int a = 0; a < 4; a++) {
            for (int b = 0; b < 4; b++) {
                for (int c = 0; c < 4; c++) {
                    for (int d = 0; d < 4; d++) {
                        if (a == b || a == c || a == d || b == c || b == d || c == d) continue;

                        var actual = InterviewEvaluation.validate(
                                reordered(source, a, b, c, d), EvaluationFixtures.input());

                        assertEquals(expected, actual);
                        assertEquals(42, InterviewEvaluation.total(actual));
                        checked++;
                    }
                }
            }
        }

        assertEquals(24, checked);
    }

    @Test
    void rejectsDuplicateMissingUnknownAndExtraCriteria() {
        invalid(reordered(valid(), 2, 0, 2, 3));
        invalid(reordered(valid(), 3, 1, 0));
        invalid(reordered(valid(), 3, 1, 0, 2, 0));

        ObjectNode node = reordered(valid(), 3, 1, 0, 2);
        ((ObjectNode) node.get("criteria").get(0)).put("criterionCode", "UNKNOWN");
        invalid(node);
    }

    @Test
    void reorderedCriteriaStillRequireValidScoresAndExactAnswerQuotes() {
        ObjectNode node = reordered(valid(), 3, 1, 0, 2);
        ((ObjectNode) node.get("criteria").get(0)).put("score", 26);
        invalid(node);

        node = reordered(valid(), 3, 1, 0, 2);
        ((ObjectNode) node.get("criteria").get(0)).put("answerQuote", "답변에 없는 인용문");
        invalid(node);
    }

    private void invalid(ObjectNode node) {
        Failure failure = assertThrows(Failure.class,
                () -> InterviewEvaluation.validate(node, EvaluationFixtures.input()));
        assertEquals("AI_INVALID_OUTPUT", failure.code());
        assertFalse(failure.retryable());
    }
}