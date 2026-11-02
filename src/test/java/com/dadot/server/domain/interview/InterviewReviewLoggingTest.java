package com.dadot.server.domain.interview;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dadot.server.domain.interview.repository.*;
import com.dadot.server.domain.interview.service.InterviewReviewService;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.SessionAuthenticationToken;
import com.dadot.server.global.security.UserAccess;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InterviewReviewLoggingTest {
    private static final String PRIVATE = "private-test-marker SQL answer password";
    private final UserAccess access = mock(UserAccess.class);
    private final SessionAuthenticationToken actor = mock(SessionAuthenticationToken.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(InterviewReviewService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private InterviewReviewService service;

    @BeforeEach
    void setup() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new InterviewReviewService(mock(InterviewReviewRepository.class),
                mock(InterviewReadRepository.class), access, Clock.systemUTC(), manager);
        logs.start(); logger.addAppender(logs);
    }

    @AfterEach
    void cleanup() { logger.detachAppender(logs); logs.stop(); }

    @ParameterizedTest
    @ValueSource(strings = {"questions", "attempts", "deleteQuestion", "deleteAttempt"})
    void onlyLogsFixedOperationAndSafeSqlMetadata(String operation) {
        when(access.require(eq(actor), anyBoolean())).thenThrow(new DataAccessResourceFailureException(
                PRIVATE, new SQLException(PRIVATE, "40001", 1213)));
        unavailable(() -> {
            switch (operation) {
                case "questions" -> service.questions(actor, 1, new com.dadot.server.domain.interview.model.InterviewReview.Paging(0, 20));
                case "attempts" -> service.attempts(actor, 1, new com.dadot.server.domain.interview.model.InterviewReview.Paging(0, 20));
                case "deleteQuestion" -> service.deleteQuestion(actor, 1);
                case "deleteAttempt" -> service.deleteAttempt(actor, 1);
                default -> fail("Unknown test operation");
            }
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
        unavailable(() -> service.questions(actor, 1, new com.dadot.server.domain.interview.model.InterviewReview.Paging(0, 20)));
        assertTrue(message().contains("sqlState=null, dbErrorCode=1040"));
    }

    @Test
    void noSqlCauseAndBusinessErrorsRemainSafe() {
        when(access.require(actor, false)).thenThrow(new DataAccessResourceFailureException(PRIVATE));
        unavailable(() -> service.questions(actor, 1, new com.dadot.server.domain.interview.model.InterviewReview.Paging(0, 20)));
        assertTrue(message().contains("sqlState=null, dbErrorCode=null"));
        logs.list.clear();
        doThrow(new BusinessException(ErrorCode.UNAUTHENTICATED)).when(access).require(actor, false);
        assertEquals(ErrorCode.UNAUTHENTICATED, assertThrows(BusinessException.class, () -> service.questions(actor, 1, new com.dadot.server.domain.interview.model.InterviewReview.Paging(0, 20))).getErrorCode());
        assertTrue(logs.list.isEmpty());
    }

    @Test
    void cyclicCauseChainTerminates() {
        var first = new IllegalStateException(PRIVATE);
        var sql = new SQLException(PRIVATE, "08006", 0);
        first.initCause(sql); sql.initCause(first);
        when(access.require(actor, false)).thenThrow(new DataAccessResourceFailureException(PRIVATE, first));
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> unavailable(() -> service.questions(actor, 1, new com.dadot.server.domain.interview.model.InterviewReview.Paging(0, 20))));
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