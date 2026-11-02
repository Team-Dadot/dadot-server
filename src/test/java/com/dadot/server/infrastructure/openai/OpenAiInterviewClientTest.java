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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiInterviewClientTest {
    private final JsonMapper mapper = new JsonMapper();
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
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