package com.dadot.server.domain.quiz.service;

import com.dadot.server.domain.company.support.CompanyLogoUrlResolver;
import com.dadot.server.domain.quiz.model.QuizBank;
import com.dadot.server.domain.quiz.model.QuizPlay;
import com.dadot.server.domain.quiz.model.QuizReview.*;
import com.dadot.server.domain.quiz.repository.QuizPlayRepository;
import com.dadot.server.domain.quiz.repository.QuizReviewRepository;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.SessionAuthenticationToken;
import com.dadot.server.global.security.UserAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.LongFunction;

@Service
public class QuizReviewService {

    private static final Logger log = LoggerFactory.getLogger(QuizReviewService.class);
    private final QuizReviewRepository repository;
    private final QuizPlayRepository snapshots;
    private final UserAccess access;
    private final CompanyLogoUrlResolver logos;
    private final TransactionTemplate read;

    public QuizReviewService(QuizReviewRepository repository, QuizPlayRepository snapshots,
                             UserAccess access, CompanyLogoUrlResolver logos,
                             PlatformTransactionManager manager) {
        this.repository = repository;
        this.snapshots = snapshots;
        this.access = access;
        this.logos = logos;
        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setTimeout(10);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public CompanyPage companies(SessionAuthenticationToken actor, Paging paging) {
        if (paging == null) {
            throw QuizBank.invalid();
        }
        return query("companies", actor, user -> {
            var page = repository.companies(user, paging);
            var content = page.rows().stream().map(company -> new CompanyItem(
                    new QuizPlay.Company(company.id(), company.name(), company.industry(), logo(company)),
                    company.active(), page.quizCounts().getOrDefault(company.id(), 0L),
                    // 면접 질문 저장 기능 도입 전이므로 현재 면접 기록은 존재하지 않는다.
                    0L
            )).toList();
            return new CompanyPage(content, paging.page(), paging.size(), page.total(),
                    paging.totalPages(page.total()), paging.hasNext(page.total()));
        });
    }

    public QuestionPage questions(SessionAuthenticationToken actor, Search search) {
        if (search == null) {
            throw QuizBank.invalid();
        }
        return query("questions", actor, user -> {
            repository.requireCompany(user, search.companyId());
            var counts = repository.counts(user, search.companyId());
            long total = search.filter() == Filter.INCORRECT ? counts.incorrect() : counts.all();
            var paging = search.paging();
            return new QuestionPage(repository.questions(user, search, total),
                    paging.page(), paging.size(), total, paging.totalPages(total),
                    paging.hasNext(total), counts.all(), counts.incorrect());
        });
    }

    public QuizPlay.ResultItem detail(SessionAuthenticationToken actor, long questionId) {
        QuizBank.id(questionId);
        return query("detail", actor, user -> {
            var submitted = repository.latest(user, questionId);
            var item = snapshots.items(submitted.setId()).stream()
                    .filter(row -> row.id() == submitted.itemId()
                            && Objects.equals(row.answerId(), submitted.answerId()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Missing submitted quiz snapshot"));
            var selected = List.of(item);
            var choices = snapshots.choices(selected).get(item.versionId());
            var evidence = snapshots.evidence(selected).getOrDefault(item.versionId(), List.of());
            var question = new QuizPlay.Question(item.id(), item.questionId(), item.versionId(),
                    item.position(), item.text(), choices.options(), item.review());
            return new QuizPlay.ResultItem(question, submitted.answerId(), item.selected(),
                    choices.correctId(), item.correct(), item.submittedAt(), item.explanation(), evidence,
                    item.available() ? null : "근거 자료가 변경되었거나 현재 제공이 중단된 문제입니다.");
        });
    }

    private String logo(QuizReviewRepository.CompanyRow company) {
        try {
            return logos.resolve(company.logoKey());
        } catch (IllegalStateException ex) {
            log.warn("Invalid review company logo key: companyId={}", company.id());
            return null;
        }
    }

    private <T> T query(String operation, SessionAuthenticationToken actor, LongFunction<T> action) {
        try {
            return Objects.requireNonNull(read.execute(tx -> action.apply(access.require(actor, false))));
        } catch (TransientDataAccessException | DataAccessResourceFailureException
                 | CannotCreateTransactionException | TransactionTimedOutException ex) {
            Throwable root = ex;
            SQLException sql = null;
            var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());

            for (Throwable cause = ex;
                 cause != null && visited.add(cause);
                 cause = cause.getCause()) {
                root = cause;
                if (sql == null && cause instanceof SQLException sqlException) {
                    sql = sqlException;
                }
            }

            String sqlState = sql == null ? null : sql.getSQLState();
            if (sqlState != null && !sqlState.matches("[0-9A-Z]{5}")) {
                sqlState = null;
            }
            Integer errorCode = sql == null ? null : sql.getErrorCode();

            log.warn(
                    "Quiz review query unavailable: operation={}, exceptionClass={}, "
                            + "rootCauseClass={}, sqlState={}, dbErrorCode={}",
                    operation, ex.getClass().getName(), root.getClass().getName(),
                    sqlState, errorCode);

            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}