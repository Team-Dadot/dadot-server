package com.dadot.server.infrastructure.dart;

import com.dadot.server.domain.disclosure.model.DisclosureSync.ReportType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DartClientTest {
    private HttpServer server;
    private DartClient client;
    private String body;
    private int status;

    private final AtomicReference<String> query = new AtomicReference<>();
    private final LocalDate day = LocalDate.of(2026, 1, 1);

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/list.json", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(bytes);
            }
        });

        server.start();
        status = 200;

        client = new DartClient(
                new JsonMapper(), "a".repeat(40), true,
                HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/list.json")
        );
    }

    @AfterEach
    void cleanup() {
        server.stop(0);
    }

    @Test
    void sendsAllReportsIncludingCorrectionsAndClassifiesActualTitles() {
        body = response("[기재정정]사업보고서 (2025.12)");
        var page = client.list("00126380", day, day, 1);

        assertEquals(ReportType.ANNUAL, page.filings().getFirst().reportType());
        assertEquals(1, page.totalCount());
        assertTrue(query.get().contains("last_reprt_at=N"));
        assertTrue(query.get().contains("pblntf_ty=A"));
        assertTrue(query.get().contains("page_count=100"));
        assertTrue(query.get().contains("bgn_de=20260101"));
    }

    @ParameterizedTest
    @CsvSource({
            "반기보고서 (2025.06),HALF_YEAR",
            "분기보고서 (2025.09),QUARTERLY"
    })
    void classifiesHalfYearAndQuarterly(String title, ReportType type) {
        body = response(title);
        assertEquals(
                type, client.list("00126380", day, day, 1).filings().getFirst().reportType()
        );
    }

    @Test
    void excludesUnrelatedReports() {
        body = response("회계법인사업보고서 (2025.12)");
        assertTrue(client.list("00126380", day, day, 1).filings().isEmpty());
    }

    @Test
    void onlyNoDataStatusIsAnEmptyResult() {
        body = "{\"status\":\"013\"}";

        assertEquals(0, client.list("00126380", day, day, 1).totalCount());
        assertTrue(assertThrows(
                DartFailure.class,
                () -> client.list("00126380", day, day, 2)
        ).retryable());
    }

    @ParameterizedTest
    @CsvSource({
            "010,DART_AUTH_ERROR,false",
            "011,DART_AUTH_ERROR,false",
            "012,DART_AUTH_ERROR,false",
            "901,DART_AUTH_ERROR,false",
            "020,DART_RATE_LIMIT,false",
            "800,DART_TEMPORARY_ERROR,true",
            "900,DART_TEMPORARY_ERROR,true",
            "100,DART_REJECTED,false"
    })
    void distinguishesErrorsInsideHttp200(String code, String expected, boolean retry) {
        body = "{\"status\":\"" + code + "\",\"message\":\"secret-" + "a".repeat(40) + "\"}";

        var error = assertThrows(
                DartFailure.class,
                () -> client.list("00126380", day, day, 1)
        );

        assertEquals(expected, error.code());
        assertEquals(retry, error.retryable());
        assertFalse(error.toString().contains("a".repeat(40)));
        assertNull(error.getCause());
    }

    @ParameterizedTest
    @CsvSource({
            "429,DART_RATE_LIMIT,false",
            "503,DART_HTTP_ERROR,true",
            "302,DART_HTTP_ERROR,false"
    })
    void handlesHttpErrorsWithoutFollowingRedirects(
            int httpStatus, String code, boolean retry
    ) {
        status = httpStatus;
        body = "error";

        var ex = assertThrows(
                DartFailure.class,
                () -> client.list("00126380", day, day, 1)
        );

        assertEquals(code, ex.code());
        assertEquals(retry, ex.retryable());
    }

    @Test
    void rejectsMalformedForeignAndOutOfRangeResponses() {
        for (String value : new String[]{
                "not-json",
                response("사업보고서").replace("00126380", "99999999"),
                response("사업보고서").replace("20260101", "20260102"),
                response("사업보고서").replace("\"total_count\":1", "\"total_count\":101"),
                response("사업보고서").replace("20260101000001", "bad")
        }) {
            body = value;

            assertEquals(
                    "DART_INVALID_RESPONSE",
                    assertThrows(
                            DartFailure.class,
                            () -> client.list("00126380", day, day, 1)
                    ).code()
            );
        }
    }

    @Test
    void boundsResponseMemory() {
        body = "x".repeat(2 * 1024 * 1024 + 1);
        assertThrows(DartFailure.class, () -> client.list("00126380", day, day, 1));
    }

    @Test
    void disabledConfigurationMakesNoRequest() {
        var disabled = new DartClient(new JsonMapper(), "", false);
        assertFalse(disabled.ready());

        assertEquals(
                "DART_NOT_CONFIGURED",
                assertThrows(
                        DartFailure.class,
                        () -> disabled.list("00126380", day, day, 1)
                ).code()
        );
    }

    @Test
    void encodesKeyInsteadOfAssumingHexOrAllowingQueryInjection() {
        body = "{\"status\":\"013\"}";

        var encoded = new DartClient(
                new JsonMapper(), "z".repeat(39) + "&", true,
                HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/list.json")
        );

        assertTrue(encoded.ready());
        encoded.list("00126380", day, day, 1);
        assertTrue(query.get().contains("%26&corp_code="));
    }

    private String response(String title) {
        return """
                {"status":"000","page_no":1,"page_count":100,"total_count":1,"total_page":1,"list":[
                {"corp_code":"00126380","rcept_no":"20260101000001","rcept_dt":"20260101","report_nm":"%s"}]}
                """.formatted(title);
    }
}