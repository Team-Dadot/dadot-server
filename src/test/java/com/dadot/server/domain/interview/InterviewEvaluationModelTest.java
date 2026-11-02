package com.dadot.server.domain.interview;

import com.dadot.server.domain.interview.model.InterviewEvaluation;
import com.dadot.server.domain.interview.model.InterviewEvaluation.*;
import com.dadot.server.domain.interview.model.InterviewGeneration.Failure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

class InterviewEvaluationModelTest {
    private final JsonMapper mapper = new JsonMapper();
    private ObjectNode valid() { return (ObjectNode) mapper.valueToTree(EvaluationFixtures.output(EvaluationFixtures.input(), 20)); }

    @Test
    void acceptsGroundedOutputAndServerSumsScores() {
        Generated value = InterviewEvaluation.validate(valid(), EvaluationFixtures.input());
        assertEquals(80, InterviewEvaluation.total(value));
        assertEquals(21, value.relatedMaterials().getFirst().materialVersionId());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 26, 100, Integer.MAX_VALUE})
    void rejectsScoresOutsideRubric(int score) {
        ObjectNode node = valid();
        ((ObjectNode) node.get("criteria").get(0)).put("score", score);
        invalid(node);
    }

    @Test
    void rejectsFractionalScoresMissingOrDuplicateCriteriaAndForgedTotals() {
        ObjectNode node = valid();
        ((ObjectNode) node.get("criteria").get(0)).put("score", 20.5); invalid(node);
        node = valid(); ((ArrayNode) node.get("criteria")).remove(0); invalid(node);
        node = valid(); ((ObjectNode) node.get("criteria").get(1)).put("criterionCode", "COMPANY_UNDERSTANDING"); invalid(node);
        node = valid(); node.put("totalScore", 100); invalid(node);
    }

    @ParameterizedTest
    @ValueSource(strings = {"summaryFeedback", "overallFeedback", "feedback", "reason", "answerQuote", "excerpt", "connectionReason", "strengths", "improvements"})
    void rejectsEveryTextBoundaryWithoutTruncating(String field) {
        ObjectNode node = valid();
        switch (field) {
            case "summaryFeedback" -> node.put(field, "가".repeat(31));
            case "overallFeedback" -> node.put(field, "가".repeat(301));
            case "feedback", "reason" -> ((ObjectNode) node.get("criteria").get(0)).put(field, "가".repeat(151));
            case "answerQuote" -> ((ObjectNode) node.get("criteria").get(0)).put(field, "가".repeat(201));
            case "excerpt", "connectionReason" -> ((ObjectNode) node.get("relatedMaterials").get(0)).put(field, "가".repeat(201));
            case "strengths" -> node.putArray(field).add("가".repeat(121));
            case "improvements" -> node.putArray(field).add("가".repeat(181));
            default -> fail();
        }
        invalid(node);
    }

    @Test
    void codePointsAllowSupplementaryCharactersAtExactLimit() {
        ObjectNode node = valid(); node.put("summaryFeedback", "😀".repeat(30));
        assertEquals(30, InterviewEvaluation.validate(node, EvaluationFixtures.input()).summaryFeedback().codePointCount(0, 60));
        node.put("summaryFeedback", "😀".repeat(31)); invalid(node);
    }

    @Test
    void rejectsBlankControlAndBrokenUnicode() {
        for (String text : new String[]{" ", "a\u0000", "a\uD800"}) {
            ObjectNode node = valid(); node.put("summaryFeedback", text); invalid(node);
        }
    }

    @Test
    void cannotCreditExperienceTextThatWasNeverSaidInAnswer() {
        ObjectNode node = valid();
        ((ObjectNode) node.get("criteria").get(0)).put("answerQuote", "30%"); invalid(node);
    }

    @Test
    void rejectsForeignMaterialVersionsAlteredQuotesAndDuplicateLinks() {
        ObjectNode node = valid();
        ((ObjectNode) node.get("relatedMaterials").get(0)).put("materialVersionId", 999); invalid(node);
        node = valid(); ((ObjectNode) node.get("relatedMaterials").get(0)).put("excerpt", "없는 원문"); invalid(node);
        node = valid(); ((ArrayNode) node.get("relatedMaterials")).add(node.get("relatedMaterials").get(0)); invalid(node);
    }

    @Test
    void listsHaveMaximumTwoAndCannotRepeatSameAdvice() {
        ObjectNode node = valid(); node.putArray("strengths").add("하나").add("둘").add("셋"); invalid(node);
        node = valid(); node.putArray("improvements").add("같은 말").add("같은 말"); invalid(node);
        node = valid(); ((ArrayNode) node.get("relatedMaterials")).add(node.get("relatedMaterials").get(0)).add(node.get("relatedMaterials").get(0)); invalid(node);
    }

    @Test
    void noExperienceAcceptsEmptyLinksAndDoesNotInventPersonalMaterials() {
        Input source = EvaluationFixtures.input();
        Input empty = new Input(source.companyName(), source.job(), source.question(), source.answer(), source.evidence(), java.util.List.of(), source.rubric());
        assertTrue(InterviewEvaluation.validate(mapper.valueToTree(EvaluationFixtures.output(empty, 16)), empty).relatedMaterials().isEmpty());
        assertThrows(Failure.class, () -> InterviewEvaluation.validate(valid(), empty));
        assertFalse(empty.toString().contains(source.answer()));
    }

    private void invalid(JsonNode value) {
        assertEquals("AI_INVALID_OUTPUT", assertThrows(Failure.class,
                () -> InterviewEvaluation.validate(value, EvaluationFixtures.input())).code());
    }
}