package com.dadot.server.domain.interview;

import com.dadot.server.domain.interview.model.InterviewEvaluation.Input;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.interview.support.InterviewFeedbackPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InterviewFeedbackPolicyTest {
    @Test
    void preparesOnlyEffectiveDescriptionsWithoutChangingUserDataOrPinnedPolicy() {
        Input original = EvaluationFixtures.input();

        Input prepared = InterviewFeedbackPolicy.prepare(original);

        assertEquals(original.companyName(), prepared.companyName());
        assertSame(original.job(), prepared.job());
        assertEquals(original.question(), prepared.question());
        assertEquals(original.answer(), prepared.answer());
        assertSame(original.evidence(), prepared.evidence());
        assertSame(original.experiences(), prepared.experiences());
        assertNotSame(original.rubric(), prepared.rubric());
        assertEquals(InterviewPolicy.CODES, prepared.rubric().stream().map(InterviewPolicy.Criterion::code).toList());
        assertTrue(prepared.rubric().stream().allMatch(c -> c.maxScore() == 25));
        assertEquals(original.rubric().stream().map(InterviewPolicy.Criterion::label).toList(),
                prepared.rubric().stream().map(InterviewPolicy.Criterion::label).toList());
        assertTrue(original.rubric().stream().allMatch(c -> c.description().equals("실제 답변의 근거와 구체성을 평가한다.")));
        assertEquals(prepared, InterviewFeedbackPolicy.prepare(prepared));
    }

    @Test
    void noRegisteredExperienceOrDisclosureDoesNotCreateOne() {
        Input original = EvaluationFixtures.input();
        Input empty = new Input(original.companyName(), original.job(), original.question(), original.answer(),
                List.of(), List.of(), original.rubric());

        Input prepared = InterviewFeedbackPolicy.prepare(empty);

        assertTrue(prepared.experiences().isEmpty());
        assertTrue(prepared.evidence().isEmpty());
        assertEquals(empty.answer(), prepared.answer());
    }
}
