package com.dadot.server.domain.interview;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dadot.server.domain.interview.repository.*;
import com.dadot.server.domain.interview.service.InterviewEvaluationService;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.SessionAuthenticationToken;
import com.dadot.server.global.security.UserAccess;
import com.dadot.server.infrastructure.openai.OpenAiEvaluationClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InterviewEvaluationLoggingTest {
    private static final String PRIVATE = "private-test-marker SQL answer password";
    private final UserAccess access = mock(UserAccess.class);
    private final SessionAuthenticationToken actor = mock(SessionAuthenticationToken.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(InterviewEvaluationService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private InterviewEvaluationService service;

    @BeforeEach
    void setup() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new InterviewEvaluationService(mock(InterviewSubmissionRepository.class),
                mock(InterviewEvaluationResultRepository.class), mock(InterviewEvaluationInputRepository.class),
                mock(InterviewEvaluationJobRepository.class), mock(InterviewEvaluationRequestStore.class),
                mock(OpenAiEvaluationClient.class), access, Clock.systemUTC(), mock(JdbcTemplate.class), manager);
        logs.start(); logger.addAppender(logs);
    }

    @AfterEach
    void cleanup() { logger.detachAppender(logs); logs.stop(); }

    @ParameterizedTest
    @ValueSource(strings = {"view", "retry"})
    void onlyLogsFixedOperationAndSafeSqlMetadata(String operation) {
        when(access.require(eq(actor), anyBoolean())).thenThrow(new DataAccessResourceFailureException(
                PRIVATE, new SQLException(PRIVATE, "40001", 1213)));
        unavailable(() -> {
            if (operation.equals("view")) service.view(actor, 1);
            else service.retry(actor, 1, "550e8400-e29b-41d4-a716-446655440000");
        });
        String message = message();
        assertTrue(message.contains("operation=" + operation));
        assertTrue(message.contains("rootCauseClass=java.sql.SQLException"));
        assertTrue(message.contains("sqlState=40001, dbErrorCode=1213"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"40001\nprivate-test-marker", "private-test-marker"})
    void rejectsUntrustedSqlState(String state) {
        when(access.require(actor, false)).thenThrow(new DataAccessResourceFailureException(
                PRIVATE, new SQLException(PRIVATE, state, 1040)));
        unavailable(() -> service.view(actor, 1));
        assertTrue(message().contains("sqlState=null, dbErrorCode=1040"));
    }

    @Test
    void noSqlCauseAndBusinessErrorsRemainSafe() {
        when(access.require(actor, false)).thenThrow(new DataAccessResourceFailureException(PRIVATE));
        unavailable(() -> service.view(actor, 1));
        assertTrue(message().contains("sqlState=null, dbErrorCode=null"));
        logs.list.clear();
        doThrow(new BusinessException(ErrorCode.UNAUTHENTICATED)).when(access).require(actor, false);
        assertEquals(ErrorCode.UNAUTHENTICATED, assertThrows(BusinessException.class, () -> service.view(actor, 1)).getErrorCode());
        assertTrue(logs.list.isEmpty());
    }

    @Test
    void cyclicCauseChainTerminates() {
        var first = new IllegalStateException(PRIVATE);
        var sql = new SQLException(PRIVATE, "08006", 0);
        first.initCause(sql); sql.initCause(first);
        when(access.require(actor, false)).thenThrow(new DataAccessResourceFailureException(PRIVATE, first));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> unavailable(() -> service.view(actor, 1)));
        assertTrue(message().contains("sqlState=08006"));
    }

    private static void unavailable(Runnable operation) {
        var failure = assertThrows(BusinessException.class, operation::run);
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, failure.getErrorCode());
        assertNull(failure.getCause());
    }

    private String message() {
        assertEquals(1, logs.list.size());
        var event = logs.list.getFirst(); assertNull(event.getThrowableProxy());
        assertFalse(event.getFormattedMessage().contains("private-test-marker"));
        for (Object value : event.getArgumentArray()) {
            assertFalse(value instanceof Throwable);
            assertFalse(String.valueOf(value).contains("private-test-marker"));
        }
        return event.getFormattedMessage();
    }
}