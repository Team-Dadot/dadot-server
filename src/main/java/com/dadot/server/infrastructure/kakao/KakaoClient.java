package com.dadot.server.infrastructure.kakao;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public class KakaoClient {

    static final String TOKEN_INFO_URL =
            "https://kapi.kakao.com/v1/user/access_token_info";

    private static final int MAX_RESPONSE_BYTES = 16_384;

    private final long appId;
    private final RestClient client;
    private final JsonMapper mapper;
    private final Clock clock;

    @Autowired
    public KakaoClient(
            @Value("${app.kakao.app-id:0}") long appId,
            JsonMapper mapper,
            Clock clock
    ) {
        this(appId, createClient(), mapper, clock);
    }

    KakaoClient(
            long appId,
            RestClient client,
            JsonMapper mapper,
            Clock clock
    ) {
        if (appId < 0) {
            throw new IllegalArgumentException(
                    "Invalid Kakao app ID"
            );
        }

        this.appId = appId;
        this.client = client;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Verified verify(String accessToken) {
        if (appId == 0) {
            throw unavailable();
        }

        Instant requestedAt = clock.instant();

        try {
            return client.get()
                    .uri(TOKEN_INFO_URL)
                    .headers(headers ->
                            headers.setBearerAuth(accessToken)
                    )
                    .exchange((request, response) -> {
                        int status =
                                response.getStatusCode().value();

                        if (status == 401) {
                            throw invalid();
                        }

                        if (status != 200 && status != 400) {
                            throw unavailable();
                        }

                        byte[] body = response.getBody()
                                .readNBytes(MAX_RESPONSE_BYTES + 1);

                        if (body.length > MAX_RESPONSE_BYTES) {
                            throw unavailable();
                        }

                        JsonNode json = mapper.readTree(body);

                        if (json == null || !json.isObject()) {
                            throw unavailable();
                        }

                        if (status == 400) {
                            JsonNode code = json.get("code");

                            if (code != null
                                    && code.isIntegralNumber()
                                    && code.canConvertToLong()
                                    && (
                                    code.longValue() == -401
                                            || code.longValue() == -2
                            )) {
                                throw invalid();
                            }

                            throw unavailable();
                        }

                        long id = positiveLong(json, "id");
                        long issuedApp =
                                positiveLong(json, "app_id");
                        long remaining =
                                positiveLong(json, "expires_in");

                        if (issuedApp != appId) {
                            throw invalid();
                        }

                        if (remaining > 604_800) {
                            throw unavailable();
                        }

                        Instant validUntil =
                                requestedAt.plusSeconds(remaining);

                        if (!validUntil.isAfter(clock.instant())) {
                            throw invalid();
                        }

                        return new Verified(
                                Long.toString(id),
                                validUntil
                        );
                    });

        } catch (RestClientException | JacksonException ex) {
            // 외부 응답 본문과 Authorization 헤더를 남기지 않는다.
            throw unavailable();
        }
    }

    private static long positiveLong(
            JsonNode json,
            String name
    ) {
        JsonNode value = json.get(name);

        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()) {
            throw unavailable();
        }

        if (value.longValue() <= 0) {
            if ("expires_in".equals(name)) {
                throw invalid();
            }

            throw unavailable();
        }

        return value.longValue();
    }

    private static RestClient createClient() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    private static BusinessException invalid() {
        return new BusinessException(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL
        );
    }

    private static BusinessException unavailable() {
        return new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE
        );
    }

    public record Verified(
            String subject,
            Instant validUntil
    ) {

        public Verified {
            if (subject == null
                    || !subject.matches("[1-9][0-9]{0,18}")
                    || validUntil == null) {
                throw new IllegalArgumentException(
                        "Invalid verified Kakao identity"
                );
            }
        }

        @Override
        public String toString() {
            return "Verified[identity=REDACTED]";
        }
    }
}