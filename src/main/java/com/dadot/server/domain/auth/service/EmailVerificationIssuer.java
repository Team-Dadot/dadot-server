package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.dto.request.IssueEmailVerificationRequest;
import com.dadot.server.domain.auth.dto.response.IssueEmailVerificationResponse;
import com.dadot.server.domain.auth.entity.EmailVerification;
import com.dadot.server.domain.auth.entity.VerificationPurpose;
import com.dadot.server.domain.auth.repository.*;
import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailVerificationIssuer {

    private final EmailVerificationRepository verifications;
    private final EmailIdentityLookup identities;
    private final AuthRequestGuardRepository guards;
    private final VerificationRequestStore requests;
    private final VerificationCrypto crypto;
    private final VerificationMailer mailer;
    private final Clock clock;

    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            timeout = 30
    )
    public IssueEmailVerificationResponse issue(
            IssueEmailVerificationRequest request,
            String requestKey,
            Long authenticatedUserId
    ) {
        VerificationPurpose purpose = request.purpose();

        Long actor = purpose == VerificationPurpose.PASSWORD_CHANGE
                ? authenticatedUserId
                : null;

        if (purpose == VerificationPurpose.PASSWORD_CHANGE) {
            validatePasswordChange(request.email(), actor);
        }

        String keyHash = crypto.hmac(
                "AUTH-01-key",
                requestKey
        );

        String bodyHmac = crypto.hmac(
                "AUTH-01-body",
                request.email(),
                purpose.name(),
                String.valueOf(actor)
        );

        guards.lock(
                crypto.hmac("AUTH-01-key-lock", keyHash),
                clock.instant().plus(Duration.ofDays(2))
        );

        var cached = requests.replay(
                keyHash,
                bodyHmac,
                clock.instant()
        );

        if (cached.isPresent()) {
            return cached.get();
        }

        guards.lock(
                crypto.hmac(
                        "verification-email-lock",
                        request.email(),
                        purpose.name()
                ),
                clock.instant().plus(Duration.ofDays(2))
        );

        Instant now = clock.instant()
                .truncatedTo(ChronoUnit.MICROS);

        LocalDateTime utcNow = LocalDateTime.ofInstant(
                now,
                ZoneOffset.UTC
        );

        var identity = identities.find(request.email());

        if (purpose == VerificationPurpose.SIGN_UP
                && identity.isPresent()) {
            throw new BusinessException(
                    ErrorCode.EMAIL_ALREADY_REGISTERED
            );
        }

        /*
         * PASSWORD_RESET은 계정 존재 여부와 무관하게
         * 동일한 발급·메일·응답 경로를 사용한다.
         *
         * user_id가 NULL인 PASSWORD_RESET은
         * AUTH-02에서 인증 완료 증빙을 발급하면 안 된다.
         */
        Long userId = purpose == VerificationPurpose.SIGN_UP
                ? null
                : identity
                .filter(EmailIdentityLookup.Identity::active)
                .map(EmailIdentityLookup.Identity::userId)
                .orElse(null);

        if (purpose == VerificationPurpose.PASSWORD_CHANGE
                && !actor.equals(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        var latest =
                verifications.findFirstByEmailAndPurposeOrderBySentAtDesc(
                        request.email(),
                        purpose
                );

        if (latest.isPresent()
                && latest.get().getSentAt()
                .plusSeconds(60)
                .isAfter(utcNow)) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }

        long issuedCount =
                verifications.countByEmailAndPurposeAndSentAtGreaterThan(
                        request.email(),
                        purpose,
                        utcNow.minusHours(1)
                );

        if (issuedCount >= 5) {
            throw new BusinessException(ErrorCode.RATE_LIMITED);
        }

        String id = UUID.randomUUID().toString();
        String code = crypto.generateCode();

        String digest = crypto.codeDigest(
                id,
                request.email(),
                purpose.name(),
                code
        );

        verifications.invalidatePrevious(
                request.email(),
                purpose,
                utcNow
        );

        verifications.saveAndFlush(
                EmailVerification.issue(
                        id,
                        userId,
                        request.email(),
                        purpose,
                        digest,
                        utcNow
                )
        );

        mailer.send(request.email(), code, purpose);

        var response = new IssueEmailVerificationResponse(
                id,
                now.plusSeconds(600),
                now.plusSeconds(60)
        );

        requests.complete(
                keyHash,
                bodyHmac,
                userId,
                response,
                now
        );

        return response;
    }

    private void validatePasswordChange(
            String email,
            Long actor
    ) {
        if (actor == null) {
            throw new BusinessException(
                    ErrorCode.UNAUTHENTICATED
            );
        }

        var identity = identities.find(email);

        if (identity.isEmpty()
                || !identity.get().active()
                || identity.get().userId() != actor) {
            throw new BusinessException(
                    ErrorCode.FORBIDDEN
            );
        }
    }
}