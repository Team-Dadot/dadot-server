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
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiInterviewClientTest {
    private final JsonMapper mapper = new JsonMapper();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> clientIds = new CopyOnWriteArrayList<>();
    private final Queue<StubResponse> responses = new ConcurrentLinkedQueue<>();
    private HttpServer server;
    private OpenAiInterviewClient client;
    private String body;
    private int status;

    private record StubResponse(int status, String body) {}

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
                    mapper.readTree(exchange.getRequestBody().readAllBytes())
            );
            requestCount.incrementAndGet();
            requests.add(request.get());
            clientIds.add(exchange.getRequestHeaders().getFirst("X-Client-Request-Id"));
            StubResponse planned = responses.poll();
            if (planned == null) planned = new StubResponse(status, body);

            exchange.getResponseHeaders().add("Retry-After", "120");
            exchange.getResponseHeaders().add(
                    "x-request-id", "test-request"
            );

            byte[] bytes = planned.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(planned.status(), bytes.length);

            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });

        server.start();

        client = new OpenAiInterviewClient(
                mapper,
                new InterviewGenerationProperties(
                        true, false, "gpt-6-luna",
                        "test-key-".repeat(4), 3_000_000, 40_000
                ),
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create(
                        "http://127.0.0.1:"
                                + server.getAddress().getPort() + "/responses"
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

        assertEquals(1, output.generated().evidence().size());
        assertEquals(30, output.metadata().inputTokens());
        assertFalse(request.get().path("store").asBoolean());
        assertFalse(request.get().has("tools"));
        assertTrue(
                request.get().path("text")
                        .path("format").path("strict").asBoolean()
        );
        assertFalse(request.get().toString().contains("test-key"));
        assertEquals("gpt-6-luna", request.get().path("model").asText());
        JsonNode schema = request.get().path("text").path("format").path("schema");
        assertEquals(100, schema.path("properties").path("question").path("maxLength").asInt());
        assertEquals(500, schema.path("properties").path("evidence").path("items")
                .path("properties").path("quote").path("maxLength").asInt());
        String instructions = request.get().path("instructions").asText();
        assertTrue(instructions.contains("50~80자"));
        assertTrue(instructions.contains("최대 100자"));
        assertTrue(instructions.contains("문장 끝을 자르지 않는다"));
        assertTrue(instructions.contains("하시겠습니까?"));
        assertEquals(1, requestCount.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {101, 160, 500})
    void providerOutputOver100CharactersFailsValidation(int length) {
        body = response("completed", Map.of("type", "output_text", "text",
                mapper.writeValueAsString(Map.of("question", "가".repeat(length),
                        "evidence", List.of(Map.of("disclosureVersionId", 2,
                                "sectionId", 1, "quote", "해외 판매"))))));
        assertEquals("QUESTION_GENERATION_FAILED", assertThrows(Failure.class,
                () -> client.generate(lease())).code());
        assertEquals(1, requestCount.get());
    }

    @Test
    void incompleteQuestionIsRegeneratedOnceWithSeparateRequestId() {
        responses.add(new StubResponse(200, questionResponse("어떤 점을 우선하겠")));

        Output output = client.generate(lease());

        assertEquals("해외영업 전략을 설명해 주세요.", output.generated().question());
        assertEquals(2, requestCount.get());
        assertNotEquals(clientIds.get(0), clientIds.get(1));
        assertFalse(requests.get(0).path("instructions").asText().contains("재생성 규칙:"));
        assertTrue(requests.get(1).path("instructions").asText().contains("재생성 규칙:"));
        assertEquals(requests.get(0).path("input"), requests.get(1).path("input"));
        assertEquals(60, output.metadata().inputTokens());
        assertEquals(40, output.metadata().outputTokens());
    }

    @Test
    void repeatedIncompleteQuestionFailsWithoutFurtherWorkerRetry() {
        body = questionResponse("어떤 점을 우선하겠?");

        Failure failure = assertThrows(Failure.class, () -> client.generate(lease()));

        assertEquals("QUESTION_INCOMPLETE", failure.code());
        assertFalse(failure.retryable());
        assertEquals(2, requestCount.get());
        assertEquals(60, failure.metadata().inputTokens());
        assertEquals(40, failure.metadata().outputTokens());
    }

    @Test
    void temporaryErrorDuringRegenerationDoesNotStartAnotherWorkerRun() {
        responses.add(new StubResponse(200, questionResponse("어떤 점을 우선하겠")));
        responses.add(new StubResponse(503, "{}"));

        Failure failure = assertThrows(Failure.class, () -> client.generate(lease()));

        assertEquals("AI_HTTP_503", failure.code());
        assertFalse(failure.retryable());
        assertEquals(2, requestCount.get());
        assertNull(failure.metadata().inputTokens());
    }

    @Test
    void invalidCitationIsNotRegeneratedEvenWhenQuestionIsIncomplete() {
        body = questionResponse("어떤 점을 우선하겠").replace("해외 판매", "없는 근거");

        Failure failure = assertThrows(Failure.class, () -> client.generate(lease()));

        assertEquals("QUESTION_GENERATION_FAILED", failure.code());
        assertFalse(failure.retryable());
        assertEquals(1, requestCount.get());
    }

    @Test
    void incompleteProviderEnvelopeIsNotRegenerated() {
        body = response("incomplete", Map.of("type", "output_text", "text", "{}"));

        Failure failure = assertThrows(Failure.class, () -> client.generate(lease()));

        assertEquals("AI_INCOMPLETE_OUTPUT", failure.code());
        assertFalse(failure.retryable());
        assertEquals(1, requestCount.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 409, 429, 500, 503})
    void temporaryErrorsAreRetriedWithBoundedDelay(int value) {
        status = value;

        Failure failure = assertThrows(
                Failure.class, () -> client.generate(lease())
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
                        Failure.class, () -> client.generate(lease())
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
                        Failure.class, () -> client.generate(lease())
                ).code()
        );

        status = 200;
        body = response(
                "completed",
                Map.of("type", "refusal", "refusal", "refused")
        );

        assertEquals(
                "AI_REFUSED",
                assertThrows(
                        Failure.class, () -> client.generate(lease())
                ).code()
        );

        body = response(
                "incomplete",
                Map.of("type", "output_text", "text", "{}")
        );

        assertEquals(
                "AI_INCOMPLETE_OUTPUT",
                assertThrows(
                        Failure.class, () -> client.generate(lease())
                ).code()
        );

        body = "x".repeat(300_000);

        assertEquals(
                "AI_RESPONSE_TOO_LARGE",
                assertThrows(
                        Failure.class, () -> client.generate(lease())
                ).code()
        );
    }

    private Lease lease() {
        var input = new Input(
                "회사",
                new Job(null, "영업", true),
                List.of(),
                List.of(new Section(1, 2, "사업", "해외 판매 확대")),
                List.of(),
                List.of()
        );

        return new Lease(
                "task", 1, 1, "context", 1, "owner",
                1, 1, 1, "gpt-6-luna",
                client.policyHash("지침"), "지침", input
        );
    }

    private String questionResponse(String question) {
        return response("completed", Map.of("type", "output_text", "text",
                mapper.writeValueAsString(Map.of("question", question,
                        "evidence", List.of(Map.of("disclosureVersionId", 2,
                                "sectionId", 1, "quote", "해외 판매"))))));
    }

    private String response(String state, Map<String, String> content) {
        return mapper.writeValueAsString(Map.of(
                "status", state,
                "model", "gpt-6-luna",
                "usage", Map.of(
                        "input_tokens", 30, "output_tokens", 20
                ),
                "output", List.of(Map.of(
                        "type", "message",
                        "content", List.of(content)
                ))
        ));
    }
}