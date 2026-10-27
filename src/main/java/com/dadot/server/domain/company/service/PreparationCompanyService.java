package com.dadot.server.domain.company.service;

import com.dadot.server.domain.company.model.PreparationCompany;
import com.dadot.server.domain.company.model.PreparationCompany.*;
import com.dadot.server.domain.company.repository.PreparationCompanyRepository;
import com.dadot.server.domain.company.repository.PreparationCompanyRepository.Row;
import com.dadot.server.domain.company.repository.PreparationCompanyRepository.State;
import com.dadot.server.domain.company.repository.PreparationCompanyRequestStore;
import com.dadot.server.domain.company.support.CompanyLogoUrlResolver;
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
import java.util.stream.Collectors;

@Service
public class PreparationCompanyService {

    private static final Logger log =
            LoggerFactory.getLogger(PreparationCompanyService.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final PreparationCompanyRepository repository;
    private final PreparationCompanyRequestStore requests;
    private final UserAccess access;
    private final CompanyLogoUrlResolver logos;
    private final Clock clock;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public PreparationCompanyService(
            PreparationCompanyRepository repository,
            PreparationCompanyRequestStore requests,
            UserAccess access,
            CompanyLogoUrlResolver logos,
            Clock clock,
            PlatformTransactionManager manager
    ) {
        this.repository = repository;
        this.requests = requests;
        this.access = access;
        this.logos = logos;
        this.clock = clock;
        read = transaction(manager, true);
        write = transaction(manager, false);
    }

    public Page list(SessionAuthenticationToken actor, Paging paging) {
        if (paging == null) throw PreparationCompany.invalid();

        return storage("list", () ->
                Objects.requireNonNull(read.execute(tx -> {
                    long user = access.require(actor, false);
                    var rows = repository.rows(user);
                    long total = rows.size();

                    List<Row> page = paging.offset() >= total
                            ? List.of()
                            : rows.subList(
                            (int) paging.offset(),
                            (int) Math.min(
                                    total, paging.offset() + paging.size()));

                    return new Page(
                            items(user, page, clock.instant()),
                            paging.page(),
                            paging.size(),
                            total,
                            paging.totalPages(total),
                            paging.offset() + paging.size() < total);
                })));
    }

    public Item add(
            SessionAuthenticationToken actor,
            String key,
            Add request
    ) {
        if (request == null) throw PreparationCompany.invalid();

        return storage("add", () ->
                Objects.requireNonNull(write.execute(tx -> {
                    long user = access.require(actor, true);

                    var requestKey = requests.key(user, key, request);
                    var replay = requests.replay(user, requestKey);

                    if (replay.isPresent()) {
                        access.require(actor, true);
                        return replay.get();
                    }

                    var active = active(user);

                    if (active.stream().anyMatch(
                            s -> s.companyId() == request.companyId())) {
                        throw failure(ErrorCode.COMPANY_ALREADY_ACTIVE);
                    }

                    if (active.size() == PreparationCompany.MAX_ACTIVE) {
                        throw failure(ErrorCode.COMPANY_LIMIT_EXCEEDED);
                    }

                    var previous = repository.lockExisting(
                            user, request.companyId()).orElse(null);

                    repository.lockPublicCompany(request.companyId());
                    repository.validateJob(request.job());

                    Instant now = clock.instant();
                    requireDate(request.interviewDate(), null, now);

                    repository.add(
                            user,
                            request.companyId(),
                            active.size() + 1,
                            request.job(),
                            request.interviewDate(),
                            previous,
                            now);

                    var result = items(
                            user,
                            repository.rows(user).stream()
                                    .filter(row ->
                                            row.companyId()
                                                    == request.companyId())
                                    .toList(),
                            now).getFirst();

                    requests.complete(user, requestKey, result, now);
                    access.require(actor, true);
                    return result;
                })));
    }

    public Changed update(
            SessionAuthenticationToken actor,
            long company,
            Patch request
    ) {
        PreparationCompany.id(company);
        if (request == null) throw PreparationCompany.invalid();

        return storage("update", () ->
                Objects.requireNonNull(write.execute(tx -> {
                    long user = access.require(actor, true);
                    var active = active(user);
                    var target = target(active, company);

                    if (target.version() != request.version()) {
                        throw failure(ErrorCode.VERSION_CONFLICT);
                    }

                    boolean all =
                            request.jobScope() == JobScope.ALL_ACTIVE;

                    if (all) {
                        var expected = request.expectedCompanyVersions()
                                .stream()
                                .collect(Collectors.toMap(
                                        Expected::companyId,
                                        Expected::version));

                        if (expected.size() != active.size()
                                || active.stream().anyMatch(s ->
                                !Objects.equals(
                                        expected.get(s.companyId()),
                                        s.version()))) {
                            throw failure(ErrorCode.VERSION_CONFLICT);
                        }
                    }

                    if (request.job() != null) {
                        repository.validateJob(request.job());
                    }

                    Instant now = clock.instant();

                    if (request.dateSpecified()) {
                        requireDate(
                                request.interviewDate(),
                                target.interviewDate(),
                                now);
                    }

                    var affected = all ? active : List.of(target);

                    for (var state : affected) {
                        JobInput job = request.job() == null
                                ? new JobInput(
                                state.jobId(), state.customName())
                                : request.job();

                        LocalDate date =
                                state.companyId() == company
                                        && request.dateSpecified()
                                        ? request.interviewDate()
                                        : state.interviewDate();

                        repository.update(user, state, job, date);
                    }

                    var changedIds = affected.stream()
                            .map(State::companyId)
                            .collect(Collectors.toSet());

                    var result = new Changed(items(
                            user,
                            repository.rows(user).stream()
                                    .filter(row ->
                                            changedIds.contains(
                                                    row.companyId()))
                                    .toList(),
                            now));

                    access.require(actor, true);
                    return result;
                })));
    }

    public void exclude(
            SessionAuthenticationToken actor,
            long company
    ) {
        PreparationCompany.id(company);

        storage("exclude", () -> write.execute(tx -> {
            long user = access.require(actor, true);
            var active = active(user);
            var target = target(active, company);

            if (active.size() == 1) {
                throw failure(ErrorCode.MIN_COMPANY_REQUIRED);
            }

            long selected = repository.lockSelected(user);
            Instant now = clock.instant();

            repository.exclude(user, target, now);

            for (var state : active) {
                if (state.slot() > target.slot()) {
                    repository.moveForward(user, state);
                }
            }

            if (selected == company) {
                long next = active.stream()
                        .filter(s -> s.companyId() != company)
                        .findFirst()
                        .orElseThrow()
                        .companyId();

                repository.select(user, next, now);
            }

            access.require(actor, true);
            return null;
        }));
    }

    private List<State> active(long user) {
        repository.requireCompleted(user);

        var active = repository.lockActive(user);
        long selected = repository.lockSelected(user);

        if (active.isEmpty()
                || active.size() > PreparationCompany.MAX_ACTIVE
                || active.stream().noneMatch(
                s -> s.companyId() == selected)) {
            throw new IllegalStateException(
                    "Inconsistent active preparation companies");
        }

        for (int i = 0; i < active.size(); i++) {
            if (active.get(i).slot() != i + 1) {
                throw new IllegalStateException(
                        "Invalid preparation company order");
            }
        }

        return active;
    }

    private static State target(List<State> active, long company) {
        return active.stream()
                .filter(s -> s.companyId() == company)
                .findFirst()
                .orElseThrow(PreparationCompany::missing);
    }

    private List<Item> items(
            long user,
            List<Row> rows,
            Instant now
    ) {
        LocalDate today = now.atZone(KST).toLocalDate();

        var quizzes = repository.quizzes(
                user, today,
                rows.stream().map(Row::companyId).toList());

        var interview = new InterviewToday(
                today,
                9,
                0,
                9,
                today.plusDays(1).atStartOfDay(KST).toInstant(),
                3,
                0,
                3);

        return rows.stream().map(row -> {
            Long days = row.interviewDate() == null
                    ? null
                    : ChronoUnit.DAYS.between(
                    today, row.interviewDate());

            String schedule = days == null
                    ? "UNSET"
                    : days < 0
                    ? "PAST"
                    : days == 0
                    ? "TODAY"
                    : "UPCOMING";

            return new Item(
                    new Company(
                            row.companyId(),
                            row.name(),
                            row.industry(),
                            logo(row)),
                    new Job(
                            row.jobId(),
                            row.jobName(),
                            row.jobId() == null),
                    row.interviewDate(),
                    days,
                    schedule,
                    days != null && days >= 0 && days <= 3
                            ? "FOCUS" : "NORMAL",
                    row.selected(),
                    row.version(),
                    quizzes.getOrDefault(
                            row.companyId(),
                            new QuizToday(null, 0, 5, false)),
                    interview);
        }).toList();
    }

    private String logo(Row company) {
        try {
            return logos.resolve(company.logoKey());
        } catch (IllegalStateException ex) {
            log.warn(
                    "Invalid preparation company logo key: companyId={}",
                    company.companyId());
            return null;
        }
    }

    private static void requireDate(
            LocalDate date,
            LocalDate previous,
            Instant now
    ) {
        if (date != null
                && date.isBefore(now.atZone(KST).toLocalDate())
                && !date.equals(previous)) {
            throw PreparationCompany.invalid();
        }
    }

    private static TransactionTemplate transaction(
            PlatformTransactionManager manager,
            boolean readOnly
    ) {
        var template = new TransactionTemplate(manager);
        template.setReadOnly(readOnly);
        template.setTimeout(10);
        template.setIsolationLevel(
                readOnly
                        ? TransactionDefinition.ISOLATION_REPEATABLE_READ
                        : TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }

    private <T> T storage(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (TransientDataAccessException
                 | DataAccessResourceFailureException
                 | TransactionException ex) {
            Throwable root = ex;
            SQLException sql = null;

            var visited = Collections.newSetFromMap(
                    new IdentityHashMap<Throwable, Boolean>());

            for (Throwable cause = ex;
                 cause != null && visited.add(cause);
                 cause = cause.getCause()) {
                root = cause;
                if (sql == null && cause instanceof SQLException found) {
                    sql = found;
                }
            }

            String state = sql == null ? null : sql.getSQLState();
            if (state != null && !state.matches("[0-9A-Z]{5}")) {
                state = null;
            }

            log.warn(
                    "Preparation company unavailable: operation={}, exceptionClass={}, "
                            + "rootCauseClass={}, sqlState={}, dbErrorCode={}",
                    operation,
                    ex.getClass().getName(),
                    root.getClass().getName(),
                    state,
                    sql == null ? null : sql.getErrorCode());

            throw failure(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static BusinessException failure(ErrorCode code) {
        return new BusinessException(code);
    }
}