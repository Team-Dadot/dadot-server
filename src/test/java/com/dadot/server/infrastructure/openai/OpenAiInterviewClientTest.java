package com.dadot.server.infrastructure.openai;

import com.dadot.server.domain.interview.model.InterviewGeneration.*;
import com.dadot.server.domain.interview.support.InterviewGenerationProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiInterviewClientTest {
    private final JsonMapper mapper = new JsonMapper();
    private final AtomicReference<JsonNode> request =
            new AtomicReference<>();
    private final AtomicInteger requestCount =
            new AtomicInteger();

    private HttpServer server;
    private OpenAiInterviewClient client;
    private String body;
    private int status;

    @BeforeEach
    void setup() throws Exception {
        status = 200;

        body = response(
                "completed",
                Map.of(
                        "type", "output_text",
                        "text",
                        """
                        {
                          "question":"우리 회사에 지원한 이유는 무엇인가요?",
                          "evidence":[{
                            "disclosureVersionId":2,
                            "sectionId":1,
                            "quote":"해외 판매",
                            "interpretation":"이 회사는 해외에서 제품을 판매해요. 해외 고객에게 판매하는 내용이에요."
                          }]
                        }
                        """
                )
        );

        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0
        );

        server.createContext("/responses", exchange -> {
            request.set(
                    mapper.readTree(
                            exchange.getRequestBody().readAllBytes()
                    )
            );
            requestCount.incrementAndGet();

            exchange.getResponseHeaders().add(
                    "Retry-After", "120"
            );
            exchange.getResponseHeaders().add(
                    "x-request-id", "test-request"
            );

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);

            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });

        server.start();

        client = new OpenAiInterviewClient(
                mapper,
                new InterviewGenerationProperties(
                        true,
                        false,
                        "gpt-6-luna",
                        "test-key-".repeat(4),
                        3_000_000,
                        40_000
                ),
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                URI.create(
                        "http://127.0.0.1:"
                                + server.getAddress().getPort()
                                + "/responses"
                ),
                Duration.ofSeconds(2),
                Clock.systemUTC()
        );
    }

    @AfterEach
    void cleanup() {
        server.stop(0);
    }

    @Test
    void sendsStatelessStrictSchemaAndParsesEvidence() {
        Output output = client.generate(lease());

        assertEquals(
                1,
                output.generated().evidence().size()
        );
        assertEquals(
                30,
                output.metadata().inputTokens()
        );
        assertFalse(
                request.get().path("store").asBoolean()
        );
        assertFalse(request.get().has("tools"));
        assertTrue(
                request.get().path("text")
                        .path("format")
                        .path("strict")
                        .asBoolean()
        );
        assertFalse(
                request.get().toString().contains("test-key")
        );
        assertEquals(
                "gpt-6-luna",
                request.get().path("model").asText()
        );

        JsonNode schema = request.get()
                .path("text")
                .path("format")
                .path("schema");

        JsonNode question = schema
                .path("properties")
                .path("question");

        assertEquals(
                "string",
                question.path("type").asText()
        );
        assertEquals(
                1,
                question.path("minLength").asInt()
        );
        assertFalse(question.has("maxLength"));

        assertEquals(
                500,
                schema.path("properties")
                        .path("evidence")
                        .path("items")
                        .path("properties")
                        .path("quote")
                        .path("maxLength")
                        .asInt()
        );

        assertEquals("이 회사는 해외에서 제품을 판매해요. 해외 고객에게 판매하는 내용이에요.",
                output.generated().evidence().getFirst().interpretation());
        JsonNode interpretation = schema.path("properties").path("evidence").path("items")
                .path("properties").path("interpretation");
        assertEquals("string", interpretation.path("type").asText());
        assertFalse(interpretation.has("maxLength"));

        String instructions = request.get()
                .path("instructions")
                .asText();

        assertTrue(instructions.contains("20~40자"));
        assertTrue(instructions.contains("최대 60자"));
        assertTrue(
                instructions.contains("문장 끝을 자르지 않는다")
        );
        assertFalse(instructions.contains("마지막 문장은"));
        assertFalse(instructions.contains("재생성 규칙:"));
        assertEquals(1, requestCount.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {61, 80, 100, 160, 500})
    void providerOutputOver60CharactersFailsValidation(int length) {
        body = response(
                "completed",
                Map.of(
                        "type", "output_text",
                        "text",
                        mapper.writeValueAsString(
                                Map.of(
                                        "question", "가".repeat(length),
                                        "evidence", List.of(
                                                Map.of(
                                                        "disclosureVersionId", 2,
                                                        "sectionId", 1,
                                                        "quote", "해외 판매",
                                                        "interpretation", "이 회사는 해외에서 제품을 판매해요. 해외 고객에게 판매하는 내용이에요."
                                                )
                                        )
                                )
                        )
                )
        );

        assertEquals(
                "QUESTION_GENERATION_FAILED",
                assertThrows(
                        Failure.class,
                        () -> client.generate(lease())
                ).code()
        );
        assertEquals(1, requestCount.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "우리 회사에 지원한 이유는 무엇인가요?",
            "우리 회사를 선택한 계기를 말해 주세요.",
            "우리 회사에 관심을 가진 이유를 설명해 보세요.",
            "우리 회사에 지원한 동기를 말씀해 주십시오.",
            "왜 우리 회사에 지원했나요?",
            "우리 회사를 선택한 이유를 설명해주세요"
    })
    void acceptsDifferentEndingsWithoutRegeneration(String question) {
        body = questionResponse(question);

        Output output = client.generate(lease());

        assertEquals(
                question,
                output.generated().question()
        );
        assertEquals(1, requestCount.get());
        assertEquals(
                30,
                output.metadata().inputTokens()
        );
        assertEquals(
                20,
                output.metadata().outputTokens()
        );
    }

    @Test
    void invalidCitationStillFailsRegardlessOfQuestionEnding() {
        body = questionResponse(
                "어떤 전략이 필요할까요?"
        ).replace("해외 판매", "없는 근거");

        Failure failure = assertThrows(
                Failure.class,
                () -> client.generate(lease())
        );

        assertEquals(
                "QUESTION_GENERATION_FAILED",
                failure.code()
        );
        assertFalse(failure.retryable());
        assertEquals(1, requestCount.get());
    }

    @Test
    void incompleteProviderEnvelopeIsNotRegenerated() {
        body = response(
                "incomplete",
                Map.of(
                        "type", "output_text",
                        "text", "{}"
                )
        );

        Failure failure = assertThrows(
                Failure.class,
                () -> client.generate(lease())
        );

        assertEquals(
                "AI_INCOMPLETE_OUTPUT",
                failure.code()
        );
        assertFalse(failure.retryable());
        assertEquals(1, requestCount.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 409, 429, 500, 503})
    void temporaryErrorsAreRetriedWithBoundedDelay(int value) {
        status = value;

        Failure failure = assertThrows(
                Failure.class,
                () -> client.generate(lease())
        );

        assertTrue(failure.retryable());
        assertEquals(60, failure.retryAfter());
        assertEquals(1, requestCount.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 302})
    void permanentErrorsAreNotRetried(int value) {
        status = value;

        assertFalse(
                assertThrows(
                        Failure.class,
                        () -> client.generate(lease())
                ).retryable()
        );
    }

    @Test
    void quotaRefusalIncompleteAndOversizedResponsesFailSafely() {
        status = 429;
        body = "{\"error\":{\"code\":\"insufficient_quota\"}}";

        assertEquals(
                "AI_QUOTA_EXCEEDED",
                assertThrows(
                        Failure.class,
                        () -> client.generate(lease())
                ).code()
        );

        status = 200;
        body = response(
                "completed",
                Map.of(
                        "type", "refusal",
                        "refusal", "refused"
                )
        );

        assertEquals(
                "AI_REFUSED",
                assertThrows(
                        Failure.class,
                        () -> client.generate(lease())
                ).code()
        );

        body = response(
                "incomplete",
                Map.of(
                        "type", "output_text",
                        "text", "{}"
                )
        );

        assertEquals(
                "AI_INCOMPLETE_OUTPUT",
                assertThrows(
                        Failure.class,
                        () -> client.generate(lease())
                ).code()
        );

        body = "x".repeat(300_000);

        assertEquals(
                "AI_RESPONSE_TOO_LARGE",
                assertThrows(
                        Failure.class,
                        () -> client.generate(lease())
                ).code()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"가", "🙂"})
    void completeQuestionAt60CodePointsIsNotTruncated(String character) {
        String ending = "지원한 이유는 무엇인가요?";
        String question = character.repeat(
                60 - ending.codePointCount(0, ending.length())
        ) + ending;

        body = questionResponse(question);

        assertEquals(
                question,
                client.generate(lease()).generated().question()
        );
        assertEquals(1, requestCount.get());
    }

    @Test
    void serverRejectsOver60RatherThanTruncatingToFitSchema() {
        String ending = "지원한 이유는 무엇인가요?";
        String question = "가".repeat(
                61 - ending.codePointCount(0, ending.length())
        ) + ending;

        body = questionResponse(question);

        Failure failure = assertThrows(
                Failure.class,
                () -> client.generate(lease())
        );

        assertEquals(
                "QUESTION_TOO_LONG",
                failure.reason()
        );
        assertEquals(
                "test-request",
                failure.metadata().requestId()
        );
        assertEquals(1, requestCount.get());
    }

    @Test
    void retryAlsoOmitsSchemaLengthCapAndRequestsACompleteShortQuestion() {
        client.generate(lease(2));

        JsonNode question = request.get()
                .path("text")
                .path("format")
                .path("schema")
                .path("properties")
                .path("question");

        assertFalse(question.has("maxLength"));

        String instructions = request.get()
                .path("instructions")
                .asText();

        assertTrue(instructions.contains("재시도 지침:"));
        assertTrue(
                instructions.contains(
                        "질문을 먼저 완결한 다음 길이를 확인한다"
                )
        );
        assertTrue(
                instructions.contains(
                        "60자까지 채울 필요가 없다"
                )
        );
        assertFalse(instructions.contains("마지막 문장은"));
        assertEquals(1, requestCount.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"무엇", "이 회사의 주요 제품은 무엇", "어떤 점을 우선하겠", "설명해 보세", "무엇?"})
    void unfinishedProviderQuestionIsRejectedWithMetadata(String question) {
        body = questionResponse(question);
        Failure failure = assertThrows(Failure.class, () -> client.generate(lease()));
        assertEquals("QUESTION_GENERATION_FAILED", failure.code());
        assertEquals("QUESTION_INCOMPLETE", failure.reason());
        assertEquals("test-request", failure.metadata().requestId());
        assertEquals(30, failure.metadata().inputTokens());
        assertEquals(20, failure.metadata().outputTokens());
        assertEquals(1, requestCount.get());
    }

    @Test
    void evidenceErrorsTakePrecedenceOverTheNewEndingCheck() {
        body = questionResponse("어떤 점을 우선하겠").replace("해외 판매", "없는 근거");
        Failure failure = assertThrows(Failure.class, () -> client.generate(lease()));
        assertEquals("EVIDENCE_QUOTE_MISMATCH", failure.reason());
        assertFalse(failure.retryable());
        assertEquals(1, requestCount.get());
    }

    @Test
    void preservesJobExperienceAndQuestionHistoryInTheGenerationInput() {
        var previous = List.of("지원 계기는 무엇인가요?", "협업에서 무엇이 중요할까요?");
        var input = new Input("예시 회사", new Job(null, "생산기술", true), List.of(),
                lease().input().sections(),
                List.of(new Material(3, 4, 1, "팀 과제", "팀 과제에서 역할을 나눴습니다.")), previous);
        var encoded = mapper.readTree(client.encode("gpt-6-luna", "관리자 지침", input));
        var actual = mapper.readTree(encoded.path("input").asText());
        assertEquals("생산기술", actual.path("job").path("jobName").asText());
        assertEquals("팀 과제에서 역할을 나눴습니다.", actual.path("experiences").get(0).path("content").asText());
        assertEquals(mapper.valueToTree(previous), actual.path("previousQuestions"));
        assertEquals(mapper.readTree(mapper.writeValueAsString(input.sections())), actual.path("sections"));
    }

    @Test
    void changedPolicyStopsBeforeCallingTheProvider() {
        var old = lease();
        var stale = new Lease(old.taskId(), old.userId(), old.companyId(), old.contextId(), old.policyVersionId(),
                old.owner(), old.fence(), old.runId(), old.attempt(), old.model(), "old-policy", old.rules(), old.input());
        assertEquals("AI_POLICY_CHANGED", assertThrows(Failure.class, () -> client.generate(stale)).code());
        assertEquals(0, requestCount.get());
    }

    @Test
    void newProviderResponsesMustIncludeACompleteBoundedInterpretation() {
        for (String text : List.of("이 회사는 해외에서", "가".repeat(181),
                "해외에서 판매해요. 고객에게 공급해요. 제품을 판매해요.")) {
            var envelope = mapper.readTree(body);
            var content = (tools.jackson.databind.node.ObjectNode) envelope.path("output").get(0).path("content").get(0);
            var generated = mapper.readTree(content.path("text").asText());
            ((tools.jackson.databind.node.ObjectNode) generated.path("evidence").get(0)).put("interpretation", text);
            content.put("text", mapper.writeValueAsString(generated));
            body = mapper.writeValueAsString(envelope);
            Failure failure = assertThrows(Failure.class, () -> client.generate(lease()));
            assertTrue(failure.reason().startsWith("EVIDENCE_INTERPRETATION_"));
            assertEquals("test-request", failure.metadata().requestId());
            assertEquals(20, failure.metadata().outputTokens());
        }
        var envelope = mapper.readTree(body);
        var content = (tools.jackson.databind.node.ObjectNode) envelope.path("output").get(0).path("content").get(0);
        var generated = mapper.readTree(content.path("text").asText());
        ((tools.jackson.databind.node.ObjectNode) generated.path("evidence").get(0)).remove("interpretation");
        content.put("text", mapper.writeValueAsString(generated));
        body = mapper.writeValueAsString(envelope);
        assertEquals("EVIDENCE_INTERPRETATION_REQUIRED", assertThrows(Failure.class, () -> client.generate(lease())).reason());
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "'입사 후 어떤 일을 해보고 싶나요?',QUESTION_POLICY_CORE_MISMATCH",
            "'영업 직무에 지원한 이유는 무엇인가요?',QUESTION_POLICY_JOB_NAME",
            "'사업 확대를 고려해 우리 회사에 지원한 이유는 무엇인가요?',QUESTION_POLICY_FORCED_BRIDGE",
            "'경쟁사보다 우리 회사에 지원한 이유는 무엇인가요?',QUESTION_POLICY_UNSUPPORTED_CONTEXT"
    })
    void rejectsQuestionsOutsideTheSelectedCoreWithProviderMetadata(String question, String reason) {
        body = questionResponse(question);
        var failure = assertThrows(Failure.class, () -> client.generate(lease()));
        assertEquals(reason, failure.reason());
        assertEquals("test-request", failure.metadata().requestId());
        assertEquals(1, requestCount.get());
    }

    @Test
    void sendsTheSelectedCoreAndExcludesBoilerplateBeforeTheProviderCall() {
        var base = lease();
        var original = base.input();
        var bad = new Section(9, 9, "연결재무제표 작성기준", "보고기간말 추정치와 가정은 실제 결과와 다를 수 있습니다.");
        var input = new Input(original.companyName(), original.job(), original.sources(),
                List.of(original.sections().getFirst(), bad), original.experiences(), original.previousQuestions());
        var value = new Lease(base.taskId(), base.userId(), base.companyId(), base.contextId(), base.policyVersionId(),
                base.owner(), base.fence(), base.runId(), base.attempt(), base.model(), base.promptHash(), base.rules(), input);
        client.generate(value);
        var actual = mapper.readTree(request.get().path("input").asText());
        assertEquals(1, actual.path("sections").size());
        assertEquals(1, actual.path("sections").get(0).path("sectionId").asInt());
        assertEquals("회사 지원동기", actual.path("coreQuestion").path("type").asText());
        assertEquals("우리 회사에 지원한 이유는 무엇인가요?", actual.path("coreQuestion").path("question").asText());
    }

    @Test
    void noSuitableBusinessSectionStopsBeforeAnyPaidRequest() {
        var base = lease();
        var old = base.input();
        var input = new Input(old.companyName(), old.job(), old.sources(), List.of(new Section(9, 9,
                "재무제표 작성기준", "보고기간말 추정치와 가정은 실제 결과와 다를 수 있습니다.")),
                old.experiences(), old.previousQuestions());
        var value = new Lease(base.taskId(), base.userId(), base.companyId(), base.contextId(), base.policyVersionId(),
                base.owner(), base.fence(), base.runId(), base.attempt(), base.model(), base.promptHash(), base.rules(), input);
        assertEquals("CONTENT_UNAVAILABLE", assertThrows(Failure.class, () -> client.generate(value)).code());
        assertEquals(0, requestCount.get());
    }

    private Lease lease() {
        return lease(1);
    }

    private Lease withPreviousQuestions(List<String> previous) {
        var base = lease();
        var old = base.input();
        var input = new Input("SK하이닉스", old.job(), old.sources(), old.sections(), old.experiences(), previous);
        return new Lease(base.taskId(), base.userId(), base.companyId(), base.contextId(), base.policyVersionId(),
                base.owner(), base.fence(), base.runId(), base.attempt(), base.model(), base.promptHash(), base.rules(), input);
    }

    @Test
    void rejectsTheScreenshotParaphraseReturnedByTheProviderWithUsageMetadata() {
        body = questionResponse("SK하이닉스에 지원한 이유는 무엇인가요?");
        var failure = assertThrows(Failure.class, () -> client.generate(withPreviousQuestions(
                List.of("SK하이닉스에 지원한 이유를 말씀해 주세요.",
                        "어떤 제품이 궁금한가요?", "우리 회사 제품의 강점은 무엇인가요?",
                        "입사 후 어떤 일을 해보고 싶나요?", "본인의 경험을 어떻게 활용하고 싶나요?",
                        "어떤 사업이 인상 깊었나요?", "우리 회사 사업의 매력은 무엇인가요?",
                        "입사 후 어떤 업무를 배우고 싶나요?", "본인의 강점을 어떻게 활용하고 싶나요?"))));
        assertEquals("QUESTION_POLICY_RECENT_DUPLICATE", failure.reason());
        assertEquals("test-request", failure.metadata().requestId());
        assertEquals(30, failure.metadata().inputTokens());
        assertEquals(1, requestCount.get());
    }

    @Test
    void requestsAndAcceptsAnotherTypeInsteadOfParaphrasingThePreviousQuestion() {
        body = questionResponse("어떤 제품이 궁금한가요?");
        var output = client.generate(withPreviousQuestions(
                List.of("SK하이닉스에 지원한 이유를 말씀해 주세요.")));
        assertEquals("어떤 제품이 궁금한가요?", output.generated().question());
        var actual = mapper.readTree(request.get().path("input").asText());
        assertEquals("사업 관심", actual.path("coreQuestion").path("type").asText());
        assertEquals("SK하이닉스에 지원한 이유를 말씀해 주세요.",
                actual.path("previousQuestions").get(0).asText());
        assertFalse(request.get().path("instructions").asText().contains("표현만 바꾼다"));
        assertEquals(1, requestCount.get());
    }

    private Lease lease(int attempt) {
        var input = new Input(
                "회사",
                new Job(null, "영업", true),
                List.of(),
                List.of(
                        new Section(
                                1, 2, "사업", "해외 판매 확대"
                        )
                ),
                List.of(),
                List.of()
        );

        return new Lease(
                "task",
                1,
                1,
                "context",
                1,
                "owner",
                1,
                attempt,
                attempt,
                "gpt-6-luna",
                client.policyHash("지침"),
                "지침",
                input
        );
    }

    private String questionResponse(String question) {
        return response(
                "completed",
                Map.of(
                        "type", "output_text",
                        "text",
                        mapper.writeValueAsString(
                                Map.of(
                                        "question", question,
                                        "evidence", List.of(
                                                Map.of(
                                                        "disclosureVersionId", 2,
                                                        "sectionId", 1,
                                                        "quote", "해외 판매",
                                                        "interpretation", "이 회사는 해외에서 제품을 판매해요. 해외 고객에게 판매하는 내용이에요."
                                                )
                                        )
                                )
                        )
                )
        );
    }

    private String response(
            String state,
            Map<String, String> content
    ) {
        return mapper.writeValueAsString(
                Map.of(
                        "status", state,
                        "model", "gpt-6-luna",
                        "usage", Map.of(
                                "input_tokens", 30,
                                "output_tokens", 20
                        ),
                        "output", List.of(
                                Map.of(
                                        "type", "message",
                                        "content", List.of(content)
                                )
                        )
                )
        );
    }
}
