package com.dadot.server.infrastructure.dart;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class DartDocumentClient {

    private final String key;
    private final HttpClient http;
    private final URI endpoint;
    private final Duration timeout;

    @Autowired
    public DartDocumentClient(
            @Value("${DART_API_KEY:${app.dart.api-key:}}") String key
    ) {
        this(
                key,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                URI.create(
                        "https://opendart.fss.or.kr/api/document.xml"
                )
        );
    }

    DartDocumentClient(String key, HttpClient http, URI endpoint) {
        this(key, http, endpoint, Duration.ofSeconds(45));
    }

    DartDocumentClient(
            String key,
            HttpClient http,
            URI endpoint,
            Duration timeout
    ) {
        this.key = key;
        this.http = http;
        this.endpoint = endpoint;
        this.timeout = timeout;
    }

    public void download(String receipt, Path target) {
        if (!key.matches("[!-~]{40}")) {
            throw new DartFailure("DART_NOT_CONFIGURED", false);
        }

        if (!receipt.matches("[0-9]{14}")) {
            throw new IllegalArgumentException("Invalid DART receipt");
        }

        var request = HttpRequest.newBuilder(
                        URI.create(
                                endpoint
                                        + "?crtfc_key="
                                        + URLEncoder.encode(
                                        key,
                                        StandardCharsets.UTF_8
                                )
                                        + "&rcept_no="
                                        + receipt
                        )
                )
                .timeout(timeout)
                .GET()
                .build();

        var pending = http.sendAsync(
                request,
                info -> new FileBody(target)
        );

        try {
            var response = pending.get(
                    timeout.toMillis(),
                    TimeUnit.MILLISECONDS
            );

            int status = response.statusCode();

            if (status != 200) {
                throw new DartFailure(
                        status == 429
                                ? "DART_RATE_LIMIT"
                                : "DART_HTTP_ERROR",
                        status == 408 || status >= 500
                );
            }

            try (var input = Files.newInputStream(target)) {
                byte[] prefix = input.readNBytes(4);

                if (prefix.length == 4
                        && prefix[0] == 'P'
                        && prefix[1] == 'K'
                        && prefix[2] == 3
                        && prefix[3] == 4) {
                    return;
                }
            }

            throw responseFailure(target);

        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new DartFailure("DART_INTERRUPTED", true);

        } catch (TimeoutException ex) {
            pending.cancel(true);
            throw new DartFailure("DART_CONNECTION_ERROR", true);

        } catch (ExecutionException ex) {
            pending.cancel(true);

            if (ex.getCause() instanceof DartFailure failure) {
                throw failure;
            }

            throw new DartFailure("DART_CONNECTION_ERROR", true);

        } catch (IOException ex) {
            throw new DartFailure("DART_STORAGE_ERROR", true);
        }
    }

    private DartFailure responseFailure(Path response) {
        try {
            if (Files.size(response) > 65536) {
                return new DartFailure("DART_INVALID_RESPONSE", false);
            }

            var factory = DocumentBuilderFactory.newInstance();

            factory.setFeature(
                    XMLConstants.FEATURE_SECURE_PROCESSING,
                    true
            );
            factory.setFeature(
                    "http://apache.org/xml/features/disallow-doctype-decl",
                    true
            );
            factory.setFeature(
                    "http://xml.org/sax/features/external-general-entities",
                    false
            );
            factory.setFeature(
                    "http://xml.org/sax/features/external-parameter-entities",
                    false
            );
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            var parser = factory.newDocumentBuilder();

            parser.setErrorHandler(
                    new org.xml.sax.helpers.DefaultHandler()
            );

            try (var stream = Files.newInputStream(response)) {
                var document = parser.parse(stream);

                if (!document.getDocumentElement()
                        .getTagName()
                        .equals("result")) {
                    return new DartFailure(
                            "DART_INVALID_RESPONSE",
                            false
                    );
                }

                var values = document.getElementsByTagName("status");

                if (values.getLength() != 1) {
                    return new DartFailure(
                            "DART_INVALID_RESPONSE",
                            false
                    );
                }

                return switch (values.item(0).getTextContent()) {
                    case "010", "011", "012", "901" ->
                            new DartFailure("DART_AUTH_ERROR", false);

                    case "020" ->
                            new DartFailure("DART_RATE_LIMIT", false);

                    case "013", "014" ->
                            new DartFailure(
                                    "DART_ORIGINAL_NOT_FOUND",
                                    false
                            );

                    case "800", "900" ->
                            new DartFailure(
                                    "DART_TEMPORARY_ERROR",
                                    true
                            );

                    default ->
                            new DartFailure(
                                    "DART_INVALID_RESPONSE",
                                    false
                            );
                };
            }

        } catch (Exception ex) {
            return new DartFailure("DART_INVALID_RESPONSE", false);
        }
    }

    private static final class FileBody
            implements HttpResponse.BodySubscriber<Path> {

        private final CompletableFuture<Path> result =
                new CompletableFuture<>();

        private final Path target;

        private OutputStream output;
        private Flow.Subscription subscription;
        private long bytes;

        FileBody(Path target) {
            this.target = target;
        }

        @Override
        public CompletionStage<Path> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;

            try {
                output = Files.newOutputStream(target);
                value.request(1);

            } catch (IOException ex) {
                value.cancel();

                result.completeExceptionally(
                        new DartFailure("DART_STORAGE_ERROR", true)
                );
            }
        }

        @Override
        public void onNext(List<ByteBuffer> chunks) {
            try {
                for (var chunk : chunks) {
                    int length = chunk.remaining();

                    if ((bytes += length) > 20L * 1024 * 1024) {
                        throw new DartFailure(
                                "DART_DOCUMENT_LIMIT",
                                false
                        );
                    }

                    byte[] buffer = new byte[length];
                    chunk.get(buffer);
                    output.write(buffer);
                }

                subscription.request(1);

            } catch (DartFailure ex) {
                fail(ex);

            } catch (IOException ex) {
                fail(new DartFailure("DART_STORAGE_ERROR", true));
            }
        }

        @Override
        public void onError(Throwable ex) {
            close();
            result.completeExceptionally(ex);
        }

        @Override
        public void onComplete() {
            try {
                output.close();
                result.complete(target);

            } catch (IOException ex) {
                result.completeExceptionally(
                        new DartFailure("DART_STORAGE_ERROR", true)
                );
            }
        }

        private void fail(DartFailure ex) {
            subscription.cancel();
            close();
            result.completeExceptionally(ex);
        }

        private void close() {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}