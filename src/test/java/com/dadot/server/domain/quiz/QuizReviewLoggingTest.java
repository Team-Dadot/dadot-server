package com.dadot.server.domain.quiz;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dadot.server.domain.company.support.CompanyLogoUrlResolver;
import com.dadot.server.domain.quiz.model.QuizReview.*;
import com.dadot.server.domain.quiz.repository.QuizPlayRepository;
import com.dadot.server.domain.quiz.repository.QuizReviewRepository;
import com.dadot.server.domain.quiz.service.QuizReviewService;
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
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.SQLException;
import java.time.Duration;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class QuizReviewLoggingTest {

    private static final String PRIVATE = "private-test-marker SQL password token";
    private final QuizReviewRepository repository = mock(QuizReviewRepository.class);
    private final UserAccess access = mock(UserAccess.class);
    private final SessionAuthenticationToken actor = mock(SessionAuthenticationToken.class);
    private final Paging paging = new Paging(0, 20);
    private final Logger logger = (Logger) LoggerFactory.getLogger(QuizReviewService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private QuizReviewService service;

    @BeforeEach
    void setup() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(access.require(actor, false)).thenReturn(1L);
        service = new QuizReviewService(repository, mock(QuizPlayRepository.class),
                access, mock(CompanyLogoUrlResolver.class), manager);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void cleanup() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void recordsSqlMetadataWithoutMessagesOrThrowable() {
        var sql = new SQLException(PRIVATE, "40001", 1213);
        var failure = new TransientDataAccessResourceException(PRIVATE, sql);
        when(repository.counts(1, 2)).thenThrow(failure);

        unavailable(() -> service.questions(actor, new Search(2, Filter.ALL, paging)));

        var message = singleMessage();
        assertTrue(message.contains("operation=questions"));
        assertTrue(message.contains("exceptionClass=" + failure.getClass().getName()));
        assertTrue(message.contains("rootCauseClass=java.sql.SQLException"));
        assertTrue(message.contains("sqlState=40001"));
        assertTrue(message.contains("dbErrorCode=1213"));
    }

    @Test
    void recordsCompaniesOperationAndNullSqlMetadata() {
        when(repository.companies(1, paging)).thenThrow(
                new DataAccessResourceFailureException(PRIVATE, new IllegalStateException(PRIVATE)));

        unavailable(() -> service.companies(actor, paging));

        var message = singleMessage();
        assertTrue(message.contains("operation=companies"));
        assertTrue(message.contains("rootCauseClass=java.lang.IllegalStateException"));
        assertTrue(message.contains("sqlState=null, dbErrorCode=null"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"40001\nprivate-test-marker", "password=private-test-marker"})
    void detailRejectsSqlStateContainingUnexpectedText(String state) {
        when(repository.latest(1, 2)).thenThrow(new DataAccessResourceFailureException(
                PRIVATE, new SQLException(PRIVATE, state, 1040)));

        unavailable(() -> service.detail(actor, 2));

        var message = singleMessage();
        assertTrue(message.contains("operation=detail"));
        assertTrue(message.contains("sqlState=null, dbErrorCode=1040"));
    }

    @Test
    void cyclicCauseChainTerminatesWithoutLoggingExceptionObjects() {
        var first = new IllegalStateException(PRIVATE);
        var sql = new SQLException(PRIVATE, "08006", 0);
        first.initCause(sql);
        sql.initCause(first);
        when(repository.companies(1, paging)).thenThrow(
                new DataAccessResourceFailureException(PRIVATE, first));

        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> unavailable(() -> service.companies(actor, paging)));

        assertTrue(singleMessage().contains("sqlState=08006"));
    }

    @ParameterizedTest
    @MethodSource("storageFailures")
    void caughtStorageFailuresRetainServiceUnavailableResponse(RuntimeException failure) {
        when(repository.companies(1, paging)).thenThrow(failure);

        unavailable(() -> service.companies(actor, paging));

        assertTrue(singleMessage().contains("exceptionClass=" + failure.getClass().getName()));
    }

    @Test
    void businessFailureKeepsItsCodeAndDoesNotProduceStorageWarning() {
        when(access.require(actor, false)).thenThrow(new BusinessException(ErrorCode.UNAUTHENTICATED));

        var failure = assertThrows(BusinessException.class, () -> service.companies(actor, paging));

        assertEquals(ErrorCode.UNAUTHENTICATED, failure.getErrorCode());
        assertTrue(logs.list.isEmpty());
    }

    private static Stream<RuntimeException> storageFailures() {
        return Stream.of(new TransientDataAccessResourceException(PRIVATE),
                new DataAccessResourceFailureException(PRIVATE),
                new CannotCreateTransactionException(PRIVATE),
                new TransactionTimedOutException(PRIVATE));
    }

    private void unavailable(Runnable operation) {
        var failure = assertThrows(BusinessException.class, operation::run);
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, failure.getErrorCode());
    }

    private String singleMessage() {
        assertEquals(1, logs.list.size());
        var event = logs.list.getFirst();
        assertNull(event.getThrowableProxy());
        assertFalse(event.getFormattedMessage().contains("private-test-marker"));
        for (Object argument : event.getArgumentArray()) {
            assertFalse(argument instanceof Throwable);
        }
        return event.getFormattedMessage();
    }
}