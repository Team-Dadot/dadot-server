package com.dadot.server.infrastructure.openai;

import com.dadot.server.domain.interview.model.InterviewGeneration.*;
import com.dadot.server.domain.interview.support.InterviewGenerationProperties;
import com.dadot.server.domain.interview.support.InterviewSourcePlanner;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 실제 제보 문장을 가짜 AI 응답으로 반환하여 저장 경로 진입 전에 차단되는지 비교한다. */
class ReportedCorePolicyRegressionTest {
    @Test
    void accountingBasisFromTheScreenshotMustNotBeSelected() {
        var section = new Section(1, 1, "연결재무제표 작성기준",
                "보고기간말 경영진의 최선의 판단에 따른 추정치와 가정은 실제 결과와 다를 수 있습니다.");
        assertTrue(new InterviewSourcePlanner().select(List.of(List.of(section)), "회계", List.of()).isEmpty());
    }

    @Test
    void automationToHrMotivationFromTheScreenshotMustNotPassTheClient() throws Exception {
        var mapper = new JsonMapper();
        String quote = "TES물류기술연구소는 AI와 로봇을 물류센터 현장에 적용해 자동화를 추진합니다.";
        String question = "회사가 물류 자동화를 추진하는 점에 비추어, 인사 직무에 관심을 가진 이유를 말씀해 주세요.";
        assertTrue(question.codePointCount(0, question.length()) <= 60);
        String generated = mapper.writeValueAsString(Map.of("question", question, "evidence", List.of(Map.of(
                "disclosureVersionId", 2, "sectionId", 1, "quote", quote,
                "interpretation", "이 회사는 AI와 로봇을 물류센터에 적용해요."))));
        byte[] envelope = mapper.writeValueAsBytes(Map.of("status", "completed", "model", "gpt-6-luna",
                "usage", Map.of("input_tokens", 30, "output_tokens", 20),
                "output", List.of(Map.of("type", "message", "content", List.of(
                        Map.of("type", "output_text", "text", generated))))));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, envelope.length);
            try (var body = exchange.getResponseBody()) { body.write(envelope); }
        });
        server.start();
        try {
            var client = new OpenAiInterviewClient(mapper, new InterviewGenerationProperties(true, false,
                    "gpt-6-luna", "test-key-".repeat(4), 3_000_000, 40_000), HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"),
                    Duration.ofSeconds(5), Clock.systemUTC());
            var input = new Input("예시 물류 회사", new Job(null, "인사", true), List.of(),
                    List.of(new Section(1, 2, "주요계약 및 연구개발활동", quote)), List.of(), List.of());
            var lease = new Lease("reported-task", 1, 1, "context", 1, "owner", 1, 1, 1,
                    "gpt-6-luna", client.policyHash("지침"), "지침", input);
            Failure failure = assertThrows(Failure.class, () -> client.generate(lease));
            assertEquals("QUESTION_POLICY_JOB_NAME", failure.reason());
        } finally {
            server.stop(0);
        }
    }
}
