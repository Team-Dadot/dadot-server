package com.dadot.server.domain.quiz.service;

import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.quiz.model.QuizBank;
import com.dadot.server.domain.quiz.model.QuizGeneration;
import com.dadot.server.domain.quiz.model.QuizGeneration.Accepted;
import com.dadot.server.domain.quiz.model.QuizGeneration.Request;
import com.dadot.server.domain.quiz.model.QuizGeneration.Result;
import com.dadot.server.domain.quiz.repository.QuizBankRepository;
import com.dadot.server.domain.quiz.repository.QuizGenerationRepository;
import com.dadot.server.domain.quiz.repository.QuizGenerationSourceRepository;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.AdminAccess;
import com.dadot.server.global.security.SessionAuthenticationToken;
import com.dadot.server.infrastructure.openai.OpenAiQuizClient;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class QuizGenerationService {
    private final QuizGenerationRepository repository;
    private final QuizGenerationSourceRepository sources;
    private final QuizBankRepository bank;
    private final AdminAccess access;
    private final VerificationCrypto crypto;
    private final JsonMapper mapper;
    private final OpenAiQuizClient client;
    private final Clock clock;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public QuizGenerationService(QuizGenerationRepository repository, QuizGenerationSourceRepository sources,
                                 QuizBankRepository bank, AdminAccess access, VerificationCrypto crypto,
                                 JsonMapper mapper, OpenAiQuizClient client, Clock clock,
                                 PlatformTransactionManager manager) {
        this.repository = repository;
        this.sources = sources;
        this.bank = bank;
        this.access = access;
        this.crypto = crypto;
        this.mapper = mapper;
        this.client = client;
        this.clock = clock;
        write = new TransactionTemplate(manager);
        write.setTimeout(20);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read = new TransactionTemplate(manager);
        read.setTimeout(10);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Accepted start(SessionAuthenticationToken actor, long companyId, String key, Request request) {
        QuizBank.id(companyId);
        String canonical = QuizGeneration.uuid(key);
        if (request == null) throw QuizBank.invalid();
        String operation = "POST /v1/admin/companies/" + companyId + "/quiz-generation-tasks";
        String scope = crypto.hmac("quiz-generation-scope-v1", actor.getName());
        String hash = crypto.hmac("quiz-generation-key-v1", operation, canonical);
        String body = crypto.hmac("quiz-generation-body-v1", operation, mapper.writeValueAsString(request));
        return storage(() -> Objects.requireNonNull(write.execute(tx -> {
            access.require(actor, true);
            var replay = repository.replay(scope, operation, hash, body, clock.instant());
            if (replay != null) return replay;
            if (!client.ready()) throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            bank.lockCompany(companyId);
            var plan = sources.plan(companyId, request);
            var result = repository.create(actor.getPrincipal().userId(), companyId, request, plan,
                    scope, operation, hash, body, client.policyHash(), clock.instant());
            access.require(actor, true);
            return result;
        })));
    }

    public Result result(SessionAuthenticationToken actor, String taskId) {
        String canonical = QuizGeneration.uuid(taskId);
        return storage(() -> Objects.requireNonNull(read.execute(tx -> {
            access.require(actor, false);
            var result = repository.result(canonical);
            access.require(actor, false);
            return result;
        })));
    }

    private static <T> T storage(Supplier<T> work) {
        try { return work.get(); }
        catch (TransientDataAccessException | DataAccessResourceFailureException
               | CannotCreateTransactionException | TransactionTimedOutException ex) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}