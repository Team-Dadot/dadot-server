package com.dadot.server.domain.quiz;

import com.dadot.server.domain.quiz.model.QuizGeneration;
import com.dadot.server.domain.quiz.model.QuizGeneration.*;
import com.dadot.server.domain.quiz.support.QuizGenerationProperties;
import com.dadot.server.domain.quiz.support.QuizSourcePlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QuizGenerationModelTest {
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void plansEveryBlockWithoutTruncationAndInterleavesAllThreeReports() {
        var reports = new ArrayList<List<Section>>();
        long id = 0;
        for (int report = 1; report <= 3; report++) {
            var rows = new ArrayList<Section>();
            for (int i = 0; i < 83; i++) rows.add(section(++id, report, "가".repeat(i == 4 ? 8192 : 701)));
            reports.add(rows);
        }
        var inputs = new QuizSourcePlanner().plan(reports, 500);
        var actual = inputs.stream().flatMap(b -> b.primary().stream()).toList();
        assertEquals(249, actual.size());
        assertEquals(249, actual.stream().map(Section::sectionId).distinct().count());
        assertEquals(3, inputs.getFirst().primary().stream().map(Section::disclosureVersionId).distinct().count());
        assertTrue(inputs.stream().allMatch(b -> b.primary().size() <= 40));
        assertTrue(inputs.stream().allMatch(b -> b.all().stream().mapToInt(s -> s.text().length()).sum() <= 32768));
        for (var original : reports.stream().flatMap(List::stream).toList()) {
            assertEquals(original, actual.stream().filter(s -> s.sectionId() == original.sectionId()).findFirst().orElseThrow());
        }
    }

    @Test
    void refusesExcessivePlansRatherThanSilentlyDroppingTheRemainder() {
        var rows = java.util.stream.IntStream.range(1, 11).mapToObj(i -> section(i, 1, "가".repeat(8192))).toList();
        assertThrows(RuntimeException.class, () -> new QuizSourcePlanner().plan(List.of(rows), 1));
    }

    @Test
    void normalizesUnorderedSourceIdsAndUppercaseUuid() {
        var request = Request.parse(mapper.readTree("{\"disclosureVersionIds\":[3,1,2]}"));
        assertEquals(List.of(1L, 2L, 3L), request.disclosureVersionIds());
        assertEquals(100, request.questionCount());
        String id = "F8F49B94-B7C4-4C20-8A22-F0016C123456";
        assertEquals(id.toLowerCase(java.util.Locale.ROOT), QuizGeneration.uuid(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"disclosureVersionIds\":null}", "{\"disclosureVersionIds\":[]}",
            "{\"disclosureVersionIds\":[1,1]}", "{\"disclosureVersionIds\":[1,2,3,4]}",
            "{\"disclosureVersionIds\":[0]}", "{\"disclosureVersionIds\":[1],\"questionCount\":101}",
            "{\"disclosureVersionIds\":[1],\"questionCount\":1.5}",
            "{\"disclosureVersionIds\":[1],\"unknown\":true}"
    })
    void rejectsInvalidRequests(String body) {
        assertThrows(RuntimeException.class, () -> Request.parse(mapper.readTree(body)));
    }

    @Test
    void rejectsInventedQuotesAndUnprovidedSourceIdsWhileKeepingOtherValidQuestions() {
        var input = new Input(List.of(section(1, 1, "연구개발에 투자합니다.")), List.of());
        var rows = mapper.createArrayNode();
        rows.add(mapper.valueToTree(question(1, 1, "연구개발에 투자합니다.")));
        rows.add(mapper.valueToTree(question(1, 1, "연구개발을 중단했습니다.")));
        rows.add(mapper.valueToTree(question(1, 2, "연구개발에 투자합니다.")));
        var result = QuizGeneration.validate(rows, 5, input, 3);
        assertEquals(1, result.questions().size());
        assertEquals(2, result.rejectedCount());
        assertEquals(5, result.questions().getFirst().companyId());
    }

    @Test
    void rejectsDuplicateOptionsEvenIfSpacingOrCompatibilityCharactersDiffer() {
        var value = mapper.valueToTree(question(1, 1, "연구개발에 투자합니다."));
        ((tools.jackson.databind.node.ObjectNode) value.path("options").get(1)).put("text", "미 래 기술 확보");
        var rows = mapper.createArrayNode().add(value);
        assertEquals(1, QuizGeneration.validate(rows, 5,
                new Input(List.of(section(1, 1, "연구개발에 투자합니다.")), List.of()), 1).rejectedCount());
    }

    @Test
    void requiresPrimaryEvidenceInsteadOfOnlyRepeatedContext() {
        var input = new Input(List.of(section(1, 1, "핵심 본문")), List.of(section(2, 1, "연구개발에 투자합니다.")));
        var rows = mapper.createArrayNode().add(mapper.valueToTree(question(2, 1, "연구개발에 투자합니다.")));
        assertEquals(1, QuizGeneration.validate(rows, 5, input, 1).rejectedCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u00a0\u3000", "\uD800", "\uDC00", "a\u0000b"})
    void rejectsInvisibleOnlyOrMalformedUnicode(String value) {
        assertThrows(RuntimeException.class, () -> QuizGeneration.validText(value, 60));
    }

    @Test
    void countsKoreanAndSupplementaryCharactersByCodePoint() {
        assertDoesNotThrow(() -> QuizGeneration.validText("😀".repeat(60), 60));
        assertThrows(RuntimeException.class, () -> QuizGeneration.validText("😀".repeat(61), 60));
    }

    @Test
    void propertyRepresentationNeverIncludesApiKey() {
        var properties = new QuizGenerationProperties(true, false, "gpt-6-luna", "secret-".repeat(10), 4096, 3000000, 500);
        assertFalse(properties.toString().contains("secret-"));
    }

    private static Section section(long id, long report, String text) {
        return new Section(id, report, "보고서" + report, "ANNUAL", LocalDate.of(2026, 3, 1),
                "연구개발활동", "PARAGRAPH", text);
    }

    public static Map<String, Object> question(long section, long report, String quote) {
        return Map.of("question", "기업 공시에 따르면 연구개발 투자는 어떤 방향으로 이루어지나요?",
                "options", List.of(Map.of("position", 1, "text", "미래 기술 확보"),
                        Map.of("position", 2, "text", "설비 투자 중단"), Map.of("position", 3, "text", "연구 인력 감축"),
                        Map.of("position", 4, "text", "관련 정보 없음")),
                "correctOptionPosition", 1, "explanation", "공시는 연구개발에 투자하고 있다고 설명합니다.",
                "evidence", List.of(Map.of("sectionId", section, "disclosureVersionId", report, "quote", quote)));
    }
}