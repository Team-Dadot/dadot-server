package com.dadot.server.infrastructure.kakao;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KakaoProfileClientTest {
    private MockRestServiceServer server;
    private KakaoProfileClient client;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KakaoProfileClient(builder.build(), JsonMapper.builder().build());
    }

    @Test
    void readsConsentedNicknameForVerifiedSubjectWithOnlyProfileProperties() {
        server.expect(requestTo(KakaoProfileClient.PROFILE_URI))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer TEST_TOKEN"))
                .andRespond(withSuccess(profile(" 진영🐱 "), MediaType.APPLICATION_JSON));
        assertEquals("진영🐱", client.nickname("TEST_TOKEN", "12345"));
        server.verify();
    }

    @Test
    void missingConsentOrNicknameKeepsFallbackAvailable() {
        for (String json : new String[]{
                "{\"id\":12345}",
                "{\"id\":12345,\"kakao_account\":{\"profile_nickname_needs_agreement\":true}}",
                "{\"id\":12345,\"kakao_account\":{\"profile\":{}}}",
                profile(""), profile(" "), profile("가".repeat(21))}) {
            setup();
            server.expect(requestTo(KakaoProfileClient.PROFILE_URI))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
            assertNull(client.nickname("TEST_TOKEN", "12345"));
            server.verify();
        }
    }

    @Test
    void mismatchedOrMalformedSubjectNeverProvidesAnotherPersonsNickname() {
        for (String json : new String[]{profile("다른 사람").replace("12345", "99999"),
                profile("다른 사람").replace("12345", "\"12345\""), "null", "[]", "{", "{}"}) {
            setup();
            server.expect(requestTo(KakaoProfileClient.PROFILE_URI))
                    .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
            assertNull(client.nickname("TEST_TOKEN", "12345"));
            server.verify();
        }
    }

    @Test
    void profileHttpFailuresDoNotInvalidateVerifiedLogin() {
        for (HttpStatus status : new HttpStatus[]{HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN,
                HttpStatus.TOO_MANY_REQUESTS, HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.FOUND}) {
            setup();
            server.expect(requestTo(KakaoProfileClient.PROFILE_URI)).andRespond(withStatus(status));
            assertNull(client.nickname("TEST_TOKEN", "12345"));
            server.verify();
        }
    }

    @Test
    void timeoutAndOversizedBodyUseFallback() {
        server.expect(requestTo(KakaoProfileClient.PROFILE_URI))
                .andRespond(withException(new IOException("TEST_TOKEN should not escape")));
        assertNull(client.nickname("TEST_TOKEN", "12345"));
        server.verify();
        setup();
        server.expect(requestTo(KakaoProfileClient.PROFILE_URI))
                .andRespond(withSuccess(" ".repeat(16_385), MediaType.APPLICATION_JSON));
        assertNull(client.nickname("TEST_TOKEN", "12345"));
        server.verify();
    }

    private String profile(String name) {
        return "{\"id\":12345,\"kakao_account\":{\"profile\":{\"nickname\":\"" + name + "\"}}}";
    }
}
