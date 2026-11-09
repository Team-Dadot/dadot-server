package com.dadot.server.infrastructure.openai;

import com.dadot.server.domain.interview.model.InterviewGeneration;
import com.dadot.server.domain.interview.model.InterviewGeneration.*;
import com.dadot.server.domain.interview.repository.InterviewGenerationRepository;
import com.dadot.server.domain.interview.repository.InterviewGenerationRepository.Claim;
import com.dadot.server.domain.interview.service.InterviewGenerationWorker;
import com.dadot.server.domain.interview.support.InterviewGenerationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InterviewGenerationLengthRetryTest {
    private static final Instant NOW =
            Instant.parse("2026-11-09T03:00:00Z");

    private final JsonMapper mapper = new JsonMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final Input input = new Input(
            "회사",
            new Job(null, "영업", true),
            List.of(),
            List.of(new Section(1, 2, "사업", "해외 판매 확대")),
            List.of(),
            List.of()
    );

    private final InterviewGenerationProperties properties =
            new InterviewGenerationProperties(
                    true,
                    false,
                    "gpt-6-luna",
                    "test-key-".repeat(4),
                    3_000_000,
                    40_000
            );

    @Test
    void distinguishesQuestionLengthFromEvidenceFailures() {
        assertEquals(
                "QUESTION_TOO_LONG",
                problem("가".repeat(101), 2, 1, "해외 판매").reason()
        );
        assertEquals(
                "QUESTION_EMPTY",
                problem(" ", 2, 1, "해외 판매").reason()
        );
        assertEquals(
                "EVIDENCE_QUOTE_MISMATCH",
                problem("전략은?", 2, 1, "없는 원문").reason()
        );
        assertEquals(
                "EVIDENCE_VERSION_MISMATCH",
                problem("전략은?", 3, 1, "해외 판매").reason()
        );
        assertEquals(
                "EVIDENCE_SECTION_UNKNOWN",
                problem("전략은?", 2, 9, "해외 판매").reason()
        );
        assertEquals(
                "EVIDENCE_ID_INVALID",
                problem("전략은?", 2, 0, "해외 판매").reason()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "필요할까요?",
            "어떤 점을 우선하겠",
            "설명해 보세요.",
            "인재 확보 방안은?"
    })
    void onlyQuestionLengthAndNonBlankAreValidated(String question) {
        assertEquals(
                question,
                InterviewGeneration.validate(
                        mapper.readTree(json(question, 2, 1, "해외 판매")),
                        input
                ).question()
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void lengthFailuresUseSameTaskAndRetryOnlyBeforeThirdAttempt(
            int attempt
    ) {
        Failure rejected = problem(
                "가".repeat(101), 2, 1, "해외 판매"
        );

        Failure recorded = runFailure(attempt, rejected);

        assertEquals("QUESTION_GENERATION_FAILED", recorded.code());
        assertEquals("QUESTION_TOO_LONG", recorded.reason());
        assertEquals(attempt < 3, recorded.retryable());
    }

    @Test
    void evidenceFailuresAreLoggedWithoutChangingTheirRetryPolicy() {
        Failure recorded = runFailure(
                1,
                problem("전략은?", 2, 1, "없는 원문")
        );

        assertEquals("EVIDENCE_QUOTE_MISMATCH", recorded.reason());
        assertFalse(recorded.retryable());
    }

    @Test
    void providerQuotaFailureIsNotRetried() {
        assertFalse(
                runFailure(
                        1,
                        new Failure("AI_QUOTA_EXCEEDED", false)
                ).retryable()
        );
    }

    @Test
    void preservesMetadataAndBoundsExistingNetworkRetries() {
        Metadata metadata = new Metadata(
                "request-id", "gpt-6-luna", 20, 10
        );

        Failure failure = new Failure(
                "AI_HTTP_429", true, 60, metadata
        );

        Failure recorded = runFailure(3, failure);

        assertFalse(recorded.retryable());
        assertEquals(metadata, recorded.metadata());
        assertEquals(60, recorded.retryAfter());
    }

    @Test
    void saveDuplicateHasDistinctReasonAndKeepsProviderMetadata() {
        InterviewGenerationRepository repository =
                mock(InterviewGenerationRepository.class);
        OpenAiInterviewClient client =
                mock(OpenAiInterviewClient.class);

        Claim claim = prepare(repository, client, 1);

        Metadata metadata = new Metadata(
                "saved-request", "gpt-6-luna", 20, 10
        );

        Output output = new Output(
                new Generated(
                        "전략은?",
                        List.of(new Quote(2, 1, "해외 판매"))
                ),
                metadata
        );

        when(client.generate(any())).thenReturn(output);

        doThrow(InterviewGeneration.invalidOutput())
                .when(repository)
                .save(claim, output, NOW);

        worker(repository, client).runOnce();

        ArgumentCaptor<Failure> failure =
                ArgumentCaptor.forClass(Failure.class);

        verify(repository).fail(
                eq(claim), failure.capture(), eq(NOW)
        );

        assertEquals(
                "QUESTION_DUPLICATE",
                failure.getValue().reason()
        );
        assertEquals(
                metadata,
                failure.getValue().metadata()
        );
        assertFalse(failure.getValue().retryable());
    }

    @Test
    @SuppressWarnings("unchecked")
    void clientPreservesLengthReasonAndAddsShorteningHintOnNextAttempt() {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);

        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(
                HttpHeaders.of(
                        Map.of(
                                "x-request-id",
                                List.of("test-request")
                        ),
                        (a, b) -> true
                )
        );

        String envelope = mapper.writeValueAsString(
                Map.of(
                        "status", "completed",
                        "model", "gpt-6-luna",
                        "output", List.of(
                                Map.of(
                                        "type", "message",
                                        "content", List.of(
                                                Map.of(
                                                        "type", "output_text",
                                                        "text",
                                                        json(
                                                                "가".repeat(101),
                                                                2,
                                                                1,
                                                                "해외 판매"
                                                        )
                                                )
                                        )
                                )
                        )
                )
        );

        when(response.body()).thenReturn(
                envelope.getBytes(StandardCharsets.UTF_8)
        );

        when(
                http.sendAsync(
                        any(HttpRequest.class),
                        org.mockito.ArgumentMatchers
                                .<HttpResponse.BodyHandler<byte[]>>any()
                )
        ).thenReturn(
                CompletableFuture.completedFuture(response)
        );

        OpenAiInterviewClient client = new OpenAiInterviewClient(
                mapper,
                properties,
                http,
                URI.create("http://example.invalid/responses"),
                Duration.ofSeconds(2),
                clock
        );

        Failure failure = assertThrows(
                Failure.class,
                () -> client.generate(lease(client, 2))
        );

        assertEquals("QUESTION_TOO_LONG", failure.reason());
        assertEquals(
                "test-request",
                failure.metadata().requestId()
        );

        ArgumentCaptor<HttpRequest> request =
                ArgumentCaptor.forClass(HttpRequest.class);

        verify(http, times(1)).sendAsync(
                request.capture(),
                org.mockito.ArgumentMatchers
                        .<HttpResponse.BodyHandler<byte[]>>any()
        );

        HttpResponse.BodySubscriber<byte[]> subscriber =
                HttpResponse.BodySubscribers.ofByteArray();

        request.getValue()
                .bodyPublisher()
                .orElseThrow()
                .subscribe(new Flow.Subscriber<ByteBuffer>() {
                    public void onSubscribe(
                            Flow.Subscription subscription
                    ) {
                        subscriber.onSubscribe(subscription);
                    }

                    public void onNext(ByteBuffer value) {
                        subscriber.onNext(List.of(value));
                    }

                    public void onError(Throwable error) {
                        subscriber.onError(error);
                    }

                    public void onComplete() {
                        subscriber.onComplete();
                    }
                });

        String instructions = mapper.readTree(
                subscriber.getBody().toCompletableFuture().join()
        ).path("instructions").asText();

        assertTrue(instructions.contains("재시도 지침:"));
        assertTrue(instructions.contains("50~80자"));
        assertFalse(instructions.contains("마지막 문장은"));
    }

    private Failure problem(
            String question,
            long version,
            long section,
            String quote
    ) {
        return assertThrows(
                Failure.class,
                () -> InterviewGeneration.validate(
                        mapper.readTree(
                                json(question, version, section, quote)
                        ),
                        input
                )
        );
    }

    private String json(
            String question,
            long version,
            long section,
            String quote
    ) {
        return mapper.writeValueAsString(
                Map.of(
                        "question", question,
                        "evidence", List.of(
                                Map.of(
                                        "disclosureVersionId", version,
                                        "sectionId", section,
                                        "quote", quote
                                )
                        )
                )
        );
    }

    private Failure runFailure(int attempt, Failure failure) {
        InterviewGenerationRepository repository =
                mock(InterviewGenerationRepository.class);
        OpenAiInterviewClient client =
                mock(OpenAiInterviewClient.class);

        Claim claim = prepare(repository, client, attempt);

        when(client.generate(any())).thenThrow(failure);

        worker(repository, client).runOnce();

        ArgumentCaptor<Failure> recorded =
                ArgumentCaptor.forClass(Failure.class);

        verify(repository).fail(
                eq(claim), recorded.capture(), eq(NOW)
        );
        verify(client, times(1)).generate(any());
        verify(repository, never()).save(any(), any(), any());

        return recorded.getValue();
    }

    private Claim prepare(
            InterviewGenerationRepository repository,
            OpenAiInterviewClient client,
            int attempt
    ) {
        Claim claim = new Claim(
                "task", 1, 1, "owner", 1, attempt, attempt
        );

        when(client.ready()).thenReturn(true);
        when(repository.claim(NOW)).thenReturn(claim);

        Lease value = lease(client, attempt);
        when(repository.load(claim, NOW)).thenReturn(value);

        return claim;
    }

    private Lease lease(OpenAiInterviewClient client, int attempt) {
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

    private InterviewGenerationWorker worker(
            InterviewGenerationRepository repository,
            OpenAiInterviewClient client
    ) {
        PlatformTransactionManager manager =
                mock(PlatformTransactionManager.class);

        when(
                manager.getTransaction(any(TransactionDefinition.class))
        ).thenAnswer(
                invocation -> new SimpleTransactionStatus()
        );

        return new InterviewGenerationWorker(
                repository, client, properties, clock, manager
        );
    }
}