package com.dadot.server.infrastructure.dart;

import com.dadot.server.domain.disclosure.model.DisclosureSync.Filing;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Page;
import com.dadot.server.domain.disclosure.model.DisclosureSync.ReportType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class DartClient {
    private static final int LIMIT = 2 * 1024 * 1024;

    private final JsonMapper mapper;
    private final String key;
    private final boolean enabled;
    private final HttpClient http;
    private final URI endpoint;

    @Autowired
    public DartClient(JsonMapper mapper,
                      @Value("${DART_API_KEY:${app.dart.api-key:}}") String key,
                      @Value("${app.dart.enabled:false}") boolean enabled) {
        this(mapper, key, enabled,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                URI.create("https://opendart.fss.or.kr/api/list.json"));
    }

    // 같은 패키지의 HTTP 계약 테스트에만 로컬 서버 주소를 주입한다.
    DartClient(JsonMapper mapper, String key, boolean enabled, HttpClient http, URI endpoint) {
        this.mapper = mapper;
        this.key = key;
        this.enabled = enabled;
        this.http = http;
        this.endpoint = endpoint;
    }

    public boolean ready() {
        return enabled && key.matches("[!-~]{40}");
    }

    public Page list(String corpCode, LocalDate from, LocalDate to, int page) {
        if (!ready()) throw new DartFailure("DART_NOT_CONFIGURED", false);
        if (!corpCode.matches("[0-9]{8}") || page < 1 || page > 99999)
            throw new DartFailure("DART_INVALID_REQUEST", false);

        String query = "?crtfc_key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&corp_code=" + corpCode
                + "&bgn_de=" + from.format(DateTimeFormatter.BASIC_ISO_DATE)
                + "&end_de=" + to.format(DateTimeFormatter.BASIC_ISO_DATE)
                + "&last_reprt_at=N&pblntf_ty=A&sort=date&sort_mth=asc&page_count=100&page_no=" + page;

        var pending = http.sendAsync(
                HttpRequest.newBuilder(URI.create(endpoint + query))
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build(),
                info -> new LimitedBody()
        );

        try {
            // 응답 헤더뿐 아니라 본문 수신 완료까지 제한한다.
            var response = pending.get(20, TimeUnit.SECONDS);
            int status = response.statusCode();

            if (status != 200)
                throw new DartFailure(
                        status == 429 ? "DART_RATE_LIMIT" : "DART_HTTP_ERROR",
                        status == 408 || status >= 500
                );

            return parse(response.body(), corpCode, from, to, page);

        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new DartFailure("DART_INTERRUPTED", true);

        } catch (ExecutionException | TimeoutException ex) {
            pending.cancel(true);
            throw new DartFailure("DART_CONNECTION_ERROR", true);
        }
    }

    private Page parse(byte[] body, String corp, LocalDate from, LocalDate to, int page) {
        try {
            JsonNode root = mapper.readTree(body);
            String status = text(root, "status");

            if (status.equals("013")) {
                if (page != 1) throw new DartFailure("DART_PAGE_CHANGED", true);
                return new Page(0, 0, "", List.of());
            }

            if (!status.equals("000")) throw switch (status) {
                case "010", "011", "012", "901" -> new DartFailure("DART_AUTH_ERROR", false);
                case "020" -> new DartFailure("DART_RATE_LIMIT", false);
                case "800", "900" -> new DartFailure("DART_TEMPORARY_ERROR", true);
                default -> new DartFailure("DART_REJECTED", false);
            };

            int total = number(root, "total_count");
            int pages = number(root, "total_page");

            if (total < 1 || pages != (total + 99L) / 100 || page > pages || pages > 99999
                    || number(root, "page_no") != page || number(root, "page_count") != 100)
                throw malformed();

            var rows = root.get("list");
            if (rows == null || !rows.isArray()
                    || rows.size() != Math.min(100, total - (page - 1L) * 100))
                throw malformed();

            var filings = new ArrayList<Filing>();
            var receipts = new java.util.HashSet<String>();
            var hashInput = new StringBuilder();

            for (var row : rows) {
                String receipt = text(row, "rcept_no");
                String title = text(row, "report_nm");
                String rawDate = text(row, "rcept_dt");

                if (!corp.equals(text(row, "corp_code"))
                        || !receipt.matches("[0-9]{14}")
                        || !receipts.add(receipt)
                        || title.isBlank()
                        || title.codePointCount(0, title.length()) > 300
                        || !rawDate.matches("[0-9]{8}"))
                    throw malformed();

                LocalDate date = LocalDate.parse(rawDate, DateTimeFormatter.BASIC_ISO_DATE);
                if (date.isBefore(from) || date.isAfter(to)) throw malformed();

                hashInput.append(receipt).append('\n');
                ReportType type = classify(title);
                if (type != null) filings.add(new Filing(receipt, title, type, date));
            }

            String hash = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(hashInput.toString().getBytes(StandardCharsets.UTF_8))
            );

            return new Page(total, pages, hash, List.copyOf(filings));

        } catch (DartFailure ex) {
            throw ex;
        } catch (Exception ex) {
            throw malformed();
        }
    }

    private ReportType classify(String title) {
        String name = title.replaceFirst("^(?:\\[[^\\]]+\\]\\s*)+", "");

        if (name.matches("사업보고서(?:\\s*\\([^)]*\\))?")) return ReportType.ANNUAL;
        if (name.matches("반기보고서(?:\\s*\\([^)]*\\))?")) return ReportType.HALF_YEAR;
        if (name.matches("분기보고서(?:\\s*\\([^)]*\\))?")) return ReportType.QUARTERLY;
        return null;
    }

    private String text(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        if (value == null || !value.isTextual()) throw malformed();
        return value.asText();
    }

    private int number(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || !(value.isTextual() || value.isIntegralNumber())
                || !value.asText().matches("[0-9]{1,9}"))
            throw malformed();
        return Integer.parseInt(value.asText());
    }

    private DartFailure malformed() {
        return new DartFailure("DART_INVALID_RESPONSE", false);
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> chunks) {
            for (var chunk : chunks) {
                if (chunk.remaining() > LIMIT - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("DART response limit exceeded"));
                    return;
                }

                byte[] part = new byte[chunk.remaining()];
                chunk.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable error) {
            body.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            body.complete(bytes.toByteArray());
        }
    }
}