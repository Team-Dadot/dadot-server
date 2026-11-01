package com.dadot.server.infrastructure.kakao;

import com.dadot.server.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KakaoAccountClientTest {
    private MockRestServiceServer server;
    private KakaoAccountClient client;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        client = new KakaoAccountClient(
                "a".repeat(32),
                builder.build(),
                JsonMapper.builder().build()
        );
    }

    @Test
    void unlinksOnlyTrustedSubjectWithAdminKey() {
        server.expect(
                        requestTo(
                                "https://kapi.kakao.com/v1/user/unlink"
                        )
                )
                .andExpect(method(HttpMethod.POST))
                .andExpect(
                        header(
                                "Authorization",
                                "KakaoAK " + "a".repeat(32)
                        )
                )
                .andExpect(
                        content().string(
                                "target_id_type=user_id&target_id=12345"
                        )
                )
                .andRespond(
                        withSuccess(
                                "{\"id\":12345}",
                                MediaType.APPLICATION_JSON
                        )
                );

        client.unlink("12345");

        server.verify();
    }

    @Test
    void alreadyUnlinkedSubjectIsIdempotent() {
        server.expect(
                        requestTo(
                                "https://kapi.kakao.com/v1/user/unlink"
                        )
                )
                .andRespond(
                        withStatus(HttpStatus.BAD_REQUEST)
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .body("{\"code\":-101}")
                );

        client.unlink("12345");

        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"id\":99999}",
            "{\"id\":\"12345\"}",
            "{}",
            "[]",
            "x"
    })
    void rejectsUnexpectedResponses(String body) {
        server.expect(
                        requestTo(
                                "https://kapi.kakao.com/v1/user/unlink"
                        )
                )
                .andRespond(
                        withSuccess(body, MediaType.APPLICATION_JSON)
                );

        assertThrows(
                BusinessException.class,
                () -> client.unlink("12345")
        );

        server.verify();
    }

    @Test
    void wrongAdminKeyIsNotTreatedAsAlreadyUnlinked() {
        server.expect(
                        requestTo(
                                "https://kapi.kakao.com/v1/user/unlink"
                        )
                )
                .andRespond(
                        withStatus(HttpStatus.UNAUTHORIZED)
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .body("{\"code\":-401}")
                );

        assertThrows(
                BusinessException.class,
                () -> client.unlink("12345")
        );

        server.verify();
    }

    @Test
    void overflowingErrorCodeCannotBecomeAlreadyUnlinked() {
        server.expect(
                        requestTo(
                                "https://kapi.kakao.com/v1/user/unlink"
                        )
                )
                .andRespond(
                        withStatus(HttpStatus.BAD_REQUEST)
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .body("{\"code\":4294967195}")
                );

        assertThrows(
                BusinessException.class,
                () -> client.unlink("12345")
        );

        server.verify();
    }

    @Test
    void missingConfigurationAndInvalidSubjectNeverSendHttp() {
        assertThrows(
                BusinessException.class,
                () -> new KakaoAccountClient(
                        "",
                        JsonMapper.builder().build()
                ).unlink("12345")
        );

        assertThrows(
                BusinessException.class,
                () -> client.unlink("12345&other=1")
        );

        server.verify();
    }
}