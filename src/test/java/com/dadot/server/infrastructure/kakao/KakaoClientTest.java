package com.dadot.server.infrastructure.kakao;

import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KakaoClientTest {

    private static final String TOKEN =
            "TEST_KAKAO_ACCESS_TOKEN";

    private static final Instant NOW =
            Instant.parse("2026-10-24T00:00:00Z");

    private MockRestServiceServer server;
    private KakaoClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder =
                RestClient.builder();

        server = MockRestServiceServer
                .bindTo(builder)
                .build();

        client = new KakaoClient(
                1620145,
                builder.build(),
                JsonMapper.builder().build(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void checksOurAppAndGetsSubjectWithoutProfileScopes() {
        server.expect(
                        requestTo(KakaoClient.TOKEN_INFO_URL)
                )
                .andExpect(method(HttpMethod.GET))
                .andExpect(
                        header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + TOKEN
                        )
                )
                .andRespond(
                        withSuccess(
                                """
                                {
                                  "id": 12345,
                                  "app_id": 1620145,
                                  "expires_in": 3600
                                }
                                """,
                                MediaType.APPLICATION_JSON
                        )
                );

        var result = client.verify(TOKEN);

        assertEquals("12345", result.subject());
        assertEquals(
                NOW.plusSeconds(3600),
                result.validUntil()
        );
        assertFalse(
                result.toString().contains("12345")
        );

        server.verify();
    }

    @Test
    void rejectsAnotherApp() {
        expectJson(
                """
                {
                  "id": 12345,
                  "app_id": 777,
                  "expires_in": 3600
                }
                """
        );

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL
        );
    }

    @Test
    void rejectsExpiredToken() {
        expectJson(
                """
                {
                  "id": 12345,
                  "app_id": 1620145,
                  "expires_in": 0
                }
                """
        );

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL
        );
    }

    @Test
    void rejectsUnauthorized() {
        server.expect(
                        requestTo(KakaoClient.TOKEN_INFO_URL)
                )
                .andRespond(
                        withStatus(HttpStatus.UNAUTHORIZED)
                );

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL
        );
    }

    @Test
    void recognizesInvalidTokenCodeInBadRequest() {
        server.expect(
                        requestTo(KakaoClient.TOKEN_INFO_URL)
                )
                .andRespond(
                        withBadRequest()
                                .body("{\"code\":-401}")
                );

        assertCode(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL
        );
    }

    @Test
    void upstreamBadRequestIsNotAlwaysInvalidCredentials() {
        server.expect(
                        requestTo(KakaoClient.TOKEN_INFO_URL)
                )
                .andRespond(
                        withBadRequest()
                                .body("{\"code\":-1}")
                );

        assertCode(
                ErrorCode.SERVICE_UNAVAILABLE
        );
    }

    @Test
    void upstreamErrorsAndRedirectsDoNotBecomeSuccess() {
        for (HttpStatus status : new HttpStatus[]{
                HttpStatus.FORBIDDEN,
                HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.INTERNAL_SERVER_ERROR,
                HttpStatus.FOUND
        }) {
            setUp();

            server.expect(
                            requestTo(KakaoClient.TOKEN_INFO_URL)
                    )
                    .andRespond(withStatus(status));

            assertCode(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }

    @Test
    void networkFailureDoesNotLeakCredentialOrUpstreamMessage() {
        server.expect(
                        requestTo(KakaoClient.TOKEN_INFO_URL)
                )
                .andRespond(
                        withException(
                                new IOException(
                                        "sensitive:" + TOKEN
                                )
                        )
                );

        var ex = assertThrows(
                BusinessException.class,
                () -> client.verify(TOKEN)
        );

        assertEquals(
                ErrorCode.SERVICE_UNAVAILABLE,
                ex.getErrorCode()
        );
        assertFalse(ex.toString().contains(TOKEN));
        assertNull(ex.getCause());
    }

    @Test
    void malformedOrMissingIdentityFieldsFailClosed() {
        for (String json : new String[]{
                "null",
                "[]",
                "{",
                "{}",
                """
                {"id":"123","app_id":1620145,"expires_in":1}
                """,
                """
                {"id":1.5,"app_id":1620145,"expires_in":1}
                """,
                """
                {"id":0,"app_id":1620145,"expires_in":1}
                """,
                """
                {"id":1,"app_id":1620145}
                """,
                """
                {"id":1,"app_id":1620145,"expires_in":999999999999999999999}
                """,
                """
                {"id":999999999999999999999,"app_id":1620145,"expires_in":1}
                """
        }) {
            setUp();
            expectJson(json);

            assertCode(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }

    @Test
    void oversizedResponseIsRejected() {
        expectJson(" ".repeat(16385));

        assertCode(
                ErrorCode.SERVICE_UNAVAILABLE
        );
    }

    @Test
    void unsetAppIdCannotMakeNetworkRequest() {
        var disabled = new KakaoClient(
                0,
                RestClient.create(),
                JsonMapper.builder().build(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertEquals(
                ErrorCode.SERVICE_UNAVAILABLE,
                assertThrows(
                        BusinessException.class,
                        () -> disabled.verify(TOKEN)
                ).getErrorCode()
        );
    }

    private void expectJson(String json) {
        server.expect(
                        requestTo(KakaoClient.TOKEN_INFO_URL)
                )
                .andRespond(
                        withSuccess(
                                json,
                                MediaType.APPLICATION_JSON
                        )
                );
    }

    private void assertCode(ErrorCode code) {
        assertEquals(
                code,
                assertThrows(
                        BusinessException.class,
                        () -> client.verify(TOKEN)
                ).getErrorCode()
        );

        server.verify();
    }
}