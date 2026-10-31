package com.dadot.server.infrastructure.openai;

import com.dadot.server.domain.interview.model.InterviewEvaluation.*;
import com.dadot.server.domain.interview.model.InterviewGeneration.Failure;
import com.dadot.server.domain.interview.support.InterviewEvaluationProperties;
import com.dadot.server.domain.interview.EvaluationFixtures;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiEvaluationClientTest {
    private final JsonMapper mapper = new JsonMapper();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private HttpServer server;
    private OpenAiEvaluationClient client;
    private String body;
    private int status;

    @BeforeEach
    void setup() throws Exception {
        status = 200;

        body = response("completed", Map.of("type", "output_text", "text",
                mapper.writeValueAsString(EvaluationFixtures.output(EvaluationFixtures.input(), 20))));

        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0
        );

        server.createContext("/responses", exchange -> {
            request.set(
                    mapper.readTree(exchange.getRequestBody().readAllBytes())
            );

            exchange.getResponseHeaders().add("Retry-After", "120");
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

        client = new OpenAiEvaluationClient(
                mapper,
                new InterviewEvaluationProperties(
                        true, false, "gpt-6-luna",
                        "test-key-".repeat(4), 40_000
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
    void sendsStatelessStrictSchemaAndParsesGroundedScores() {
        Output output = client.evaluate(lease());

        assertEquals(4, output.generated().criteria().size());
        assertEquals(20, output.generated().criteria().getFirst().score());
        assertEquals(8192, request.get().path("max_output_tokens").asInt());
        assertTrue(request.get().path("instructions").asText().contains("채점 대상은 answer"));
        assertTrue(request.get().path("instructions").asText().contains("21~25"));
        assertEquals(30, output.metadata().inputTokens());
        assertFalse(request.get().path("store").asBoolean());
        assertFalse(request.get().has("tools"));
        assertTrue(
                request.get().path("text")
                        .path("format").path("strict").asBoolean()
        );
        assertFalse(request.get().toString().contains("test-key"));
        assertEquals("gpt-6-luna", request.get().path("model").asText());
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 409, 429, 500, 503})
    void temporaryErrorsAreRetriedWithBoundedDelay(int value) {
        status = value;

        Failure failure = assertThrows(
                Failure.class, () -> client.evaluate(lease())
        );

        assertTrue(failure.retryable());
        assertEquals(60, failure.retryAfter());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 302})
    void permanentErrorsAreNotRetried(int value) {
        status = value;

        assertFalse(
                assertThrows(
                        Failure.class, () -> client.evaluate(lease())
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
                        Failure.class, () -> client.evaluate(lease())
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
                        Failure.class, () -> client.evaluate(lease())
                ).code()
        );

        body = response(
                "incomplete",
                Map.of("type", "output_text", "text", "{}")
        );

        assertEquals(
                "AI_INCOMPLETE_OUTPUT",
                assertThrows(
                        Failure.class, () -> client.evaluate(lease())
                ).code()
        );

        body = "x".repeat(300_000);

        assertEquals(
                "AI_RESPONSE_TOO_LARGE",
                assertThrows(
                        Failure.class, () -> client.evaluate(lease())
                ).code()
        );
    }

    @Test
    void validatesDuplicateKeysTrailingTokensAndInvalidAnswerCitation() {
        String valid = mapper.writeValueAsString(EvaluationFixtures.output(EvaluationFixtures.input(), 20));
        for (String json : List.of(valid + " {}", valid.replace("\"summaryFeedback\":", "\"summaryFeedback\":\"중복\",\"summaryFeedback\":"),
                valid.replace(EvaluationFixtures.input().answer().substring(0, 20), "답변에 없는 인용"))) {
            body = response("completed", Map.of("type", "output_text", "text", json));
            Failure failure = assertThrows(Failure.class, () -> client.evaluate(lease()));
            assertEquals("AI_INVALID_OUTPUT", failure.code());
            assertNotNull(failure.metadata());
            assertEquals(30, failure.metadata().inputTokens());
        }
    }

    @Test
    void rejectsOversizedRequestsAndPinsCanonicalInputHash() {
        var source = EvaluationFixtures.input();
        var huge = new Input(source.companyName(), source.job(), source.question(), "가".repeat(100_000),
                source.evidence(), source.experiences(), source.rubric());
        assertEquals("INTERVIEW_INPUT_TOO_LARGE", assertThrows(Failure.class,
                () -> client.encode("gpt-6-luna", "지침", huge)).code());
        assertEquals(client.inputHash("gpt-6-luna", "지침", source), client.inputHash("gpt-6-luna", "지침", source));
    }

    private Lease lease() {
        return new Lease(new Claim("task", "owner", 1, 1, 1, 1), "gpt-6-luna",
                client.policyHash("지침"), "지침", EvaluationFixtures.input());
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