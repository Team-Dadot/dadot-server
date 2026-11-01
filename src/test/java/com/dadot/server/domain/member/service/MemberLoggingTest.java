package com.dadot.server.domain.member.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.sql.SQLException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class MemberLoggingTest {
    private final Logger logger =
            (Logger) LoggerFactory.getLogger(MemberService.class);

    private final ListAppender<ILoggingEvent> logs =
            new ListAppender<>();

    @BeforeEach
    void setup() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void cleanup() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "40001",
            "08006",
            "08006\nprivate-marker",
            "private-marker"
    })
    void logsOnlyAllowedMetadata(String state) {
        var failure = assertThrows(
                BusinessException.class,
                () -> MemberService.storage("delete", () -> {
                    throw new DataAccessResourceFailureException(
                            "private-marker password token SQL",
                            new SQLException(
                                    "private-marker",
                                    state,
                                    1213
                            )
                    );
                })
        );

        assertEquals(
                ErrorCode.SERVICE_UNAVAILABLE,
                failure.getErrorCode()
        );

        assertNull(failure.getCause());

        String allowed =
                state != null && state.matches("[0-9A-Z]{5}")
                        ? state
                        : "null";

        assertTrue(
                message().contains(
                        "sqlState=" + allowed + ", dbErrorCode=1213"
                )
        );
    }

    @Test
    void cyclicCausesTerminateWithoutLoggingThrowable() {
        var first = new IllegalStateException("private-marker");
        var sql = new SQLException(
                "private-marker",
                "40001",
                0
        );

        first.initCause(sql);
        sql.initCause(first);

        assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> assertThrows(
                        BusinessException.class,
                        () -> MemberService.storage("delete", () -> {
                            throw new DataAccessResourceFailureException(
                                    "private-marker",
                                    first
                            );
                        })
                )
        );

        assertTrue(message().contains("sqlState=40001"));
    }

    @Test
    void businessFailureIsUnchangedAndNotLogged() {
        var original = new BusinessException(
                ErrorCode.REAUTHENTICATION_FAILED
        );

        assertSame(
                original,
                assertThrows(
                        BusinessException.class,
                        () -> MemberService.storage("delete", () -> {
                            throw original;
                        })
                )
        );

        assertTrue(logs.list.isEmpty());
    }

    private String message() {
        assertEquals(1, logs.list.size());

        var event = logs.list.getFirst();

        assertNull(event.getThrowableProxy());

        assertFalse(
                event.getFormattedMessage().contains("private-marker")
        );

        for (Object argument : event.getArgumentArray()) {
            assertFalse(argument instanceof Throwable);
            assertFalse(
                    String.valueOf(argument).contains("private-marker")
            );
        }

        return event.getFormattedMessage();
    }
}