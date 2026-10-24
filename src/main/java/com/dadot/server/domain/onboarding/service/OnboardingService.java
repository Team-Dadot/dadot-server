package com.dadot.server.domain.onboarding.service;

import com.dadot.server.domain.auth.repository.LogoutRepository;
import com.dadot.server.domain.onboarding.dto.request.OnboardingPatchRequest;
import com.dadot.server.domain.onboarding.dto.response.OnboardingCompleteResponse;
import com.dadot.server.domain.onboarding.dto.response.OnboardingResponse;
import com.dadot.server.domain.onboarding.model.OnboardingDraft;
import com.dadot.server.domain.onboarding.model.OnboardingDraft.Assignment;
import com.dadot.server.domain.onboarding.model.OnboardingDraft.Job;
import com.dadot.server.domain.onboarding.model.OnboardingStep;
import com.dadot.server.domain.onboarding.repository.OnboardingRepository;
import com.dadot.server.domain.onboarding.repository.OnboardingRepository.State;
import com.dadot.server.domain.onboarding.repository.OnboardingRequestStore;
import com.dadot.server.domain.onboarding.support.OnboardingDraftCodec;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class OnboardingService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final OnboardingRepository repository;
    private final OnboardingRequestStore requests;
    private final LogoutRepository actors;
    private final OnboardingDraftCodec codec;
    private final Clock clock;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate writeTransaction;

    public OnboardingService(
            OnboardingRepository repository,
            OnboardingRequestStore requests,
            LogoutRepository actors,
            OnboardingDraftCodec codec,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.requests = requests;
        this.actors = actors;
        this.codec = codec;
        this.clock = clock;
        this.readTransaction = transaction(transactionManager, true);
        this.writeTransaction = transaction(transactionManager, false);
    }

    public OnboardingResponse get(
            long userId, String sessionId, int authVersion
    ) {
        return execute(readTransaction, () -> {
            if (!repository.actorExists(
                    userId, sessionId, authVersion, clock.instant()
            )) {
                throw failure(ErrorCode.UNAUTHENTICATED);
            }

            var state = repository.find(userId);

            return response(
                    userId, sessionId, state.version(), draft(state),
                    state.completedAt() != null
            );
        });
    }

    public OnboardingResponse save(
            long userId,
            String sessionId,
            int authVersion,
            OnboardingPatchRequest request
    ) {
        return execute(writeTransaction, () -> {
            var session = lockActor(userId, sessionId, authVersion);
            var state = repository.lock(userId, clock.instant());

            if (state.completedAt() != null
                    || state.version() != request.version()) {
                throw failure(ErrorCode.VERSION_CONFLICT);
            }

            requireMutableVersion(state.version());

            var previous = draft(state);
            requireEarlierSteps(previous, request.step());

            var next = apply(userId, previous, request);
            Instant now = clock.instant();

            Instant guidanceAt =
                    next.completedSteps().contains(OnboardingStep.NOTIFICATION_GUIDANCE)
                            ? (state.guidanceAt() == null
                            ? now
                            : state.guidanceAt().toInstant(ZoneOffset.UTC))
                            : null;

            repository.save(
                    userId, state.version(), next, codec.encode(next),
                    guidanceAt, null, now
            );

            var response = response(
                    userId, sessionId, state.version() + 1, next, false
            );

            requireUsable(session);

            return response;
        });
    }

    public OnboardingCompleteResponse complete(
            long userId,
            String sessionId,
            int authVersion,
            String idempotencyKey
    ) {
        var key = requests.key(userId, idempotencyKey);

        return execute(writeTransaction, () -> {
            var session = lockActor(userId, sessionId, authVersion);

            // 복구는 현재 초안/일정/공개 상태 검증보다 먼저 수행한다.
            var replay = requests.replay(key, userId, clock.instant());

            if (replay.isPresent()) {
                requireUsable(session);
                return replay.get();
            }

            var state = repository.lock(userId, clock.instant());
            var draft = draft(state);

            if (state.completedAt() == null) {
                requireEarlierSteps(draft, OnboardingStep.COMPLETE);
                requireMutableVersion(state.version());
                requirePublicCompanies(draft.companyIds());

                var jobIds = draft.assignments().stream()
                        .map(Assignment::job)
                        .map(Job::jobId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .sorted()
                        .toList();

                if (repository.lockActiveJobs(jobIds).size() != jobIds.size()) {
                    throw failure(ErrorCode.RESOURCE_NOT_FOUND);
                }

                var steps = EnumSet.copyOf(draft.completedSteps());
                steps.add(OnboardingStep.COMPLETE);

                var completed = new OnboardingDraft(
                        draft.companyIds(),
                        draft.assignments(),
                        draft.experienceIds(),
                        List.copyOf(steps)
                );

                Instant now = clock.instant();

                repository.activate(userId, completed, now);

                repository.save(
                        userId,
                        state.version(),
                        completed,
                        codec.encode(completed),
                        state.guidanceAt().toInstant(ZoneOffset.UTC),
                        now,
                        now
                );
            }

            // 새 키로 다시 완료해도 최초 선택 기업을 반환하고 현재 홈 선택을 변경하지 않는다.
            var result = new OnboardingCompleteResponse(
                    true, draft.companyIds().getFirst()
            );

            requests.complete(key, userId, result, clock.instant());
            requireUsable(session);

            return result;
        });
    }

    private OnboardingDraft apply(
            long userId,
            OnboardingDraft draft,
            OnboardingPatchRequest request
    ) {
        var companyIds = draft.companyIds();
        var assignments = draft.assignments();
        var experienceIds = draft.experienceIds();

        var steps = EnumSet.noneOf(OnboardingStep.class);
        steps.addAll(draft.completedSteps());

        switch (request.step()) {
            case COMPANIES -> {
                companyIds = request.companyIds();
                requirePublicCompanies(companyIds);

                if (!companyIds.equals(draft.companyIds())) {
                    steps.removeIf(
                            step -> step.ordinal()
                                    > OnboardingStep.COMPANIES.ordinal()
                    );
                }

                assignments = companyIds.stream()
                        .map(id -> draft.companyIds().contains(id)
                                ? draft.assignment(id)
                                : new Assignment(id, null, null))
                        .toList();
            }

            case JOBS -> {
                var inputs = request.jobs();

                requireExactCompanies(
                        companyIds,
                        inputs.stream()
                                .map(OnboardingPatchRequest.JobInput::companyId)
                                .toList()
                );

                var jobIds = inputs.stream()
                        .map(OnboardingPatchRequest.JobInput::jobId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .sorted()
                        .toList();

                var names = repository.lockActiveJobs(jobIds);

                if (names.size() != jobIds.size()) {
                    throw failure(ErrorCode.RESOURCE_NOT_FOUND);
                }

                var jobs = new HashMap<Long, Job>();

                for (var input : inputs) {
                    jobs.put(
                            input.companyId(),
                            input.jobId() == null
                                    ? new Job(null, input.customName(), true)
                                    : new Job(
                                    input.jobId(),
                                    names.get(input.jobId()),
                                    false
                            )
                    );
                }

                assignments = companyIds.stream()
                        .map(id -> new Assignment(
                                id,
                                jobs.get(id),
                                draft.assignment(id).interviewDate()
                        ))
                        .toList();
            }

            case DATES -> {
                var inputs = request.dates();

                requireExactCompanies(
                        companyIds,
                        inputs.stream()
                                .map(OnboardingPatchRequest.DateInput::companyId)
                                .toList()
                );

                var dates =
                        new HashMap<Long, OnboardingPatchRequest.DateInput>();

                LocalDate today = LocalDate.ofInstant(clock.instant(), SEOUL);

                for (var input : inputs) {
                    LocalDate date = input.interviewDate();

                    // 시간이 지나 과거가 된 기존 일정은 유지할 수 있다.
                    if (date != null
                            && date.isBefore(today)
                            && !date.equals(
                            draft.assignment(input.companyId()).interviewDate()
                    )) {
                        throw failure(ErrorCode.INVALID_INPUT);
                    }

                    dates.put(input.companyId(), input);
                }

                assignments = companyIds.stream()
                        .map(id -> new Assignment(
                                id,
                                draft.assignment(id).job(),
                                dates.get(id).interviewDate()
                        ))
                        .toList();
            }

            case EXPERIENCE -> {
                experienceIds = request.experienceIds();

                if (repository.lockExperiences(userId, experienceIds).size()
                        != experienceIds.size()) {
                    throw failure(ErrorCode.RESOURCE_NOT_FOUND);
                }
            }

            case NOTIFICATION_GUIDANCE -> request.requireGuidanceCompleted();

            case COMPLETE -> throw failure(ErrorCode.INVALID_INPUT);
        }

        steps.add(request.step());

        return new OnboardingDraft(
                companyIds, assignments, experienceIds, List.copyOf(steps)
        );
    }

    private void requirePublicCompanies(List<Long> ids) {
        if (ids.isEmpty()
                || repository.lockPublicCompanies(ids).size() != ids.size()) {
            throw failure(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private void requireExactCompanies(
            List<Long> selected, List<Long> submitted
    ) {
        if (submitted.size() != selected.size()
                || !new HashSet<>(selected).equals(new HashSet<>(submitted))) {
            throw failure(ErrorCode.INVALID_INPUT);
        }
    }

    private void requireEarlierSteps(
            OnboardingDraft draft, OnboardingStep requested
    ) {
        for (int i = 0; i < requested.ordinal(); i++) {
            if (!draft.completedSteps().contains(OnboardingStep.values()[i])) {
                throw failure(ErrorCode.ONBOARDING_INCOMPLETE);
            }
        }
    }

    private OnboardingDraft draft(State state) {
        var draft = codec.decode(state.json());

        if ((state.completedAt() != null)
                != draft.completedSteps().contains(OnboardingStep.COMPLETE)
                || (state.guidanceAt() != null)
                != draft.completedSteps().contains(
                OnboardingStep.NOTIFICATION_GUIDANCE
        )) {
            throw new IllegalStateException(
                    "Inconsistent stored onboarding state"
            );
        }

        return draft;
    }

    private OnboardingResponse response(
            long userId,
            String sessionId,
            long version,
            OnboardingDraft draft,
            boolean completed
    ) {
        return new OnboardingResponse(
                version,
                draft.currentStep(),
                draft.completedSteps(),
                draft.companyIds(),
                draft.assignments(),
                repository.experienceCount(userId),
                repository.notifications(userId, sessionId),
                completed
        );
    }

    private LogoutRepository.Session lockActor(
            long userId, String sessionId, int authVersion
    ) {
        actors.lockAccount(userId)
                .filter(account -> account.active()
                        && account.authVersion() == authVersion)
                .orElseThrow(() -> failure(ErrorCode.UNAUTHENTICATED));

        var session = actors.lockSession(userId, sessionId)
                .orElseThrow(() -> failure(ErrorCode.UNAUTHENTICATED));

        requireUsable(session);

        return session;
    }

    private void requireUsable(LogoutRepository.Session session) {
        if (!session.usable(clock.instant())) {
            throw failure(ErrorCode.UNAUTHENTICATED);
        }
    }

    private void requireMutableVersion(long version) {
        if (version == Long.MAX_VALUE) {
            throw failure(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static TransactionTemplate transaction(
            PlatformTransactionManager manager, boolean readOnly
    ) {
        var transaction = new TransactionTemplate(manager);
        transaction.setReadOnly(readOnly);
        transaction.setIsolationLevel(
                TransactionDefinition.ISOLATION_REPEATABLE_READ
        );
        transaction.setTimeout(10);

        return transaction;
    }

    private <T> T execute(
            TransactionTemplate transaction, Supplier<T> work
    ) {
        try {
            return Objects.requireNonNull(
                    transaction.execute(status -> work.get())
            );
        } catch (TransientDataAccessException
                 | DataAccessResourceFailureException
                 | TransactionException ex) {
            throw failure(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    private static BusinessException failure(ErrorCode code) {
        return new BusinessException(code);
    }
}