package com.dadot.server.domain.quiz;

import com.dadot.server.domain.quiz.model.QuizBank;
import com.dadot.server.domain.quiz.model.QuizGeneration;
import com.dadot.server.domain.quiz.model.QuizGeneration.Input;
import com.dadot.server.domain.quiz.model.QuizGeneration.Section;
import com.dadot.server.domain.quiz.model.QuizGeneration.Validated;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class QuizGenerationNumericRecallTest {
    private static final String SOURCE = "2025년 보고서는 5G 서비스와 3M 제품을 설명하며 연간 2,511만 명을 수송했다고 기록합니다.";
    private static final String BUSINESS_QUESTION = "공시에서 설명하는 주요 서비스는 무엇인가요?";
    private static final List<String> BUSINESS_OPTIONS = List.of("항공 여객 운송", "통신망 구축", "소프트웨어 개발", "식품 제조");
    private final JsonMapper mapper = new JsonMapper();
    private final Input input = new Input(List.of(new Section(1, 1, "사업보고서", "ANNUAL",
            LocalDate.of(2026, 3, 1), "사업의 내용", "PARAGRAPH", SOURCE)), List.of());

    @Test
    void rejectsPassengerCount() {
        var result = validate(question("대한항공은 연간 몇 만 명의 승객을 수송했나요?",
                List.of("1,541만 명", "2,511만 명", "165만 명", "106만 명")));
        assertTrue(result.questions().isEmpty());
        assertEquals(1, result.rejectedCount());
        assertEquals(0, result.incompleteCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "공시에 따른 직원 수는 몇 명인가요?",
            "공시에 따른 매출액은 얼마인가요?",
            "회사가 설립된 연도는 무엇인가요?",
            "회사의 상장은 언제 이루어졌나요?",
            "영업이익은 어느 수준인가요?",
            "해외 매출 비중은 몇 퍼센트인가요?",
            "항공기는 몇 대를 보유하고 있나요?",
            "매출액을 더하면 얼마인가요?",
            "공시에 제시된 수치는 무엇인가요?"
    })
    void rejectsExplicitNumericRecallEvenWithWordedOptions(String text) {
        var result = validate(question(text, BUSINESS_OPTIONS));
        assertTrue(result.questions().isEmpty());
        assertEquals(1, result.rejectedCount());
    }

    static Stream<List<String>> quantities() {
        return Stream.of(
                List.of("1,541만 명", "2,511만 명", "165만 명", "106만 명"),
                List.of("3.5%", "35%", "약 12%", "20% 이상"),
                List.of("1,200억 원", "약 3조 원", "100만 원 미만", "2천만 원"),
                List.of("2021년", "2022년 말", "2023년", "2024년"),
                List.of("10~20%", "20~30%", "30~40%", "40~50%"),
                List.of("삼천만 명", "이천만 명", "천만 명", "오백만 명"),
                List.of("한 개", "두 개", "세 개", "네 개"),
                List.of("１５４１만 명", "２５１１만 명", "１６５만 명", "１０６만 명"),
                List.of("1\u00a0541만\u3000명", "2\u00a0511만 명", "165만 명", "106만 명"),
                List.of("10", "20", "30", "공시 없음"),
                List.of("10% 증가", "20% 증가", "10% 감소", "20% 감소"),
                List.of("10 KG", "20 KG", "30 KG", "40 KG"));
    }

    @ParameterizedTest
    @MethodSource("quantities")
    void rejectsNumericOptionsWithoutQuestionCue(List<String> options) {
        var result = validate(question("공시에 제시된 내용으로 맞는 것은 무엇인가요?", options));
        assertTrue(result.questions().isEmpty());
        assertEquals(1, result.rejectedCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "주요 서비스는 무엇인가요?",
            "2025년 공시에서 설명한 주요 사업은 무엇인가요?",
            "5G 서비스는 어떤 고객을 대상으로 하나요?",
            "3M 제품은 어떤 분야에서 사용되나요?",
            "설립 시기부터 주력한 사업은 무엇인가요?"
    })
    void acceptsBusinessQuestionsDespiteNumbersInNamesContextOrEvidence(String text) {
        var result = validate(question(text, BUSINESS_OPTIONS));
        assertEquals(1, result.questions().size());
        assertEquals(0, result.rejectedCount());
        assertEquals(text, result.questions().getFirst().question());
        assertEquals(SOURCE, result.questions().getFirst().evidence().getFirst().quote());
    }

    @Test
    void acceptsNamesContainingNumbersInsteadOfTreatingThemAsQuantities() {
        List<String> names = List.of("5G", "3M", "HBM3E", "B2B 플랫폼");
        var result = validate(question(BUSINESS_QUESTION, names));
        assertEquals(1, result.questions().size());
        assertEquals(names, result.questions().getFirst().options().stream().map(QuizBank.InputOption::text).toList());
    }

    @Test
    void keepsGoodQuestionsWhenNumericRecallIsInSameBatch() {
        var numeric = question("공시에 제시된 내용으로 맞는 것은 무엇인가요?",
                List.of("10%", "20%", "30%", "40%"));
        var good = question(BUSINESS_QUESTION, BUSINESS_OPTIONS);
        var rows = mapper.createArrayNode().add(numeric).add(good);
        var result = QuizGeneration.validate(rows, 5, input, 2);
        assertEquals(1, result.questions().size());
        assertEquals(BUSINESS_QUESTION, result.questions().getFirst().question());
        assertEquals(1, result.rejectedCount());
        assertEquals(0, result.incompleteCount());
    }

    @Test
    void preservesExistingOrManuallyWrittenBankContentContract() {
        var stored = question("대한항공은 연간 몇 만 명의 승객을 수송했나요?",
                List.of("1,541만 명", "2,511만 명", "165만 명", "106만 명"));
        stored.put("companyId", 5);
        assertEquals("2,511만 명", QuizBank.Content.parse(stored).options().get(1).text());
    }

    private Validated validate(ObjectNode question) {
        return QuizGeneration.validate(mapper.createArrayNode().add(question), 5, input, 1);
    }

    private ObjectNode question(String text, List<String> options) {
        return (ObjectNode) mapper.valueToTree(Map.of(
                "question", text,
                "options", IntStream.range(0, options.size())
                        .mapToObj(i -> Map.of("position", i + 1, "text", options.get(i))).toList(),
                "correctOptionPosition", 2,
                "explanation", "공시 원문에서 확인한 내용입니다.",
                "evidence", List.of(Map.of("sectionId", 1, "disclosureVersionId", 1, "quote", SOURCE))));
    }
}
