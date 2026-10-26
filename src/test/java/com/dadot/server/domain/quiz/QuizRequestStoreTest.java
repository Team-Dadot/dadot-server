package com.dadot.server.domain.quiz;

import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.quiz.model.QuizBank.View;
import com.dadot.server.domain.quiz.repository.QuizRequestStore;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuizRequestStoreTest {

    @Test
    void expirationIsCheckedAfterQueryWhenMaintenanceAlreadyClearedPayload()
            throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var clock = mock(Clock.class);

        var time = new AtomicReference<>(
                Instant.parse("2026-10-26T00:00:00Z")
        );

        var expires = time.get().plusSeconds(1);

        when(clock.instant()).thenAnswer(
                invocation -> time.get()
        );

        var rs = mock(ResultSet.class);

        when(rs.getString("body_hmac"))
                .thenReturn("body");

        when(rs.getObject(
                "expires_at",
                LocalDateTime.class
        )).thenReturn(
                LocalDateTime.ofInstant(
                        expires,
                        ZoneOffset.UTC
                )
        );

        when(rs.getString("response_payload"))
                .thenReturn(null);

        doAnswer(invocation -> {
            time.set(expires.plusMillis(1));

            RowMapper<View> mapper = invocation.getArgument(1);

            return List.of(
                    mapper.mapRow(rs, 0)
            );
        }).when(jdbc).query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<View>>any(),
                eq("scope"),
                eq(QuizRequestStore.OPERATION),
                eq("key")
        );

        var store = new QuizRequestStore(
                jdbc,
                mock(VerificationCrypto.class),
                JsonMapper.builder().build(),
                clock
        );

        var failure = assertThrows(
                BusinessException.class,
                () -> store.replay(
                        new QuizRequestStore.Key(
                                "scope",
                                "key",
                                "body"
                        ),
                        1
                )
        );

        assertEquals(
                ErrorCode.IDEMPOTENCY_KEY_EXPIRED,
                failure.getErrorCode()
        );
    }
}