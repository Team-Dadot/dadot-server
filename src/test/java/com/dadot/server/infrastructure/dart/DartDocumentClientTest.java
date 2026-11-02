package com.dadot.server.infrastructure.dart;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.dadot.server.infrastructure.dart.DartOriginalFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class DartDocumentClientTest {
    @TempDir Path directory;
    private HttpServer server; private HttpClient http; private DartDocumentClient client;
    private volatile byte[] body; private volatile int status; private volatile boolean stall;
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicReference<String> query = new AtomicReference<>();
    @BeforeEach void setup() throws Exception {
        body = zip("<P>원문</P>"); status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/document.xml", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery()); exchange.sendResponseHeaders(status, body.length);
            if (stall) try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            try (var output = exchange.getResponseBody()) { output.write(body); } catch (java.io.IOException ignored) {}
        }); server.start();
        http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        client = new DartDocumentClient("a".repeat(40), http, endpoint());
    }
    @AfterEach void cleanup() { release.countDown(); server.stop(0); http.close(); }
    @Test void downloadsOriginalBytesAndUsesReceiptNumber() throws Exception {
        Path target = directory.resolve("original.zip"); client.download(RECEIPT, target);
        assertArrayEquals(body, Files.readAllBytes(target)); assertTrue(query.get().contains("rcept_no=" + RECEIPT));
    }
    @ParameterizedTest @CsvSource({"010,DART_AUTH_ERROR,false", "011,DART_AUTH_ERROR,false", "012,DART_AUTH_ERROR,false",
            "901,DART_AUTH_ERROR,false", "013,DART_ORIGINAL_NOT_FOUND,false", "014,DART_ORIGINAL_NOT_FOUND,false",
            "020,DART_RATE_LIMIT,false", "800,DART_TEMPORARY_ERROR,true", "900,DART_TEMPORARY_ERROR,true"})
    void distinguishesXmlErrorsInsideHttp200(String statusCode, String code, boolean retryable) {
        body = ("<result><status>" + statusCode + "</status><message>" + "a".repeat(40) + "</message></result>").getBytes(StandardCharsets.UTF_8);
        var failure = assertThrows(DartFailure.class, () -> client.download(RECEIPT, directory.resolve("response")));
        assertEquals(code, failure.code()); assertEquals(retryable, failure.retryable());
        assertFalse(failure.toString().contains("a".repeat(40))); assertNull(failure.getCause());
    }
    @ParameterizedTest @CsvSource({"429,DART_RATE_LIMIT,false", "503,DART_HTTP_ERROR,true", "302,DART_HTTP_ERROR,false"})
    void distinguishesHttpErrors(int statusCode, String code, boolean retryable) {
        status = statusCode;
        var failure = assertThrows(DartFailure.class, () -> client.download(RECEIPT, directory.resolve("response")));
        assertEquals(code, failure.code()); assertEquals(retryable, failure.retryable());
    }
    @Test void boundsDownloadSize() {
        body = new byte[20 * 1024 * 1024 + 1]; body[0] = 'P'; body[1] = 'K'; body[2] = 3; body[3] = 4;
        assertEquals("DART_DOCUMENT_LIMIT", assertThrows(DartFailure.class, () -> client.download(RECEIPT, directory.resolve("huge.zip"))).code());
    }
    @Test void timesOutWhileWaitingForBodyNotOnlyHeaders() {
        stall = true;
        var shortTimeout = new DartDocumentClient("a".repeat(40), http, endpoint(), Duration.ofMillis(150));
        long start = System.nanoTime();
        try {
            assertEquals("DART_CONNECTION_ERROR", assertThrows(DartFailure.class,
                    () -> shortTimeout.download(RECEIPT, directory.resolve("slow.zip"))).code());
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1500);
        } finally { release.countDown(); }
    }
    @Test void rejectsMalformedAndUnsafeErrorDocuments() {
        for (String value : new String[]{"not zip", "<result><status>000</status></result>",
                "<!DOCTYPE result [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><result><status>&x;</status></result>"}) {
            body = value.getBytes(StandardCharsets.UTF_8);
            assertEquals("DART_INVALID_RESPONSE", assertThrows(DartFailure.class,
                    () -> client.download(RECEIPT, directory.resolve("invalid"))).code());
        }
    }
    @Test void reportsFileWriteFailuresWithoutCredentialBearingCauses() {
        var failure = assertThrows(DartFailure.class, () -> client.download(RECEIPT, directory.resolve("missing/file")));
        assertEquals("DART_STORAGE_ERROR", failure.code()); assertNull(failure.getCause());
    }
    @Test void encodesCredentialsAndRejectsInvalidReceiptBeforeRequest() {
        var encoded = new DartDocumentClient("z".repeat(39) + "&", http, endpoint());
        encoded.download(RECEIPT, directory.resolve("original.zip"));
        assertTrue(query.get().contains("%26&rcept_no="));
        assertThrows(IllegalArgumentException.class, () -> client.download("../escape", directory.resolve("bad")));
    }
    private URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/document.xml"); }
}
