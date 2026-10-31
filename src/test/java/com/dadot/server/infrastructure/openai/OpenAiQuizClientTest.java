package com.dadot.server.infrastructure.openai;

import com.dadot.server.domain.quiz.model.QuizGeneration.*;
import com.dadot.server.domain.quiz.support.QuizGenerationProperties;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiQuizClientTest {
    private final JsonMapper mapper = new JsonMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC);
    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private HttpServer server;
    private OpenAiQuizClient client;
    private String body;
    private int status;
    private String retryAfter;
    private boolean slowBody;

    @BeforeEach
    void setup() throws Exception {
        status = 200;
        body = response("completed", Map.of("type", "output_text", "text", "{\"questions\":[]}"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            received.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getResponseHeaders().add("x-request-id", "req-test");
            if (retryAfter != null) exchange.getResponseHeaders().add("Retry-After", retryAfter);
            if (status == 302) exchange.getResponseHeaders().add("Location", "https://example.invalid/never-follow");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var stream = exchange.getResponseBody()) {
                if (slowBody) {
                    stream.write(bytes, 0, 1); stream.flush();
                    try { Thread.sleep(600); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    stream.write(bytes, 1, bytes.length - 1);
                } else stream.write(bytes);
            } catch (java.io.IOException ignored) { /* timeout이 연결을 닫는 정상적인 테스트 경로. */ }
        });
        server.start();
        client = client(Duration.ofSeconds(2));
    }

    @AfterEach
    void cleanup() { server.stop(0); }

    @Test
    void sendsStrictSchemaWithoutToolsAndParsesActualUsage() {
        var result = client.generate(lease(client));
        assertEquals(0, result.questions().size());
        assertEquals(16, result.metadata().inputTokens());
        assertEquals(7, result.metadata().outputTokens());
        assertEquals("req-test", result.metadata().requestId());
        var request = received.get();
        assertEquals("gpt-6-luna", request.path("model").asText());
        assertFalse(request.path("store").asBoolean());
        assertFalse(request.has("tools"));
        assertEquals("none", request.path("reasoning").path("effort").asText());
        assertEquals("json_schema", request.path("text").path("format").path("type").asText());
        assertTrue(request.path("text").path("format").path("strict").asBoolean());
        assertFalse(request.toString().contains("test-key"));
        assertEquals("Bearer " + "test-key-".repeat(4), authorization.get());
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 409, 429, 500, 503})
    void retriesTemporaryFailuresWithServerDelay(int value) {
        status = value;
        retryAfter = "120";
        body = "{\"error\":{\"code\":\"slow_down\"}}";
        var error = assertThrows(Failure.class, () -> client.generate(lease(client)));
        assertTrue(error.retryable());
        assertEquals(120, error.retryAfterSeconds());
        assertEquals("req-test", error.metadata().requestId());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 302})
    void doesNotRetryPermanentErrorsOrFollowRedirects(int value) {
        status = value;
        body = "{\"error\":{\"message\":\"untrusted provider text\"}}";
        var error = assertThrows(Failure.class, () -> client.generate(lease(client)));
        assertFalse(error.retryable());
        assertFalse(error.getMessage().contains("untrusted provider text"));
    }

    @Test
    void billingQuotaErrorIsNotRetried() {
        status = 429;
        body = "{\"error\":{\"code\":\"insufficient_quota\"}}";
        var error = assertThrows(Failure.class, () -> client.generate(lease(client)));
        assertEquals("AI_QUOTA_EXCEEDED", error.code());
        assertFalse(error.retryable());
    }

    @Test
    void parsesHttpDateRetryAfterAsMinimumDelay() {
        status = 503;
        retryAfter = "Sat, 31 Oct 2026 00:02:00 GMT";
        var error = assertThrows(Failure.class, () -> client.generate(lease(client)));
        assertEquals(120, error.retryAfterSeconds());
    }

    @Test
    void refusalAndIncompleteResponsesKeepKnownTokenUsage() {
        body = response("completed", Map.of("type", "refusal", "refusal", "not allowed"));
        var refusal = assertThrows(Failure.class, () -> client.generate(lease(client)));
        assertEquals("AI_REFUSED", refusal.code());
        assertEquals(7, refusal.metadata().outputTokens());
        body = response("incomplete", Map.of("type", "output_text", "text", "{}"));
        var incomplete = assertThrows(Failure.class, () -> client.generate(lease(client)));
        assertEquals("AI_INCOMPLETE_OUTPUT", incomplete.code());
        assertFalse(incomplete.retryable());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}{}", "{\"questions\":[],\"unexpected\":1}", "[1]", "not-json"})
    void rejectsMalformedStructuredOutput(String json) {
        body = response("completed", Map.of("type", "output_text", "text", json));
        assertEquals("AI_INVALID_OUTPUT", assertThrows(Failure.class, () -> client.generate(lease(client))).code());
    }

    @Test
    void boundsTheWholeResponseIncludingSlowBody() {
        slowBody = true;
        client = client(Duration.ofMillis(150));
        long started = System.nanoTime();
        assertEquals("AI_CONNECTION_ERROR", assertThrows(Failure.class, () -> client.generate(lease(client))).code());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1500);
    }

    @Test
    void refusesAnOversizedResponseWithoutRetryingIt() {
        body = "x".repeat(270 * 1024);
        var error = assertThrows(Failure.class, () -> client.generate(lease(client)));
        assertEquals("AI_RESPONSE_TOO_LARGE", error.code());
        assertFalse(error.retryable());
    }

    @Test
    void refusesToRunAJobWithChangedPromptPolicy() {
        var old = lease(client);
        var stale = new Lease(old.taskId(), old.companyId(), old.userId(), old.owner(), old.fence(), old.runId(),
                0, 1, 3, 1, 3, 4096, old.model(), "old-policy", old.input());
        assertEquals("AI_POLICY_CHANGED", assertThrows(Failure.class, () -> client.generate(stale)).code());
        assertNull(received.get());
    }

    private OpenAiQuizClient client(Duration timeout) {
        return new OpenAiQuizClient(mapper, new QuizGenerationProperties(true, false, "gpt-6-luna",
                "test-key-".repeat(4), 4096, 3000000, 500), HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"), timeout, clock);
    }

    private Lease lease(OpenAiQuizClient value) {
        var section = new Section(1, 1, "사업보고서", "ANNUAL", LocalDate.of(2026, 3, 1),
                "사업의 내용", "PARAGRAPH", "연구개발에 투자합니다.");
        return new Lease("f8f49b94-b7c4-4c20-8a22-f0016c123456", 1, 1, "owner", 1, 1,
                0, 1, 3, 1, 3, 4096, "gpt-6-luna", value.policyHash(), new Input(List.of(section), List.of()));
    }

    private String response(String state, Object content) {
        return mapper.writeValueAsString(Map.of("status", state, "model", "gpt-6-luna",
                "usage", Map.of("input_tokens", 16, "output_tokens", 7),
                "output", List.of(Map.of("type", "message", "content", List.of(content)))));
    }
}