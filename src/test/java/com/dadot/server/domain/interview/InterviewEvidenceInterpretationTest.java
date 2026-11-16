package com.dadot.server.domain.interview;

import com.dadot.server.domain.interview.model.InterviewGeneration;
import com.dadot.server.domain.interview.model.InterviewGeneration.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InterviewEvidenceInterpretationTest {
    private final JsonMapper mapper = new JsonMapper();
    private static final String QUOTE = "회사는 해외 판매망을 확대할 계획입니다. 현지 유통사를 추가할 예정입니다.";
    private static final String SUMMARY = "이 회사는 해외 판매망을 넓힐 계획이에요. 현지 유통사를 추가하려는 내용이에요.";

    @Test
    void keepsOriginalQuoteAndSeparateInterpretationOnTheSameEvidence() {
        var generated = InterviewGeneration.validateComplete(validate(output(SUMMARY)));
        assertEquals(QUOTE, generated.evidence().getFirst().quote());
        assertEquals(SUMMARY, generated.evidence().getFirst().interpretation());
        assertEquals(10, generated.evidence().getFirst().sectionId());
        assertEquals(20, generated.evidence().getFirst().disclosureVersionId());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "이 회사는 해외 판매망을 넓힐 계획이에요.",
            "이 회사는 해외 판매망을 넓힐 계획이에요.현지 유통사를 추가하려는 내용이에요.",
            "이 회사는 해외 판매망을 넓힐 계획이에요.\n현지 유통사를 추가하려는 내용이에요.",
            "해외 판매망을 넓힐 계획입니다。현지 유통사를 추가할 예정입니다。"
    })
    void acceptsOneOrTwoCompleteSentencesWithoutChangingTheirText(String text) {
        assertEquals(text, validate(output(text)).evidence().getFirst().interpretation());
    }

    @ParameterizedTest
    @ValueSource(strings = {"가", "🙂"})
    void uses180UnicodeCodePointsRatherThanUtf16Length(String character) {
        String text = character.repeat(178) + "요.";
        assertEquals(text, validate(output(text)).evidence().getFirst().interpretation());
        Failure error = assertThrows(Failure.class, () -> validate(output(character + text)));
        assertEquals("EVIDENCE_INTERPRETATION_TOO_LONG", error.reason());
    }

    @ParameterizedTest
    @CsvSource({
            "' ',EVIDENCE_INTERPRETATION_EMPTY",
            "'해외 판매망을 확대할',EVIDENCE_INTERPRETATION_INCOMPLETE",
            "'해외 판매망을 넓혀요. 유통사를 늘려요. 판매 경로를 늘려요.',EVIDENCE_INTERPRETATION_SENTENCES",
            "'해외 판매망을 넓혀요.AI 제품을 공급해요.다른 제품도 만들어요.',EVIDENCE_INTERPRETATION_SENTENCES",
            "'어떤 회사인가요?',EVIDENCE_INTERPRETATION_INCOMPLETE"
    })
    void rejectsIncompleteOrExcessiveDescriptions(String text, String reason) {
        assertEquals(reason, assertThrows(Failure.class, () -> validate(output(text))).reason());
    }

    @Test
    void permitsLegacyMissingOrNullInterpretationsButRequiresThemForNewAiResponses() {
        var missing = output(SUMMARY);
        ((tools.jackson.databind.node.ObjectNode) missing.path("evidence").get(0)).remove("interpretation");
        var old = validate(missing);
        assertNull(old.evidence().getFirst().interpretation());
        assertEquals("EVIDENCE_INTERPRETATION_REQUIRED",
                assertThrows(Failure.class, () -> InterviewGeneration.validateComplete(old)).reason());
        var withNull = output(SUMMARY);
        ((tools.jackson.databind.node.ObjectNode) withNull.path("evidence").get(0)).putNull("interpretation");
        assertNull(validate(withNull).evidence().getFirst().interpretation());
    }

    @Test
    void inventedQuoteStillFailsBeforeTheInterpretationIsAccepted() {
        var value = output(SUMMARY);
        ((tools.jackson.databind.node.ObjectNode) value.path("evidence").get(0)).put("quote", "없는 원문");
        assertEquals("EVIDENCE_QUOTE_MISMATCH", assertThrows(Failure.class, () -> validate(value)).reason());
    }

    @Test
    void rejectsUnknownFieldsAndNonTextInterpretations() {
        var value = output(SUMMARY);
        var evidence = (tools.jackson.databind.node.ObjectNode) value.path("evidence").get(0);
        evidence.put("unexpected", "not accepted");
        assertEquals("JSON_STRUCTURE_INVALID", assertThrows(Failure.class, () -> validate(value)).reason());
        evidence.remove("unexpected");
        evidence.put("interpretation", 123);
        assertEquals("EVIDENCE_INTERPRETATION_EMPTY", assertThrows(Failure.class, () -> validate(value)).reason());
    }

    @Test
    void keepsEachInterpretationBoundToItsOwnSectionAndQuote() {
        var value = output(SUMMARY);
        ((tools.jackson.databind.node.ArrayNode) value.path("evidence")).add(mapper.valueToTree(Map.of(
                "disclosureVersionId", 21, "sectionId", 11, "quote", "직원을 교육합니다.",
                "interpretation", "이 회사는 직원 교육을 운영해요.")));
        var input = new Input("회사", new Job(null, "영업", true), List.of(), List.of(
                new Section(10, 20, "사업", QUOTE), new Section(11, 21, "교육", "직원을 교육합니다.")),
                List.of(), List.of());
        var checked = InterviewGeneration.validateComplete(InterviewGeneration.validate(value, input));
        assertEquals(SUMMARY, checked.evidence().get(0).interpretation());
        assertEquals("직원을 교육합니다.", checked.evidence().get(1).quote());
        assertEquals("이 회사는 직원 교육을 운영해요.", checked.evidence().get(1).interpretation());
    }

    private Generated validate(JsonNode value) {
        return InterviewGeneration.validate(value, new Input("회사", new Job(null, "영업", true), List.of(),
                List.of(new Section(10, 20, "사업", QUOTE)), List.of(), List.of()));
    }

    private JsonNode output(String interpretation) {
        return mapper.valueToTree(Map.of("question", "이 회사의 어떤 사업에 관심이 있나요?", "evidence", List.of(
                Map.of("disclosureVersionId", 20, "sectionId", 10, "quote", QUOTE,
                        "interpretation", interpretation))));
    }
}
