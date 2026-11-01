package com.dadot.server.domain.notification.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.sql.SQLException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class NotificationSettingsLoggingTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(NotificationSettingsService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

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
    @ValueSource(strings = {"40001", "08006", "08006\nprivate-marker", "private-marker"})
    void logsOnlyAllowedMetadata(String state) {
        var result = assertThrows(BusinessException.class, () -> NotificationSettingsService.storage("patch", () -> {
            throw new DataAccessResourceFailureException("private-marker SQL token",
                    new SQLException("private-marker", state, 1213));
        }));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, result.getErrorCode());
        assertNull(result.getCause());
        String allowed = state != null && state.matches("[0-9A-Z]{5}") ? state : "null";
        assertTrue(message().contains("sqlState=" + allowed + ", dbErrorCode=1213"));
    }

    @Test
    void cyclicCauseChainTerminates() {
        var first = new IllegalStateException("private-marker");
        var second = new SQLException("private-marker", "40001", 0);
        first.initCause(second);
        second.initCause(first);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(BusinessException.class,
                () -> NotificationSettingsService.storage("get", () -> {
                    throw new DataAccessResourceFailureException("private-marker", first);
                })));
        assertTrue(message().contains("sqlState=40001"));
    }

    @Test
    void businessErrorsAreNotTranslatedOrLogged() {
        var original = new BusinessException(ErrorCode.VERSION_CONFLICT);
        assertSame(original, assertThrows(BusinessException.class,
                () -> NotificationSettingsService.storage("patch", () -> { throw original; })));
        assertTrue(logs.list.isEmpty());
    }

    private String message() {
        assertEquals(1, logs.list.size());
        var entry = logs.list.getFirst();
        assertNull(entry.getThrowableProxy());
        assertFalse(entry.getFormattedMessage().contains("private-marker"));
        return entry.getFormattedMessage();
    }
}