package com.dadot.server.domain.interview;

import com.dadot.server.domain.interview.model.InterviewSubmission.SaveDraft;
import com.dadot.server.domain.interview.model.InterviewSubmission.Submit;
import com.dadot.server.global.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class InterviewSubmissionModelTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{}", "{\"text\":\"a\"}",
            "{\"text\":null,\"version\":null}", "{\"text\":1,\"version\":null}",
            "{\"text\":\"a\",\"version\":0}", "{\"text\":\"a\",\"version\":1.0}",
            "{\"text\":\"a\",\"version\":\"1\"}", "{\"text\":\"a\",\"version\":9007199254740992}",
            "{\"text\":\"a\",\"version\":null,\"materialIds\":[]}"})
    void invalidDraft(String json) {
        assertThrows(BusinessException.class, () -> SaveDraft.parse(mapper.readTree(json)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{}", "{\"text\":\"\"}", "{\"text\":\"  \\n\"}",
            "{\"text\":false}", "{\"text\":null}", "{\"text\":\"a\",\"draftVersion\":-1}",
            "{\"text\":\"a\",\"draftVersion\":1.5}", "{\"text\":\"a\",\"draftVersion\":true}",
            "{\"text\":\"a\",\"draftVersion\":9007199254740992}",
            "{\"text\":\"a\",\"experienceIds\":[]}"})
    void invalidSubmission(String json) {
        assertThrows(BusinessException.class, () -> Submit.parse(mapper.readTree(json)));
    }

    @Test
    void countsCodePointsAndPreservesOriginalText() {
        String text = "😀".repeat(2000);
        assertEquals(text, new SaveDraft(text, null).text());
        assertEquals(text, new Submit(text, null, false).text());
        assertThrows(BusinessException.class, () -> new SaveDraft(text + "a", null));
        assertThrows(BusinessException.class, () -> new Submit(text + "a", null, false));
        assertEquals("  답변\n\t", new Submit("  답변\n\t", null, false).text());
        assertEquals("", new SaveDraft("", null).text());
    }

    @Test
    void rejectsBrokenUnicodeAndUnsupportedControls() {
        for (String text : new String[]{"a\u0000", "a\uD800", "a\uDC00", "a\u007F"}) {
            assertThrows(BusinessException.class, () -> new SaveDraft(text, null));
            assertThrows(BusinessException.class, () -> new Submit(text, null, false));
        }
    }

    @Test
    void keepsOmittedAndExplicitNullDistinctForIdempotency() {
        Submit omitted = Submit.parse(mapper.readTree("{\"text\":\"답변\"}"));
        Submit explicit = Submit.parse(mapper.readTree("{\"draftVersion\":null,\"text\":\"답변\"}"));
        assertFalse(omitted.canonicalBody().containsKey("draftVersion"));
        assertTrue(explicit.canonicalBody().containsKey("draftVersion"));
        assertNull(explicit.canonicalBody().get("draftVersion"));
        assertNotEquals(mapper.writeValueAsString(omitted.canonicalBody()),
                mapper.writeValueAsString(explicit.canonicalBody()));
    }
}