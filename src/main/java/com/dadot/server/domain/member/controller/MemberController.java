package com.dadot.server.domain.member.controller;

import com.dadot.server.domain.member.model.Member;
import com.dadot.server.domain.member.model.Member.*;
import com.dadot.server.domain.member.service.AccountDeletionService;
import com.dadot.server.domain.member.service.MemberService;
import com.dadot.server.global.response.ApiResponse;
import com.dadot.server.global.security.SessionAuthenticationToken;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;

import java.io.IOException;
import java.util.Collections;

@RestController
@RequiredArgsConstructor
public class MemberController {
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final MemberService members;
    private final AccountDeletionService deletion;

    @Operation(summary = "내 정보·마이 요약 조회")
    @GetMapping("/v1/users/me")
    public ResponseEntity<ApiResponse<Summary>> summary(
            Authentication auth,
            HttpServletRequest request
    ) {
        noQuery(request);

        try {
            if (request.getInputStream().read() != -1) {
                throw Member.invalid();
            }
        } catch (IOException ex) {
            throw Member.invalid();
        }

        return ok(members.summary(actor(auth)));
    }

    @Operation(summary = "닉네임 수정")
    @PatchMapping(
            value = "/v1/users/me/profile",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse<Profile>> profile(
            Authentication auth,
            HttpServletRequest request
    ) {
        noQuery(request);

        return ok(
                members.nickname(
                        actor(auth),
                        Nickname.parse(body(request))
                )
        );
    }

    @Operation(summary = "본인 확인 후 회원 탈퇴")
    @DeleteMapping(
            value = "/v1/users/me",
            consumes = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<Void> delete(
            Authentication auth,
            HttpServletRequest request
    ) {
        noQuery(request);

        var keys = Collections.list(
                request.getHeaders("Idempotency-Key")
        );

        if (keys.size() != 1) {
            throw Member.invalid();
        }

        deletion.delete(
                actor(auth),
                Delete.parse(body(request)),
                keys.getFirst(),
                request.getRemoteAddr()
        );

        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }

    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) {
            throw Member.invalid();
        }
    }

    private static JsonNode body(HttpServletRequest request) {
        try {
            byte[] bytes = request.getInputStream().readNBytes(16385);

            if (bytes.length > 16384) {
                throw Member.invalid();
            }

            return JSON.readTree(bytes);
        } catch (IOException | JacksonException ex) {
            throw Member.invalid();
        }
    }

    private static SessionAuthenticationToken actor(
            Authentication auth
    ) {
        if (auth instanceof SessionAuthenticationToken token
                && token.isAuthenticated()) {
            return token;
        }

        throw Member.unauthenticated();
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(data));
    }
}