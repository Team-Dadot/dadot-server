package com.dadot.server.domain.interview;

import com.dadot.server.domain.interview.model.InterviewGeneration;
import com.dadot.server.domain.interview.model.InterviewGeneration.*;
import com.dadot.server.domain.interview.support.InterviewSourcePlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InterviewGenerationModelTest {
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void fingerprintsUseNfcAndWhitespaceButPreserveNumbersAndPunctuation() {
        assertEquals(
                InterviewGeneration.fingerprint("  가\n 나  "),
                InterviewGeneration.fingerprint("가 나")
        );
        assertEquals(
                InterviewGeneration.fingerprint("가"),
                InterviewGeneration.fingerprint("가")
        );
        assertNotEquals(
                InterviewGeneration.fingerprint("3.5%"),
                InterviewGeneration.fingerprint("35%")
        );
    }

    @Test
    void validatesSourceVersionSectionAndExactQuoteTogether() {
        var input = new Input(
                "회사",
                new Job(null, "영업", true),
                List.of(),
                List.of(new Section(10, 20, "사업", "해외 판매 채널 확대")),
                List.of(),
                List.of()
        );

        var good = mapper.readTree("""
                {
                  "question":"해외영업 전략을 설명해 주세요.",
                  "evidence":[
                    {
                      "disclosureVersionId":20,
                      "sectionId":10,
                      "quote":"해외 판매"
                    }
                  ]
                }
                """);

        assertEquals(
                1,
                InterviewGeneration.validate(good, input).evidence().size()
        );

        for (String replacement : List.of(
                good.toString().replace("20", "21"),
                good.toString().replace("해외 판매", "존재하지 않는 원문"),
                good.toString().replace("10", "11")
        )) {
            assertThrows(
                    Failure.class,
                    () -> InterviewGeneration.validate(
                            mapper.readTree(replacement), input
                    )
            );
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 49, 50, 80, 99, 100})
    void questionLengthAcceptsUpTo100CodePointsWithoutForcingRecommendedMinimum(int length) {
        String question = "가".repeat(length);
        assertEquals(question, InterviewGeneration.validate(lengthOutput(question, "근거"),
                lengthInput("근거")).question());
    }

    @ParameterizedTest
    @ValueSource(ints = {101, 140, 160, 500})
    void questionLongerThan100IsRejectedRatherThanTruncated(int length) {
        assertThrows(Failure.class, () -> InterviewGeneration.validate(
                lengthOutput("가".repeat(length), "근거"), lengthInput("근거")));
    }

    @Test
    void questionLengthCountsSpacesAndSupplementaryCodePoints() {
        String question = "가 ".repeat(49) + "🙂?";
        assertEquals(100, question.codePointCount(0, question.length()));
        assertEquals(question, InterviewGeneration.validate(lengthOutput(question, "근거"),
                lengthInput("근거")).question());
        assertThrows(Failure.class, () -> InterviewGeneration.validate(
                lengthOutput(question + " ", "근거"), lengthInput("근거")));
    }

    @Test
    void evidenceQuoteStillAccepts500AndRejects501Characters() {
        String quote = "가".repeat(500);
        assertEquals(quote, InterviewGeneration.validate(lengthOutput("핵심 질문?", quote),
                lengthInput(quote)).evidence().getFirst().quote());
        String oversized = quote + "나";
        assertThrows(Failure.class, () -> InterviewGeneration.validate(
                lengthOutput("핵심 질문?", oversized), lengthInput(oversized)));
    }

    private Input lengthInput(String source) {
        return new Input("회사", new Job(null, "영업", true), List.of(),
                List.of(new Section(10, 20, "사업", source)), List.of(), List.of());
    }

    private tools.jackson.databind.JsonNode lengthOutput(String question, String quote) {
        return mapper.readTree(mapper.writeValueAsString(java.util.Map.of(
                "question", question,
                "evidence", List.of(java.util.Map.of(
                        "disclosureVersionId", 20, "sectionId", 10, "quote", quote)))));
    }

    @Test
    void plannerScansEachReportAndKeepsOriginalAdjacentContext() {
        var reports = List.of(
                List.of(
                        new Section(1, 1, "재무", "단위: 백만원"),
                        new Section(2, 1, "해외영업", "해외 채널 확대")
                ),
                List.of(new Section(3, 2, "시장", "상반기 판매")),
                List.of(new Section(4, 3, "전략", "최근 분기 판매"))
        );

        var selected = new InterviewSourcePlanner().select(
                reports, "해외영업", List.of()
        );

        assertEquals(
                3,
                selected.stream()
                        .map(Section::disclosureVersionId)
                        .distinct().count()
        );

        assertTrue(selected.contains(reports.getFirst().getFirst()));

        assertEquals(
                "해외 채널 확대",
                selected.stream()
                        .filter(s -> s.sectionId() == 2)
                        .findFirst().orElseThrow().content()
        );
    }

    @Test
    void dailyResetUsesKoreaMidnight() {
        Daily before = Daily.of(
                Instant.parse("2026-10-31T14:59:59Z"), 4, 3
        );

        assertEquals("2026-10-31", before.businessDate().toString());
        assertEquals(
                Instant.parse("2026-10-31T15:00:00Z"),
                before.resetAt()
        );
        assertEquals(5, before.remaining());
        assertEquals(0, before.questionRemaining());

        assertEquals(
                "2026-11-01",
                Daily.of(
                        Instant.parse("2026-10-31T15:00:00Z"), 0, 0
                ).businessDate().toString()
        );
    }
}