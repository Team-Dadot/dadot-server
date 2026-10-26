package com.dadot.server.domain.disclosure.service;

import com.dadot.server.domain.company.support.CompanyLogoUrlResolver;
import com.dadot.server.domain.disclosure.model.DisclosureReview;
import com.dadot.server.domain.disclosure.model.DisclosureSync;
import com.dadot.server.domain.disclosure.model.DisclosureReview.Request;
import com.dadot.server.domain.disclosure.model.DisclosureReview.Result;
import com.dadot.server.domain.disclosure.model.DisclosureReview.Status;
import com.dadot.server.domain.disclosure.repository.DisclosureReviewRepository;
import com.dadot.server.domain.quiz.repository.QuizDisclosureImpactRepository;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.AdminAccess;
import com.dadot.server.global.security.SessionAuthenticationToken;
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
import java.util.LinkedHashSet;
import java.util.Objects;

@Service
public class DisclosureReviewService {

    private static final Logger log =
            LoggerFactory.getLogger(DisclosureReviewService.class);

    private final DisclosureReviewRepository repository;
    private final AdminAccess access;
    private final QuizDisclosureImpactRepository quizImpact;
    private final CompanyLogoUrlResolver logos;
    private final Clock clock;
    private final TransactionTemplate write;

    public DisclosureReviewService(
            DisclosureReviewRepository repository,
            AdminAccess access,
            QuizDisclosureImpactRepository quizImpact,
            CompanyLogoUrlResolver logos,
            Clock clock,
            PlatformTransactionManager manager
    ) {
        this.repository = repository;
        this.access = access;
        this.quizImpact = quizImpact;
        this.logos = logos;
        this.clock = clock;

        write = new TransactionTemplate(manager);
        write.setTimeout(10);
        write.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED
        );
    }

    public Result review(
            SessionAuthenticationToken actor,
            long disclosureId,
            Request request
    ) {
        DisclosureReview.id(disclosureId);

        if (request == null) {
            throw DisclosureSync.invalid();
        }

        try {
            return Objects.requireNonNull(
                    write.execute(
                            tx -> apply(
                                    actor,
                                    disclosureId,
                                    request
                            )
                    )
            );

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

    private Result apply(
            SessionAuthenticationToken actor,
            long disclosureId,
            Request request
    ) {
        access.require(actor, true);

        long companyId = repository.companyId(disclosureId);

        // 같은 기업의 정정 그래프 변경을 직렬화한다.
        // 외부 호출은 수행하지 않는다.
        repository.lockCompany(companyId);

        var filing = repository.lockFiling(disclosureId);

        var content = repository.lockContent(
                disclosureId,
                request.disclosureVersionId()
        );

        if (filing.version() != request.expectedVersion()) {
            throw new BusinessException(
                    ErrorCode.VERSION_CONFLICT
            );
        }

        Long correction = request.correctionSpecified()
                ? request.correctionOf()
                : filing.correctionOf();

        boolean correctionChanged = !Objects.equals(
                correction,
                filing.correctionOf()
        );

        if (correctionChanged
                && repository.hasProtectedHistory(disclosureId)) {
            throw DisclosureReview.transition();
        }

        if (content.status() == request.status()) {
            if (!correctionChanged) {
                throw DisclosureReview.transition();
            }

        } else if (!content.status().canChangeTo(request.status())) {
            throw DisclosureReview.transition();
        }

        repository.validateCorrection(
                filing,
                correction
        );

        if (correction != null
                && repository.hasOtherReplacement(
                correction,
                disclosureId
        )) {
            throw DisclosureReview.transition();
        }

        if (request.status() == Status.IN_REVIEW
                || request.status() == Status.APPROVED
                || request.status() == Status.PUBLISHED) {
            repository.requireOriginal(content.id());
        }

        if (request.status() == Status.PUBLISHED
                && (
                repository.hasCorrection(disclosureId)
                        || repository.hasNewerPublication(
                        disclosureId,
                        content.id()
                )
        )) {
            throw DisclosureReview.transition();
        }

        if (request.status() == Status.SUPERSEDED
                && !repository.hasReplacement(disclosureId)
                && !repository.hasPublishedSibling(
                disclosureId,
                content.id()
        )) {
            throw DisclosureReview.transition();
        }

        var now = clock.instant();
        long actorId = actor.getPrincipal().userId();

        if (correctionChanged) {
            repository.setCorrection(
                    filing,
                    correction,
                    actorId,
                    request.reason(),
                    now
            );
        }

        if (request.status() == Status.PUBLISHED) {
            repository.supersedeOlder(
                    disclosureId,
                    content.id(),
                    actorId,
                    request.reason(),
                    now
            );
        }

        if (correction != null
                && (
                correctionChanged
                        || request.status() == Status.PUBLISHED
        )) {
            repository.lockFiling(correction);

            Status retired = request.status() == Status.PUBLISHED
                    ? Status.SUPERSEDED
                    : Status.SUSPENDED;

            if (repository.retireTarget(
                    correction,
                    retired,
                    actorId,
                    request.reason(),
                    now
            )) {
                repository.touch(
                        correction,
                        now
                );
            }
        }

        repository.change(
                content,
                request.status(),
                actorId,
                request.reason(),
                now
        );

        repository.touch(
                disclosureId,
                now
        );

        var result = repository.summary(
                disclosureId,
                content.id(),
                key -> logo(companyId, key)
        );

        var changedDisclosures = new LinkedHashSet<Long>();
        changedDisclosures.add(disclosureId);

        if (correction != null
                && (
                correctionChanged
                        || request.status() == Status.PUBLISHED
        )) {
            changedDisclosures.add(correction);
        }

        long affectedQuizzes = quizImpact.suspendUnavailable(
                changedDisclosures,
                actorId,
                request.reason(),
                now
        );

        // 면접 테이블·API는 아직 없으며 도입 시 해당 영향 처리도 연결한다.
        access.require(actor, true);

        return new Result(
                result,
                affectedQuizzes,
                0
        );
    }

    private String logo(
            long companyId,
            String key
    ) {
        try {
            return logos.resolve(key);

        } catch (IllegalStateException ex) {
            log.warn(
                    "Invalid company logo key. companyId={}",
                    companyId
            );

            return null;
        }
    }
}