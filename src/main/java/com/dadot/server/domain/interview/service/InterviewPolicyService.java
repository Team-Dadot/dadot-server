package com.dadot.server.domain.interview.service;

import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.interview.model.InterviewPolicy.*;
import com.dadot.server.domain.interview.repository.InterviewPolicyRepository;
import com.dadot.server.domain.interview.repository.InterviewPolicyRequestStore;
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
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class InterviewPolicyService {

    private final InterviewPolicyRepository repository;
    private final InterviewPolicyRequestStore requests;
    private final AdminAccess access;
    private final Clock clock;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public InterviewPolicyService(InterviewPolicyRepository repository, InterviewPolicyRequestStore requests,
                                  AdminAccess access, Clock clock, PlatformTransactionManager manager) {
        this.repository = repository;
        this.requests = requests;
        this.access = access;
        this.clock = clock;
        write = new TransactionTemplate(manager);
        write.setTimeout(10);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read = new TransactionTemplate(manager);
        read.setTimeout(10);
        read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Page search(SessionAuthenticationToken actor, Search search) {
        Objects.requireNonNull(search);
        return storage(() -> Objects.requireNonNull(read.execute(tx -> {
            access.require(actor, false);
            return repository.search(search);
        })));
    }

    public View create(SessionAuthenticationToken actor, String key, JsonNode body) {
        Create content = Create.parse(body);
        return storage(() -> Objects.requireNonNull(write.execute(tx -> {
            access.require(actor, true);
            long userId = actor.getPrincipal().userId();
            var request = requests.key(userId, key, body);
            var replay = requests.replay(userId, request);
            if (replay.isPresent()) return replay.get();

            var now = clock.instant();
            repository.validateScope(content.scope());
            var policy = content.sourcePolicyId() == null
                    ? repository.createPolicy(content, now)
                    : repository.lockPolicy(content.sourcePolicyId());
            if (policy.type() != content.type() || !policy.scope().equals(content.scope())) {
                throw InterviewPolicy.invalid();
            }
            long versionId = repository.createVersion(policy, content, now);
            if (content.sourcePolicyId() != null) repository.touch(policy.id(), now);
            repository.audit(userId, "POLICY_DRAFT_CREATED", versionId, null, now);
            View result = repository.view(versionId);
            requests.complete(userId, request, result, now);
            access.require(actor, true);
            return result;
        })));
    }

    public View update(SessionAuthenticationToken actor, long policyId, Patch request) {
        InterviewPolicy.id(policyId);
        Objects.requireNonNull(request);
        return storage(() -> Objects.requireNonNull(write.execute(tx -> {
            access.require(actor, true);
            var policy = repository.lockPolicy(policyId);
            var target = repository.lockVersion(policyId, request.policyVersionId());
            if (policy.revision() != request.expectedVersion()) {
                throw new BusinessException(ErrorCode.VERSION_CONFLICT);
            }
            if (!target.status().canChangeTo(request.status())) throw InterviewPolicy.transition();

            Status next = request.status();
            if (next == Status.IN_REVIEW || next == Status.APPROVED || next == Status.PUBLISHED) {
                repository.requireReady(target.id());
            }
            long userId = actor.getPrincipal().userId();
            var now = clock.instant();
            if (next == Status.PUBLISHED) {
                repository.requirePublishable(target);
                repository.supersedePublished(target, userId, request.reviewNote(), now);
                repository.activate(policyId, target.number());
            } else if (next == Status.SUSPENDED) {
                if (!Objects.equals(policy.activeNo(), target.number())) throw InterviewPolicy.notConfigured();
                repository.activate(policyId, null);
            }
            repository.changeStatus(target, next, userId, request.reviewNote(), now);
            repository.touch(policyId, now);
            View result = repository.view(target.id());
            access.require(actor, true);
            return result;
        })));
    }

    private <T> T storage(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (TransientDataAccessException | DataAccessResourceFailureException
                 | CannotCreateTransactionException | TransactionTimedOutException ex) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}