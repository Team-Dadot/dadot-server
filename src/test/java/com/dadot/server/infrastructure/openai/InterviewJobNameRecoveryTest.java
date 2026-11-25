package com.dadot.server.infrastructure.openai;

import com.dadot.server.domain.interview.model.InterviewGeneration.*;
import com.dadot.server.domain.interview.support.InterviewGenerationProperties;
import com.dadot.server.domain.interview.support.InterviewQuestionPolicy;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InterviewJobNameRecoveryTest {
    private static final String QUOTE = "회사는 물류 서비스와 제품을 판매하고 연구개발 업무와 생산 활동을 운영합니다.";
    private static final String MOTIVATION = "우리 회사에 지원한 이유는 무엇인가요?";
    private final JsonMapper mapper = new JsonMapper();

    private Input input(String job, List<String> history) {
        return InterviewQuestionPolicy.prepare(new Input("예시 회사", new Job(null, job, true), List.of(),
                List.of(new Section(1, 2, "주요 사업", QUOTE)), List.of(), history));
    }

    private Generated generated(String question) {
        return new Generated(question, List.of(new Quote(2, 1, QUOTE,
                "이 회사는 물류 서비스와 제품을 판매하며 연구개발 업무와 생산 활동을 운영해요.")));
    }

    @ParameterizedTest
    @CsvSource({
            "물류,우리 회사의 물류 사업 중 어떤 점이 인상 깊었나요?",
            "생산,우리 회사의 생산 활동 중 어떤 점이 인상 깊었나요?",
            "개발,우리 회사의 연구개발 업무 중 어떤 점이 인상 깊었나요?",
            "개발,우리 회사의 연구개발 활동 중 어떤 점이 인상 깊었나요?",
            "영업,우리 회사의 제품 중 어떤 점이 인상 깊었나요?"
    })
    void permitsBusinessWordsAndCompoundWordsWithoutConfusingThemWithRoles(String job, String question) {
        var original = generated(question);
        assertSame(original, InterviewQuestionPolicy.validateQuestion(original, input(job, List.of(MOTIVATION))));
    }

    @ParameterizedTest
    @CsvSource({
            "물류,물류 직무에서 어떤 사업에 관심이 있나요?",
            "물류,물류직무에서 어떤 사업에 관심이 있나요?",
            "물류,물 류 직무에서 어떤 사업에 관심이 있나요?",
            "물류,물류 업무에서 어떤 사업에 관심이 있나요?",
            "인사,인사에 관심을 가진 이유는 무엇인가요?",
            "물류,물류에 지원한 이유는 무엇인가요?",
            "개발,개발 담당으로 어떤 사업에 관심이 있나요?"
    })
    void stillRejectsExplicitRolesAndUnrelatedJobMentions(String job, String question) {
        Failure failure = assertThrows(Failure.class,
                () -> InterviewQuestionPolicy.validateQuestion(generated(question), input(job, List.of())));
        assertEquals("QUESTION_POLICY_JOB_NAME", failure.reason());
    }

    @Test
    void ignoresJobMentionsInExcludedSections() {
        var input = new Input("예시 회사", new Job(null, "인사", true), List.of(), List.of(
                new Section(1, 2, "주요 사업", QUOTE),
                new Section(3, 2, "재무제표 주석", "인사 관련 회계정책을 설명합니다.")), List.of(), List.of());
        assertEquals("QUESTION_POLICY_JOB_NAME", assertThrows(Failure.class,
                () -> InterviewQuestionPolicy.validateQuestion(generated("인사에 관심을 가진 이유는 무엇인가요?"), input)).reason());
    }

    @Test
    void stillRejectsUnsupportedQuestionsEvenWhenTheJobIsABusinessWord() {
        assertEquals("QUESTION_POLICY_UNSUPPORTED_CONTEXT", assertThrows(Failure.class,
                () -> InterviewQuestionPolicy.validateQuestion(generated("물류 사업은 경쟁사보다 어떤 점이 강점이라고 생각하나요?"),
                        input("물류", List.of(MOTIVATION)))).reason());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void repeatedJobNameFailureCanRecoverWithTheChosenCoreAndValidatedEvidence(int attempt) throws Exception {
        try (var api = new FakeApi()) {
            var input = input("물류", List.of());
            var client = api.client();
            String core = InterviewQuestionPolicy.core(input).question();
            api.question = "물류 직무에 지원한 이유는 무엇인가요?";
            Failure first = assertThrows(Failure.class, () -> client.generate(lease(client, input, 1)));
            assertEquals("QUESTION_POLICY_JOB_NAME", first.reason());
            assertFalse(api.requests.getFirst().path("text").path("format").path("schema")
                    .path("properties").path("question").has("enum"));
            api.question = core;
            Output result = client.generate(lease(client, input, attempt));
            assertEquals(core, result.generated().question());
            assertEquals(generated(core).evidence(), result.generated().evidence());
            assertEquals("fake-request", result.metadata().requestId());
            assertEquals(30, result.metadata().inputTokens());
            assertEquals(20, result.metadata().outputTokens());
            assertEquals(2, api.requests.size());
            JsonNode retry = api.requests.getLast();
            JsonNode choices = retry.path("text").path("format").path("schema")
                    .path("properties").path("question").path("enum");
            assertEquals(1, choices.size());
            assertEquals(core, choices.get(0).asText());
            assertTrue(retry.path("instructions").asText().contains("공백·문장부호까지 정확히"));
            assertEquals(core, mapper.readTree(retry.path("input").asText()).path("coreQuestion").path("question").asText());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void retriesStillRejectAnOtherwiseValidQuestionOutsideTheSchemaChoice(int attempt) throws Exception {
        try (var api = new FakeApi()) {
            var client = api.client();
            api.question = "우리 회사에 지원하게 된 이유는 무엇인가요?";
            Failure failure = assertThrows(Failure.class, () -> client.generate(lease(client, input("물류", List.of()), attempt)));
            assertEquals("QUESTION_POLICY_RETRY_MISMATCH", failure.reason());
            assertEquals("fake-request", failure.metadata().requestId());
            assertEquals(1, api.requests.size());
        }
    }

    @ParameterizedTest
    @CsvSource({"물류,1", "인사,1", "개발,1"})
    void firstAttemptStillAllowsNaturalVariations(String job, int attempt) throws Exception {
        try (var api = new FakeApi()) {
            var client = api.client();
            api.question = "우리 회사에 지원하게 된 이유는 무엇인가요?";
            assertEquals(api.question, client.generate(lease(client, input(job, List.of()), attempt)).generated().question());
        }
    }

    @Test
    void retryKeepsCoreRotationAndDuplicateValidation() throws Exception {
        try (var api = new FakeApi()) {
            var client = api.client();
            var input = input("물류", List.of(MOTIVATION));
            api.question = InterviewQuestionPolicy.core(input).question();
            assertNotEquals(MOTIVATION, api.question);
            assertEquals(api.question, client.generate(lease(client, input, 2)).generated().question());
            api.question = MOTIVATION;
            assertEquals("QUESTION_POLICY_RECENT_DUPLICATE", assertThrows(Failure.class,
                    () -> client.generate(lease(client, input, 3))).reason());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"quote", "interpretation", "length", "ending"})
    void retryDoesNotBypassExistingOutputValidation(String problem) throws Exception {
        try (var api = new FakeApi()) {
            var client = api.client();
            var input = input("물류", List.of());
            api.question = InterviewQuestionPolicy.core(input).question();
            String expected = switch (problem) {
                case "quote" -> { api.quote = "원문에 없는 인용입니다."; yield "EVIDENCE_QUOTE_MISMATCH"; }
                case "interpretation" -> { api.interpretation = ""; yield "EVIDENCE_INTERPRETATION_EMPTY"; }
                case "length" -> { api.question = "가".repeat(61); yield "QUESTION_TOO_LONG"; }
                default -> { api.question = "우리 회사에 지원하게 된 이유를 말하"; yield "QUESTION_INCOMPLETE"; }
            };
            assertEquals(expected, assertThrows(Failure.class, () -> client.generate(lease(client, input, 2))).reason());
        }
    }

    @ParameterizedTest
    @CsvSource({"429,insufficient_quota,AI_QUOTA_EXCEEDED,false", "429,rate_limit_exceeded,AI_HTTP_429,true", "401,invalid_api_key,AI_HTTP_401,false"})
    void retryKeepsProviderFailureSemantics(int status, String providerCode, String expected, boolean retryable) throws Exception {
        try (var api = new FakeApi()) {
            api.status = status;
            api.providerCode = providerCode;
            var client = api.client();
            Failure failure = assertThrows(Failure.class, () -> client.generate(lease(client, input("물류", List.of()), 2)));
            assertEquals(expected, failure.code());
            assertEquals(retryable, failure.retryable());
            assertEquals(1, api.requests.size());
        }
    }

    private Lease lease(OpenAiInterviewClient client, Input input, int attempt) {
        return new Lease("recovery-task", 1, 1, "context", 1, "owner", 1, attempt, attempt,
                "test-model", client.policyHash("지침"), "지침", input);
    }

    private final class FakeApi implements AutoCloseable {
        final HttpServer server;
        final List<JsonNode> requests = new ArrayList<>();
        String question = MOTIVATION;
        String quote = QUOTE;
        String interpretation = generated(MOTIVATION).evidence().getFirst().interpretation();
        int status = 200;
        String providerCode;

        FakeApi() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/responses", exchange -> {
                requests.add(mapper.readTree(exchange.getRequestBody().readAllBytes()));
                String content = mapper.writeValueAsString(Map.of("question", question, "evidence", List.of(Map.of(
                        "disclosureVersionId", 2, "sectionId", 1, "quote", quote, "interpretation", interpretation))));
                byte[] body = status == 200 ? mapper.writeValueAsBytes(Map.of("status", "completed", "model", "test-model",
                        "usage", Map.of("input_tokens", 30, "output_tokens", 20),
                        "output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", content))))))
                        : mapper.writeValueAsBytes(Map.of("error", Map.of("code", providerCode)));
                exchange.getResponseHeaders().add("x-request-id", "fake-request");
                exchange.sendResponseHeaders(status, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
        }

        OpenAiInterviewClient client() {
            return new OpenAiInterviewClient(mapper, new InterviewGenerationProperties(true, false,
                    "test-model", "test-key-".repeat(4), 3_000_000, 40_000), HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"),
                    Duration.ofSeconds(5), Clock.systemUTC());
        }

        public void close() { server.stop(0); }
    }
}
