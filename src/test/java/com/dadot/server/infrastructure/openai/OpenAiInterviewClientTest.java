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
                          "question":"해외영업 전략을 설명해 주세요.",
                          "evidence":[{
                            "disclosureVersionId":2,
                            "sectionId":1,
                            "quote":"해외 판매"
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

        String instructions = request.get()
                .path("instructions")
                .asText();

        assertTrue(instructions.contains("50~80자"));
        assertTrue(instructions.contains("최대 100자"));
        assertTrue(
                instructions.contains("문장 끝을 자르지 않는다")
        );
        assertTrue(
                instructions.contains(
                        "특정 어미나 표현으로 제한하지 않는다"
                )
        );
        assertFalse(instructions.contains("마지막 문장은"));
        assertFalse(instructions.contains("재생성 규칙:"));
        assertEquals(1, requestCount.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {101, 160, 500})
    void providerOutputOver100CharactersFailsValidation(int length) {
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
                                                        "quote", "해외 판매"
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
            "어떤 전략이 필요할까요?",
            "공급 지연에 어떻게 대응하나요?",
            "비용과 품질 중 무엇을 우선할 것인가요?",
            "품질과 납기를 함께 개선할 방안을 설명해 보세요.",
            "인재 확보 방안을 제안해 보십시오.",
            "어떤 점을 우선하겠"
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

    @Test
    void completeQuestionAt100CodePointsIsNotTruncated() {
        String ending = "어떻게 대응하나요?";
        String question = "가".repeat(
                100 - ending.codePointCount(0, ending.length())
        ) + ending;

        body = questionResponse(question);

        assertEquals(
                question,
                client.generate(lease()).generated().question()
        );
        assertEquals(1, requestCount.get());
    }

    @Test
    void serverRejectsOver100RatherThanTruncatingToFitSchema() {
        String ending = "어떻게 대응하나요?";
        String question = "가".repeat(
                101 - ending.codePointCount(0, ending.length())
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
                        "100자까지 채울 필요가 없다"
                )
        );
        assertFalse(instructions.contains("마지막 문장은"));
        assertEquals(1, requestCount.get());
    }

    private Lease lease() {
        return lease(1);
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
                                                        "quote", "해외 판매"
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