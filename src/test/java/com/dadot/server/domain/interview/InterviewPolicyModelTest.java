package com.dadot.server.domain.interview;

import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.interview.model.InterviewPolicy.*;
import com.dadot.server.global.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InterviewPolicyModelTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void requiresFourFixedCriteriaInOrderAndImmutableCopy() {
        var items = criteria();
        var content = new Create(Type.EVALUATION, new Scope(null, null), "평가 기준", items,
                InterviewPolicy.AGGREGATION, null);
        items.clear();
        assertEquals(4, content.criteria().size());
        assertThrows(UnsupportedOperationException.class, () -> content.criteria().clear());
        assertThrows(BusinessException.class, () -> new Create(Type.EVALUATION, content.scope(),
                content.rules(), List.of(content.criteria().getFirst()), InterviewPolicy.AGGREGATION, null));
        var reordered = new ArrayList<>(content.criteria());
        java.util.Collections.swap(reordered, 0, 1);
        assertThrows(BusinessException.class, () -> new Create(Type.EVALUATION, content.scope(),
                content.rules(), reordered, InterviewPolicy.AGGREGATION, null));
    }

    @Test
    void generationForbidsEvaluationSettings() {
        assertThrows(BusinessException.class, () -> new Create(Type.GENERATION,
                new Scope(null, null), "생성 지침", criteria(), null, null));
        assertThrows(BusinessException.class, () -> new Create(Type.GENERATION,
                new Scope(null, null), "생성 지침", List.of(), InterviewPolicy.AGGREGATION, null));
    }

    @Test
    void countsUnicodeCodePointsWithoutTruncation() {
        String limit = "😀".repeat(20_000);
        assertEquals(limit, new Create(Type.GENERATION, new Scope(null, null), limit,
                List.of(), null, null).rules());
        assertThrows(BusinessException.class, () -> new Create(Type.GENERATION,
                new Scope(null, null), limit + "a", List.of(), null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "[]",
            "{\"type\":\"GENERATION\",\"scope\":{\"jobId\":null},\"rules\":\"x\",\"criteria\":[],\"aggregationRule\":null}",
            "{\"type\":\"GENERATION\",\"scope\":{\"companyId\":1.0,\"jobId\":null},\"rules\":\"x\",\"criteria\":[],\"aggregationRule\":null}",
            "{\"type\":\"GENERATION\",\"scope\":{\"companyId\":null,\"jobId\":null},\"rules\":\" \",\"criteria\":[],\"aggregationRule\":null}",
            "{\"type\":\"GENERATION\",\"scope\":{\"companyId\":null,\"jobId\":null},\"rules\":\"x\",\"criteria\":[],\"aggregationRule\":null,\"materialTypes\":[\"COVER_LETTER\"]}"
    })
    void rejectsInvalidOrUnknownRequestFields(String json) {
        assertThrows(BusinessException.class, () -> Create.parse(mapper.readTree(json)));
    }

    @Test
    void eightyIsTheOnlyReadinessThreshold() {
        assertEquals(new Score(79, 79, false), InterviewPolicy.score(List.of(25, 25, 25, 4)));
        assertEquals(new Score(80, 80, true), InterviewPolicy.score(List.of(25, 25, 25, 5)));
        assertEquals(new Score(100, 100, true), InterviewPolicy.score(List.of(25, 25, 25, 25)));
        assertEquals(new Score(0, 0, false), InterviewPolicy.score(List.of(0, 0, 0, 0)));
        assertThrows(BusinessException.class, () -> InterviewPolicy.score(List.of(26, 0, 0, 0)));
        assertThrows(BusinessException.class, () -> InterviewPolicy.score(List.of(-1, 0, 0, 0)));
        assertThrows(BusinessException.class, () -> InterviewPolicy.score(List.of(25, 25, 25)));
        assertThrows(BusinessException.class, () -> InterviewPolicy.score(Arrays.asList(25, 25, 25, null)));
    }

    @Test
    void suspensionRequiresReviewAndSupersededIsTerminal() {
        assertTrue(Status.SUSPENDED.canChangeTo(Status.IN_REVIEW));
        assertFalse(Status.SUSPENDED.canChangeTo(Status.PUBLISHED));
        assertFalse(Status.DRAFT.canChangeTo(Status.PUBLISHED));
        for (Status target : Status.values()) assertFalse(Status.SUPERSEDED.canChangeTo(target));
        assertThrows(BusinessException.class, () -> new Patch(1, 1, Status.SUPERSEDED, "직접 변경"));
    }

    private static ArrayList<Criterion> criteria() {
        var result = new ArrayList<Criterion>();
        for (int i = 0; i < 4; i++) result.add(new Criterion(InterviewPolicy.CODES.get(i),
                InterviewPolicy.LABELS.get(i), 25, "0~25점 판단 기준"));
        return result;
    }
}