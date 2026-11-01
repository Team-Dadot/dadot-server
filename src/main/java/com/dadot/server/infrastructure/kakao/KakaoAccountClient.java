package com.dadot.server.infrastructure.kakao;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class KakaoAccountClient {
    private final String adminKey;
    private final RestClient client;
    private final JsonMapper mapper;

    @Autowired
    public KakaoAccountClient(
            @Value("${app.kakao.admin-key:${KAKAO_ADMIN_KEY:}}")
            String adminKey,
            JsonMapper mapper
    ) {
        this(adminKey, client(), mapper);
    }

    KakaoAccountClient(
            String adminKey,
            RestClient client,
            JsonMapper mapper
    ) {
        this.adminKey = adminKey;
        this.client = client;
        this.mapper = mapper;
    }

    public void checkConfigured() {
        if (!adminKey.matches("[A-Za-z0-9]{32,128}")) {
            throw unavailable();
        }
    }

    public void unlink(String subject) {
        checkConfigured();

        if (subject == null
                || !subject.matches("[1-9][0-9]{0,18}")) {
            throw unavailable();
        }

        var form = new LinkedMultiValueMap<String, String>();
        form.add("target_id_type", "user_id");
        form.add("target_id", subject);

        try {
            client.post()
                    .uri("https://kapi.kakao.com/v1/user/unlink")
                    .header("Authorization", "KakaoAK " + adminKey)
                    .contentType(
                            MediaType.APPLICATION_FORM_URLENCODED
                    )
                    .body(form)
                    .exchange((request, response) -> {
                        byte[] bytes = response.getBody()
                                .readNBytes(16385);

                        if (bytes.length > 16384) {
                            throw unavailable();
                        }

                        var json = mapper.readTree(bytes);

                        if (json == null || !json.isObject()) {
                            throw unavailable();
                        }

                        int status = response.getStatusCode().value();

                        // -101은 이미 앱과 연결되지 않은 사용자다.
                        // 잘못된 관리자 키(-401)는 성공 처리하지 않는다.
                        if (status == 400
                                && json.path("code").isIntegralNumber()
                                && json.path("code").canConvertToInt()
                                && json.path("code").intValue() == -101) {
                            return true;
                        }

                        if (status != 200
                                || !json.path("id").isIntegralNumber()
                                || !json.path("id").canConvertToLong()
                                || !subject.equals(
                                Long.toString(
                                        json.path("id").longValue()
                                )
                        )) {
                            throw unavailable();
                        }

                        return true;
                    });
        } catch (RestClientException | JacksonException ex) {
            throw unavailable();
        }
    }

    private static RestClient client() {
        var http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));

        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    private static BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
    }
}