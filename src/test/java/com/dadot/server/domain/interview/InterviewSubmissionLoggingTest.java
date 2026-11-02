package com.dadot.server.domain.interview;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dadot.server.domain.interview.model.InterviewGeneration.Attempt;
import com.dadot.server.domain.interview.model.InterviewSubmission.SaveDraft;
import com.dadot.server.domain.interview.model.InterviewSubmission.Submit;
import com.dadot.server.domain.interview.repository.InterviewReadRepository;
import com.dadot.server.domain.interview.repository.InterviewSubmissionRepository;
import com.dadot.server.domain.interview.repository.InterviewSubmissionRequestStore;
import com.dadot.server.domain.interview.service.InterviewSubmissionService;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.SessionAuthenticationToken;
import com.dadot.server.global.security.UserAccess;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InterviewSubmissionLoggingTest {
    private static final String PRIVATE = "private-test-marker answer SQL password token";
    private final UserAccess access = mock(UserAccess.class);
    private final SessionAuthenticationToken actor = mock(SessionAuthenticationToken.class);
    private final InterviewSubmissionRepository repository = mock(InterviewSubmissionRepository.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(InterviewSubmissionService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private InterviewSubmissionService service;
    private Level originalLevel;

    @BeforeEach
    void setup() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(access.require(eq(actor), anyBoolean())).thenReturn(1L);
        service = new InterviewSubmissionService(repository,
                mock(InterviewSubmissionRequestStore.class), mock(InterviewReadRepository.class),
                access, Clock.fixed(Instant.parse("2026-10-31T10:00:00Z"), ZoneOffset.UTC), manager);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.WARN);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void cleanup() {
        logger.detachAppender(logs);
        logger.setLevel(originalLevel);
        logs.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"save", "submit", "detail"})
    void logsFixedOperationAndSqlMetadataWithoutSensitiveContents(String operation) {
        var sql = new SQLException(PRIVATE, "40001", 1213);
        var failure = new TransientDataAccessResourceException(PRIVATE, sql);
        when(access.require(eq(actor), anyBoolean())).thenThrow(failure);

        unavailable(() -> invoke(operation));

        String message = singleMessage();
        assertTrue(message.contains("operation=" + operation));
        assertTrue(message.contains("exceptionClass=" + failure.getClass().getName()));
        assertTrue(message.contains("rootCauseClass=java.sql.SQLException"));
        assertTrue(message.contains("sqlState=40001, dbErrorCode=1213"));
    }

    @Test
    void noSqlCauseProducesNullDatabaseMetadata() {
        when(access.require(actor, false)).thenThrow(new DataAccessResourceFailureException(
                PRIVATE, new IllegalStateException(PRIVATE)));

        unavailable(() -> service.detail(actor, 2));

        String message = singleMessage();
        assertTrue(message.contains("rootCauseClass=java.lang.IllegalStateException"));
        assertTrue(message.contains("sqlState=null, dbErrorCode=null"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"40001\nprivate-test-marker", "password=private-test-marker", "0800x", ""})
    void rejectsUnexpectedSqlStateContents(String state) {
        when(access.require(actor, false)).thenThrow(new DataAccessResourceFailureException(
                PRIVATE, new SQLException(PRIVATE, state, 1040)));

        unavailable(() -> service.detail(actor, 2));

        assertTrue(singleMessage().contains("sqlState=null, dbErrorCode=1040"));
    }

    @Test
    void cyclicCausesTerminateWithoutLoggingThrowable() {
        var root = new IllegalStateException(PRIVATE);
        var sql = new SQLException(PRIVATE, "08006", 0);
        root.initCause(sql);
        sql.initCause(root);
        when(access.require(actor, false)).thenThrow(new DataAccessResourceFailureException(PRIVATE, root));

        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> unavailable(() -> service.detail(actor, 2)));

        assertTrue(singleMessage().contains("sqlState=08006, dbErrorCode=0"));
    }

    @ParameterizedTest
    @MethodSource("storageFailures")
    void caughtStorageFailuresStillBecomeServiceUnavailable(RuntimeException failure) {
        when(access.require(actor, false)).thenThrow(failure);

        unavailable(() -> service.detail(actor, 2));

        assertTrue(singleMessage().contains("exceptionClass=" + failure.getClass().getName()));
    }

    @Test
    void businessErrorsKeepTheirCodeWithoutStorageWarning() {
        var expected = new BusinessException(ErrorCode.UNAUTHENTICATED);
        when(access.require(actor, false)).thenThrow(expected);

        assertSame(expected, assertThrows(BusinessException.class, () -> service.detail(actor, 2)));
        assertTrue(logs.list.isEmpty());
    }

    @Test
    void sqlProgrammingErrorsAreNotConvertedToTemporaryFailures() {
        var expected = new BadSqlGrammarException("test", PRIVATE, new SQLException(PRIVATE));
        when(access.require(actor, false)).thenThrow(expected);

        assertSame(expected, assertThrows(BadSqlGrammarException.class, () -> service.detail(actor, 2)));
        assertTrue(logs.list.isEmpty());
    }

    @Test
    void successfulReadsDoNotProduceStorageWarning() {
        var expected = new Attempt(2, 3, 1, PRIVATE, Instant.parse("2026-10-31T10:00:00Z"),
                "550e8400-e29b-41d4-a716-446655440000", "PROCESSING");
        when(repository.attempt(1, 2)).thenReturn(expected);

        assertEquals(expected, service.detail(actor, 2));
        assertTrue(logs.list.isEmpty());
    }

    private void invoke(String operation) {
        switch (operation) {
            case "save" -> service.save(actor, 2, new SaveDraft(PRIVATE, null));
            case "submit" -> service.submit(actor, 2, "550e8400-e29b-41d4-a716-446655440000",
                    new Submit(PRIVATE, null, false));
            case "detail" -> service.detail(actor, 2);
            default -> throw new IllegalArgumentException("Unknown test operation");
        }
    }

    private static Stream<RuntimeException> storageFailures() {
        return Stream.of(new TransientDataAccessResourceException(PRIVATE),
                new DataAccessResourceFailureException(PRIVATE),
                new CannotCreateTransactionException(PRIVATE),
                new TransactionTimedOutException(PRIVATE));
    }

    private static void unavailable(Runnable operation) {
        var failure = assertThrows(BusinessException.class, operation::run);
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, failure.getErrorCode());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("private-test-marker"));
    }

    private String singleMessage() {
        assertEquals(1, logs.list.size());
        var event = logs.list.getFirst();
        assertEquals(Level.WARN, event.getLevel());
        assertNull(event.getThrowableProxy());
        assertFalse(event.getFormattedMessage().contains("private-test-marker"));
        for (Object argument : event.getArgumentArray()) {
            assertFalse(argument instanceof Throwable);
            assertFalse(String.valueOf(argument).contains("private-test-marker"));
        }
        return event.getFormattedMessage();
    }
}