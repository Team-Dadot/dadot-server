package com.dadot.server.infrastructure.kakao;

import com.dadot.server.domain.auth.support.SocialNickname;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/** 이미 검증한 카카오 토큰에서 가입용 표시 이름만 조회한다. 인증 판정에는 사용하지 않는다. */
@Component
public class KakaoProfileClient {
    static final URI PROFILE_URI = URI.create(
            "https://kapi.kakao.com/v2/user/me"
                    + "?property_keys=%5B%22kakao_account.profile%22%5D");
    private static final int MAX_RESPONSE_BYTES = 16_384;

    private final RestClient client;
    private final JsonMapper mapper;

    @Autowired
    public KakaoProfileClient(JsonMapper mapper) {
        this(client(), mapper);
    }

    KakaoProfileClient(RestClient client, JsonMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    public String nickname(String accessToken, String verifiedSubject) {
        try {
            return client.get().uri(PROFILE_URI)
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() != 200) return null;
                        byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                        if (bytes.length > MAX_RESPONSE_BYTES) return null;
                        var json = mapper.readTree(bytes);
                        if (json == null || !json.isObject()) return null;
                        var id = json.path("id");
                        if (!id.isIntegralNumber() || !id.canConvertToLong()
                                || !Long.toString(id.longValue()).equals(verifiedSubject)) {
                            return null;
                        }
                        var name = json.path("kakao_account").path("profile").path("nickname");
                        return name.isString() ? SocialNickname.normalize(name.asString()) : null;
                    });
        } catch (RestClientException | JacksonException ex) {
            // 프로필 미제공·조회 장애 때문에 기존 인증 성공을 실패로 바꾸지 않는다.
            // 토큰·응답·이름은 로그에 남기지 않는다.
            return null;
        }
    }

    private static RestClient client() {
        var http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().requestFactory(factory).build();
    }
}
