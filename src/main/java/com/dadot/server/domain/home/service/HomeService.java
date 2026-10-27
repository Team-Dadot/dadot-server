package com.dadot.server.domain.home.service;

import com.dadot.server.domain.company.model.PreparationCompany;
import com.dadot.server.domain.company.model.PreparationCompany.*;
import com.dadot.server.domain.company.repository.PreparationCompanyRepository;
import com.dadot.server.domain.company.repository.PreparationCompanyRepository.Row;
import com.dadot.server.domain.company.support.CompanyLogoUrlResolver;
import com.dadot.server.domain.home.model.Home.*;
import com.dadot.server.domain.home.repository.HomeRepository;
import com.dadot.server.domain.home.repository.HomeRepository.ProfileRow;
import com.dadot.server.domain.home.support.ProfileImageUrlResolver;
import com.dadot.server.domain.quiz.repository.QuizPlayRepository;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.SessionAuthenticationToken;
import com.dadot.server.global.security.UserAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class HomeService {

    private static final Logger log = LoggerFactory.getLogger(HomeService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final HomeRepository repository;
    private final PreparationCompanyRepository companies;
    private final QuizPlayRepository quizzes;
    private final UserAccess access;
    private final CompanyLogoUrlResolver logos;
    private final ProfileImageUrlResolver images;
    private final Clock clock;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public HomeService(HomeRepository repository, PreparationCompanyRepository companies,
                       QuizPlayRepository quizzes, UserAccess access,
                       CompanyLogoUrlResolver logos, ProfileImageUrlResolver images,
                       Clock clock, PlatformTransactionManager manager) {
        this.repository = repository;
        this.companies = companies;
        this.quizzes = quizzes;
        this.access = access;
        this.logos = logos;
        this.images = images;
        this.clock = clock;
        read = transaction(manager, true);
        write = transaction(manager, false);
    }

    public Dashboard dashboard(SessionAuthenticationToken actor, Long companyId) {
        if (companyId != null) PreparationCompany.id(companyId);
        return storage("dashboard", () -> Objects.requireNonNull(read.execute(tx -> {
            long user = access.require(actor, false);
            ProfileRow profile = repository.profile(user);
            completed(profile);
            var active = companies.rows(user); // 스키마상 최대 5행.
            requireSelected(profile, active);
            long requested = companyId == null ? profile.selectedCompanyId() : companyId;
            Row target = active.stream().filter(row -> row.companyId() == requested)
                    .findFirst().orElseThrow(PreparationCompany::missing);

            // KST 날짜·D-day·리셋 시각은 하나의 시각에서 계산한다.
            Instant now = clock.instant();
            LocalDate today = now.atZone(KST).toLocalDate();
            Instant reset = today.plusDays(1).atStartOfDay(KST).toInstant();
            Item item = item(user, target, today, reset);
            String action = item.quizToday().completed() ? "START_INTERVIEW"
                    : item.quizToday().quizSetId() == null ? "START_QUIZ" : "CONTINUE_QUIZ";

            return new Dashboard(
                    new Profile(user, profile.nickname(), profileImage(user, profile.imageKey())),
                    item, quizzes.accuracy(user, requested), EvaluationSummary.unavailable(),
                    today, reset, action);
        })));
    }

    public Preferences select(SessionAuthenticationToken actor, Preferences request) {
        if (request == null) throw PreparationCompany.invalid();
        return storage("select", () -> Objects.requireNonNull(write.execute(tx -> {
            // #38의 추가·수정·제외와 같은 순서로 잠가 기업 제외와 선택을 직렬화한다.
            long user = access.require(actor, true);
            ProfileRow profile = repository.profile(user);
            completed(profile);
            var active = companies.lockActive(user);
            if (active.stream().noneMatch(row ->
                    row.companyId() == request.selectedCompanyId())) {
                throw PreparationCompany.missing();
            }
            if (!Objects.equals(profile.selectedCompanyId(), request.selectedCompanyId())) {
                repository.select(user, request.selectedCompanyId(), clock.instant());
            }
            // 잠금 대기·처리 중 세션이 만료됐다면 변경까지 롤백한다.
            access.require(actor, true);
            return request;
        })));
    }

    private Item item(long user, Row row, LocalDate today, Instant reset) {
        QuizToday quiz = companies.quizzes(user, today, List.of(row.companyId()))
                .getOrDefault(row.companyId(), new QuizToday(null, 0, 5, false));
        Long days = row.interviewDate() == null ? null
                : ChronoUnit.DAYS.between(today, row.interviewDate());
        String schedule = days == null ? "UNSET" : days < 0 ? "PAST"
                : days == 0 ? "TODAY" : "UPCOMING";
        return new Item(
                new Company(row.companyId(), row.name(), row.industry(), logo(row)),
                new Job(row.jobId(), row.jobName(), row.jobId() == null),
                row.interviewDate(), days, schedule,
                days != null && days >= 0 && days <= 3 ? "FOCUS" : "NORMAL",
                row.selected(), row.version(), quiz,
                new InterviewToday(today, 9, 0, 9, reset, 3, 0, 3));
    }

    private static void completed(ProfileRow profile) {
        if (!profile.completed()) throw new BusinessException(ErrorCode.ONBOARDING_REQUIRED);
    }

    private static void requireSelected(ProfileRow profile, List<Row> active) {
        if (profile.selectedCompanyId() == null || active.stream().noneMatch(row ->
                row.companyId() == profile.selectedCompanyId())) {
            throw new IllegalStateException("Missing active home selection");
        }
    }

    private String logo(Row row) {
        try {
            return logos.resolve(row.logoKey());
        } catch (IllegalStateException ex) {
            log.warn("Invalid home company logo key: companyId={}", row.companyId());
            return null;
        }
    }

    private String profileImage(long user, String key) {
        try {
            return images.resolve(key);
        } catch (IllegalStateException ex) {
            log.warn("Invalid home profile image key: userId={}", user);
            return null;
        }
    }

    private static TransactionTemplate transaction(PlatformTransactionManager manager,
                                                   boolean readOnly) {
        var template = new TransactionTemplate(manager);
        template.setReadOnly(readOnly);
        template.setTimeout(10);
        template.setIsolationLevel(readOnly ? TransactionDefinition.ISOLATION_REPEATABLE_READ
                : TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    private <T> T storage(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (TransientDataAccessException | DataAccessResourceFailureException
                 | TransactionException ex) {
            Throwable root = ex;
            SQLException sql = null;
            var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
            for (Throwable cause = ex; cause != null && visited.add(cause); cause = cause.getCause()) {
                root = cause;
                if (sql == null && cause instanceof SQLException found) sql = found;
            }
            String state = sql == null ? null : sql.getSQLState();
            if (state != null && !state.matches("[0-9A-Z]{5}")) state = null;
            log.warn("Home unavailable: operation={}, exceptionClass={}, rootCauseClass={}, "
                            + "sqlState={}, dbErrorCode={}", operation, ex.getClass().getName(),
                    root.getClass().getName(), state, sql == null ? null : sql.getErrorCode());
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}