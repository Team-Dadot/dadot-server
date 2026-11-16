package com.dadot.server.domain.quiz;

import com.dadot.server.domain.quiz.model.QuizGeneration;
import com.dadot.server.domain.quiz.model.QuizGeneration.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QuizQuestionEndingTest {
    private final JsonMapper mapper = new JsonMapper();
    private final Input input = new Input(List.of(new Section(1, 1, "사업보고서", "ANNUAL",
            LocalDate.of(2026, 3, 1), "사업", "PARAGRAPH", "연구개발에 투자합니다.")), List.of());

    @ParameterizedTest
    @ValueSource(strings = {"무엇", "이 회사의 주요 제품은 무엇", "무엇인", "어떤 것을 설명하",
            "주요 고객은 어디", "이 회사의 주요 제품은?", "이 회사가 집중하는 분야", "?"})
    void rejectsUnfinishedQuestionsAndMarksOnlyThemForRegeneration(String text) {
        var result = validate(question(text));
        assertTrue(result.questions().isEmpty());
        assertEquals(1, result.rejectedCount());
        assertEquals(1, result.incompleteCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"이 회사의 주요 제품은 무엇인가요?", "주요 고객은 어디인가요?",
            "어떤 사업을 확대하고 있나요?", "어떤 특징이 있습니까?", "가장 가까운 설명을 고르세요.",
            "어떤 제품을 만들까요?", "주요 사업은 무엇인가요", "주요 사업은 무엇인가요？"})
    void keepsNaturalCompleteQuestionsWithoutChangingTheirText(String text) {
        var result = validate(question(text));
        assertEquals(0, result.rejectedCount());
        assertEquals(0, result.incompleteCount());
        assertEquals(text, result.questions().getFirst().question());
    }

    @Test
    void badQuestionDoesNotRemoveItsValidSibling() {
        var result = QuizGeneration.validate(mapper.createArrayNode().add(question("무엇"))
                .add(question("이 회사가 투자하는 분야는 무엇인가요?")), 5, input, 2);
        assertEquals(1, result.questions().size());
        assertEquals(1, result.rejectedCount());
        assertEquals(1, result.incompleteCount());
        assertEquals("이 회사가 투자하는 분야는 무엇인가요?", result.questions().getFirst().question());
    }

    @Test
    void sourceErrorsAndOversizedQuestionsDoNotBecomeNewCompletionRetries() {
        var missingQuote = question("무엇");
        ((tools.jackson.databind.node.ObjectNode) missingQuote.path("evidence").get(0)).put("quote", "없는 원문");
        var sourceFailure = validate(missingQuote);
        assertEquals(1, sourceFailure.rejectedCount());
        assertEquals(0, sourceFailure.incompleteCount());
        var lengthFailure = validate(question("가".repeat(46)));
        assertEquals(1, lengthFailure.rejectedCount());
        assertEquals(0, lengthFailure.incompleteCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"가", "🙂"})
    void keepsAComplete45CodePointQuestionAndRejects46RatherThanSlicing(String character) {
        String ending = "무엇인가요?";
        String text = character.repeat(45 - ending.length()) + ending;
        var accepted = validate(question(text));
        assertEquals(text, accepted.questions().getFirst().question());
        assertEquals(1, validate(question(character + text)).rejectedCount());
    }

    private tools.jackson.databind.node.ObjectNode question(String text) {
        var value = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(
                QuizGenerationModelTest.question(1, 1, "연구개발에 투자합니다."));
        value.put("question", text);
        return value;
    }

    private Validated validate(JsonNode value) {
        return QuizGeneration.validate(mapper.createArrayNode().add(value), 5, input, 1);
    }
}
