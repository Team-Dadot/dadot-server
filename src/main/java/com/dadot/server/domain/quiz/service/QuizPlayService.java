package com.dadot.server.domain.quiz.service;

import com.dadot.server.domain.company.support.CompanyLogoUrlResolver;
import com.dadot.server.domain.quiz.model.QuizBank;
import com.dadot.server.domain.quiz.model.QuizPlay;
import com.dadot.server.domain.quiz.model.QuizPlay.*;
import com.dadot.server.domain.quiz.repository.QuizPlayRepository;
import com.dadot.server.domain.quiz.repository.QuizPlayRepository.*;
import com.dadot.server.domain.quiz.repository.QuizPlayRequestStore;
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

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static com.dadot.server.domain.quiz.model.QuizPlay.fail;

@Service
public class QuizPlayService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Logger log = LoggerFactory.getLogger(QuizPlayService.class);
    private final QuizPlayRepository repository;
    private final QuizPlayRequestStore requests;
    private final UserAccess access;
    private final CompanyLogoUrlResolver logos;
    private final Clock clock;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public QuizPlayService(QuizPlayRepository repository, QuizPlayRequestStore requests,
                           UserAccess access, CompanyLogoUrlResolver logos, Clock clock,
                           PlatformTransactionManager manager) {
        this.repository = repository;
        this.requests = requests;
        this.access = access;
        this.logos = logos;
        this.clock = clock;
        write = new TransactionTemplate(manager);
        write.setTimeout(10);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read = new TransactionTemplate(manager);
        read.setTimeout(10);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Prepared prepare(SessionAuthenticationToken actor, long company, String key) {
        QuizBank.id(company);
        return storage(() -> Objects.requireNonNull(write.execute(tx -> {
            long user = access.require(actor, true);
            var interview = repository.requirePreparation(user, company, true);
            var request = requests.key(user, "POST /v1/companies/" + company + "/quiz-sets/today", key, null);
            var replay = requests.replay(user, request, Summary.class);
            if (replay.isPresent()) {
                return new Prepared(replay.get().status(), replay.get().body());
            }
            repository.lockCompany(company);
            var now = clock.instant();
            LocalDate date = now.atZone(KST).toLocalDate();
            var existing = repository.today(user, company, date);
            SetRow set;
            int status;
            if (existing.isPresent()) {
                set = existing.get();
                status = 200;
            } else {
                boolean focus = interview != null && !date.isBefore(interview.minusDays(3))
                        && !date.isAfter(interview);
                var candidates = repository.select(user, company, focus);
                if (candidates.size() != QuizPlay.SIZE) {
                    throw fail(ErrorCode.QUIZ_BANK_INSUFFICIENT);
                }
                set = repository.create(user, company, date, focus ? "FOCUS" : "NORMAL",
                        repository.accuracy(user, company), candidates, now);
                status = 201;
            }
            var items = checkedItems(set);
            if (!"COMPLETED".equals(set.status())) {
                requireAvailable(items);
                repository.choices(items);
            }
            var body = summary(set, items, date);
            requests.complete(user, request, status, body, now);
            access.require(actor, true);
            return new Prepared(status, body);
        })));
    }

    public Detail detail(SessionAuthenticationToken actor, long setId) {
        QuizBank.id(setId);
        return storage(() -> Objects.requireNonNull(read.execute(tx -> {
            long user = access.require(actor, false);
            var set = repository.owned(user, setId);
            var items = checkedItems(set);
            var summary = summary(set, items, today());
            Question current = null;
            if ("IN_PROGRESS".equals(summary.status())) {
                repository.requirePreparation(user, set.companyId(), false);
                requireAvailable(items);
                var item = items.get(summary.submittedCount());
                current = question(item, repository.choices(List.of(item)).get(item.versionId()));
            }
            var saved = items.stream().filter(i -> i.answerId() != null)
                    .map(i -> new SavedAnswer(i.id(), i.selected(), i.submittedAt())).toList();
            return new Detail(set.id(), set.companyId(), set.date(), summary.status(),
                    summary.submittedCount(), QuizPlay.SIZE, summary.nextItemPosition(),
                    set.mode(), current, saved);
        })));
    }

    public Submitted answer(SessionAuthenticationToken actor, long setId, String key, Answer answer) {
        QuizBank.id(setId);
        if (answer == null) {
            throw QuizBank.invalid();
        }
        return storage(() -> Objects.requireNonNull(write.execute(tx -> {
            long user = access.require(actor, true);
            var set = repository.owned(user, setId);
            repository.requirePreparation(user, set.companyId(), true);
            var request = requests.key(user, "POST /v1/quiz-sets/" + setId + "/answers", key, answer);
            var replay = requests.replay(user, request, Submitted.class);
            if (replay.isPresent()) {
                return replay.get().body();
            }
            repository.lockCompany(set.companyId());
            var now = clock.instant();
            if (!set.date().equals(now.atZone(KST).toLocalDate()) || "EXPIRED".equals(set.status())) {
                throw fail(ErrorCode.QUIZ_SET_EXPIRED);
            }
            var items = checkedItems(set);
            var target = items.stream().filter(i -> i.id() == answer.itemId())
                    .findFirst().orElseThrow(QuizBank::missing);
            if (target.answerId() != null) {
                throw fail(ErrorCode.ANSWER_ALREADY_SUBMITTED);
            }
            int count = (int) items.stream().filter(i -> i.answerId() != null).count();
            if (target.position() != count + 1) {
                throw fail(ErrorCode.INVALID_ITEM_ORDER);
            }
            requireAvailable(items);
            var choices = repository.choices(List.of(target)).get(target.versionId());
            if (choices.options().stream().noneMatch(o -> o.optionId() == answer.selectedOptionId())) {
                throw QuizBank.invalid();
            }
            long answerId = repository.save(user, set, target, answer.selectedOptionId(),
                    choices.correctId() == answer.selectedOptionId(), request.hash(), request.body(), now);
            int submitted = count + 1;
            if (submitted == QuizPlay.SIZE) {
                repository.complete(set.id(), repository.accuracy(user, set.companyId()), now);
            }
            var result = new Submitted(answerId, submitted,
                    submitted == QuizPlay.SIZE ? null : submitted + 1,
                    submitted == QuizPlay.SIZE ? "COMPLETED" : "IN_PROGRESS");
            requests.complete(user, request, 200, result, now);
            access.require(actor, true);
            return result;
        })));
    }

    public Result result(SessionAuthenticationToken actor, long setId) {
        QuizBank.id(setId);
        return storage(() -> Objects.requireNonNull(read.execute(tx -> {
            long user = access.require(actor, false);
            var set = repository.owned(user, setId);
            if (!"COMPLETED".equals(set.status())) {
                throw fail(ErrorCode.QUIZ_SET_NOT_COMPLETED);
            }
            var items = checkedItems(set);
            var choices = repository.choices(items);
            var evidence = repository.evidence(items);
            var results = items.stream().map(i -> new ResultItem(
                    question(i, choices.get(i.versionId())), Objects.requireNonNull(i.answerId()),
                    i.selected(), choices.get(i.versionId()).correctId(), i.correct(), i.submittedAt(),
                    i.explanation(), evidence.getOrDefault(i.versionId(), List.of()),
                    i.available() ? null : "근거 자료가 변경되었거나 현재 제공이 중단된 문제입니다."
            )).toList();
            var company = repository.company(set.companyId());
            return new Result(set.id(), new Company(company.id(), company.name(), company.industry(), logo(company)),
                    set.date(), (int) items.stream().filter(i -> Boolean.TRUE.equals(i.correct())).count(),
                    QuizPlay.SIZE, set.before(), set.after(), results);
        })));
    }

    private List<Item> checkedItems(SetRow set) {
        var items = repository.items(set.id());
        if (items.size() != QuizPlay.SIZE) {
            throw new IllegalStateException("Incomplete quiz set storage");
        }
        boolean pending = false;
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            if (item.position() != i + 1 || (pending && item.answerId() != null)) {
                throw new IllegalStateException("Invalid quiz answer order");
            }
            pending |= item.answerId() == null;
        }
        if ("COMPLETED".equals(set.status()) == pending) {
            throw new IllegalStateException("Inconsistent quiz completion");
        }
        return items;
    }

    private static void requireAvailable(List<Item> items) {
        if (items.stream().anyMatch(i -> i.answerId() == null && !i.available())) {
            throw QuizBank.unavailable();
        }
    }

    private static Summary summary(SetRow set, List<Item> items, LocalDate today) {
        int count = (int) items.stream().filter(i -> i.answerId() != null).count();
        String status = "IN_PROGRESS".equals(set.status()) && set.date().isBefore(today)
                ? "EXPIRED" : set.status();
        return new Summary(set.id(), set.companyId(), set.date(), status, count, QuizPlay.SIZE,
                "IN_PROGRESS".equals(status) ? count + 1 : null, set.mode());
    }

    private static Question question(Item item, Choices choices) {
        return new Question(item.id(), item.questionId(), item.versionId(), item.position(),
                item.text(), choices.options(), item.review());
    }

    private String logo(CompanyRow company) {
        try {
            return logos.resolve(company.logoKey());
        } catch (IllegalStateException ex) {
            log.warn("Invalid quiz result company logo key: companyId={}", company.id());
            return null;
        }
    }

    private LocalDate today() {
        return clock.instant().atZone(KST).toLocalDate();
    }

    private static <T> T storage(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (TransientDataAccessException | DataAccessResourceFailureException
                 | CannotCreateTransactionException | TransactionTimedOutException ex) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}