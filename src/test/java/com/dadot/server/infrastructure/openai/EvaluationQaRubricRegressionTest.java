package com.dadot.server.infrastructure.openai;

import com.dadot.server.domain.interview.EvaluationFixtures;
import com.dadot.server.domain.interview.model.InterviewEvaluation.Input;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.interview.support.InterviewEvaluationProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;

/** 유료 AI 채점 결과가 아닌, 기존 공개 정책 설명이 전송되는 경로의 회귀 검증이다. */
class EvaluationQaRubricRegressionTest {
    private final JsonMapper mapper = new JsonMapper();
    private final OpenAiEvaluationClient client = new OpenAiEvaluationClient(mapper,
            new InterviewEvaluationProperties(true, false, "gpt-6-luna", "test-key-".repeat(4), 40_000),
            Clock.systemUTC());

    @Test
    void oldPublishedRubricDoesNotRequireCareerAchievementsInNewRequests() {
        Input input = withDescriptions("경험 설명이 없으면 0점, 반드시 정량 성과와 완전한 STAR 형식을 요구한다.");

        var request = mapper.readTree(client.encode("gpt-6-luna", "정책", input));
        var sent = mapper.readTree(request.path("input").asText());

        assertNotEquals(input.rubric().get(1).description(), sent.path("rubric").get(1).path("description").asText());
        assertEquals(input.answer(), sent.path("answer").asText());
        assertEquals(mapper.readTree(mapper.writeValueAsString(input.experiences())), sent.path("experiences"));
        assertEquals(mapper.readTree(mapper.writeValueAsString(input.evidence())), sent.path("evidence"));
        assertEquals("경험 설명이 없으면 0점, 반드시 정량 성과와 완전한 STAR 형식을 요구한다.", input.rubric().get(1).description());
        for (int i = 0; i < 4; i++) {
            assertEquals(input.rubric().get(i).code(), sent.path("rubric").get(i).path("code").asText());
            assertEquals(input.rubric().get(i).label(), sent.path("rubric").get(i).path("label").asText());
            assertEquals(25, sent.path("rubric").get(i).path("maxScore").asInt());
        }
    }

    @Test
    void legacyDescriptionsDoNotChangeEffectiveRequestOrItsHash() {
        Input strict = withDescriptions("모든 답변에 직무 연결, 전사 전략 분석과 정량 성과가 필수다.");
        Input vague = withDescriptions("기존의 다른 평가 설명이다.");

        assertArrayEquals(client.encode("gpt-6-luna", "동일 정책", strict),
                client.encode("gpt-6-luna", "동일 정책", vague));
        assertEquals(client.inputHash("gpt-6-luna", "동일 정책", strict),
                client.inputHash("gpt-6-luna", "동일 정책", vague));
        assertNotEquals(client.inputHash("gpt-6-luna", "정책 A", strict),
                client.inputHash("gpt-6-luna", "정책 B", strict));
    }

    private Input withDescriptions(String description) {
        Input source = EvaluationFixtures.input();
        var rubric = source.rubric().stream().map(c ->
                new InterviewPolicy.Criterion(c.code(), c.label(), c.maxScore(), description)).toList();
        return new Input(source.companyName(), source.job(), source.question(), source.answer(),
                source.evidence(), source.experiences(), rubric);
    }
}
