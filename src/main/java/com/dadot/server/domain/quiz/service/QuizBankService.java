package com.dadot.server.domain.quiz.service;

import com.dadot.server.domain.quiz.model.QuizBank;
import com.dadot.server.domain.quiz.model.QuizBank.*;
import com.dadot.server.domain.quiz.repository.QuizBankRepository;
import com.dadot.server.domain.quiz.repository.QuizRequestStore;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.AdminAccess;
import com.dadot.server.global.security.SessionAuthenticationToken;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class QuizBankService {

    private final QuizBankRepository repository;
    private final QuizRequestStore requests;
    private final AdminAccess access;
    private final Clock clock;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public QuizBankService(
            QuizBankRepository repository,
            QuizRequestStore requests,
            AdminAccess access,
            Clock clock,
            PlatformTransactionManager manager
    ) {
        this.repository = repository;
        this.requests = requests;
        this.access = access;
        this.clock = clock;

        write = new TransactionTemplate(manager);
        write.setTimeout(10);
        write.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED
        );

        read = new TransactionTemplate(manager);
        read.setTimeout(10);
        read.setReadOnly(true);
        read.setIsolationLevel(
                TransactionDefinition.ISOLATION_REPEATABLE_READ
        );
    }

    public View create(
            SessionAuthenticationToken actor,
            String key,
            Content content
    ) {
        if (content == null) {
            throw QuizBank.invalid();
        }

        return storage(() ->
                Objects.requireNonNull(write.execute(tx -> {
                    access.require(actor, true);

                    long userId = actor.getPrincipal().userId();

                    var request = requests.key(
                            userId,
                            key,
                            content
                    );

                    var replay = requests.replay(
                            request,
                            userId
                    );

                    if (replay.isPresent()) {
                        return replay.get();
                    }

                    repository.lockCompany(content.companyId());
                    repository.validateReferences(content);

                    var now = clock.instant();

                    long questionId = repository.createQuestion(
                            content.companyId(),
                            now
                    );

                    long versionId = repository.createVersion(
                            questionId,
                            content,
                            null,
                            now
                    );

                    repository.audit(
                            userId,
                            "QUIZ_DRAFT_CREATED",
                            versionId,
                            null,
                            now
                    );

                    var result = repository.view(versionId);

                    requests.complete(
                            request,
                            userId,
                            result,
                            now
                    );

                    access.require(actor, true);

                    return result;
                }))
        );
    }

    public Page search(
            SessionAuthenticationToken actor,
            Search search
    ) {
        if (search == null) {
            throw QuizBank.invalid();
        }

        return storage(() ->
                Objects.requireNonNull(read.execute(tx -> {
                    access.require(actor, false);
                    return repository.search(search);
                }))
        );
    }

    public View update(
            SessionAuthenticationToken actor,
            long questionId,
            Patch request
    ) {
        QuizBank.id(questionId);

        if (request == null) {
            throw QuizBank.invalid();
        }

        return storage(() ->
                Objects.requireNonNull(write.execute(tx -> {
                    access.require(actor, true);

                    repository.lockCompany(
                            repository.companyId(questionId)
                    );

                    var question = repository.lockQuestion(questionId);

                    var target = repository.lockVersion(
                            questionId,
                            request.questionVersionId()
                    );

                    if (question.revision() != request.expectedVersion()) {
                        throw new BusinessException(
                                ErrorCode.VERSION_CONFLICT
                        );
                    }

                    long actorId = actor.getPrincipal().userId();
                    var now = clock.instant();
                    long resultId = target.id();

                    if (request.content() != null) {
                        if (request.content().companyId() != question.companyId()) {
                            throw QuizBank.invalid();
                        }

                        repository.validateReferences(request.content());

                        resultId = repository.createVersion(
                                questionId,
                                request.content(),
                                request.reviewNote(),
                                now
                        );

                        repository.audit(
                                actorId,
                                "QUIZ_DRAFT_CREATED",
                                resultId,
                                request.reviewNote(),
                                now
                        );

                    } else {
                        Status next = request.status();

                        if (!target.status().canChangeTo(next)) {
                            throw QuizBank.transition();
                        }

                        if (next == Status.IN_REVIEW
                                || next == Status.APPROVED
                                || next == Status.PUBLISHED) {
                            repository.requireReady(target.id());
                        }

                        if (next == Status.PUBLISHED) {
                            if (repository.hasNewerPublication(target)) {
                                throw QuizBank.transition();
                            }

                            repository.supersedeOlder(
                                    target,
                                    actorId,
                                    request.reviewNote(),
                                    now
                            );

                            repository.publication(
                                    questionId,
                                    target.number()
                            );

                        } else if (next == Status.SUPERSEDED) {
                            if (question.publishedNo() == null
                                    || question.publishedNo() == target.number()) {
                                throw QuizBank.transition();
                            }

                        } else if (next == Status.SUSPENDED
                                && question.publishedNo() != null
                                && question.publishedNo() == target.number()) {
                            repository.publication(
                                    questionId,
                                    null
                            );
                        }

                        repository.changeStatus(
                                target,
                                next,
                                actorId,
                                request.reviewNote(),
                                now
                        );
                    }

                    repository.touch(
                            questionId,
                            now
                    );

                    var result = repository.view(resultId);

                    access.require(actor, true);

                    return result;
                }))
        );
    }

    private <T> T storage(Supplier<T> operation) {
        try {
            return operation.get();

        } catch (
                TransientDataAccessException
                | DataAccessResourceFailureException
                | CannotCreateTransactionException
                | TransactionTimedOutException ex
        ) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }
    }
}