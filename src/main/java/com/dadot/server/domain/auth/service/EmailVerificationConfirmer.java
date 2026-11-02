package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.dto.response.VerifyEmailVerificationResponse;
import com.dadot.server.domain.auth.entity.VerificationPurpose;
import com.dadot.server.domain.auth.repository.EmailIdentityLookup;
import com.dadot.server.domain.auth.repository.VerificationConfirmationRepository;
import com.dadot.server.domain.auth.repository.VerificationConfirmationRepository.Verification;
import com.dadot.server.domain.auth.repository.VerificationConfirmationRequestStore;
import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class EmailVerificationConfirmer {

    private final VerificationConfirmationRepository verifications;
    private final VerificationConfirmationRequestStore requests;
    private final EmailIdentityLookup identities;
    private final VerificationCrypto hmac;
    private final VerificationTokenCrypto tokens;
    private final Clock clock;

    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            timeout = 10
    )
    public Result confirm(
            String id,
            String code,
            String requestKey,
            Long actor
    ) {
        var found = verifications.lock(id);

        if (found.isEmpty()) {
            return Result.failure(
                    ErrorCode.RESOURCE_NOT_FOUND
            );
        }

        Verification source = found.get();

        if (source.purpose() == VerificationPurpose.PASSWORD_CHANGE) {
            if (actor == null) {
                return Result.failure(
                        ErrorCode.UNAUTHENTICATED
                );
            }

            if (!Objects.equals(actor, source.userId())) {
                return Result.failure(
                        ErrorCode.FORBIDDEN
                );
            }
        }

        // 잠금을 기다리는 동안 만료될 수 있으므로 잠금 획득 후 읽는다.
        Instant now = clock.instant()
                .truncatedTo(ChronoUnit.MICROS);

        String operation =
                "POST /v1/auth/email-verifications/" + id + "/verify";

        String keyHash = hmac.hmac(
                "AUTH-02-key",
                requestKey
        );

        String bodyHmac = hmac.hmac(
                "AUTH-02-body",
                id,
                code,
                source.purpose() == VerificationPurpose.PASSWORD_CHANGE
                        ? String.valueOf(actor)
                        : "null"
        );

        var stored = requests.find(
                operation,
                keyHash
        );

        if (stored.isPresent()) {
            var previous = stored.get();

            if (!tokens.matches(previous.bodyHmac(), bodyHmac)
                    || !previous.expiresAt().isAfter(now)) {
                return Result.failure(
                        ErrorCode.IDEMPOTENCY_CONFLICT
                );
            }

            if ("FAILED".equals(previous.status())) {
                return Result.failure(
                        requests.failure(previous)
                );
            }

            if (!"SUCCEEDED".equals(previous.status())) {
                return Result.failure(
                        ErrorCode.SERVICE_UNAVAILABLE
                );
            }

            return replay(
                    source,
                    previous,
                    operation,
                    keyHash,
                    now
            );
        }

        if (source.failedAttempts() >= 5) {
            return Result.failure(
                    ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED
            );
        }

        if (source.invalidatedAt() != null
                || source.verifiedAt() != null
                || !source.expiresAt().isAfter(now)) {
            return Result.failure(
                    ErrorCode.VERIFICATION_EXPIRED
            );
        }

        String digest = hmac.codeDigest(
                id,
                source.email(),
                source.purpose().name(),
                code
        );

        if (!tokens.matches(source.codeDigest(), digest)) {
            verifications.recordMismatch(
                    source,
                    now
            );

            ErrorCode error = source.failedAttempts() + 1 >= 5
                    ? ErrorCode.VERIFICATION_ATTEMPTS_EXCEEDED
                    : ErrorCode.INVALID_VERIFICATION_CODE;

            requests.saveMismatch(
                    operation,
                    keyHash,
                    bodyHmac,
                    source.userId(),
                    error,
                    now
            );

            return Result.failure(error);
        }

        if (!eligible(source)) {
            return Result.failure(
                    ErrorCode.INVALID_VERIFICATION_CODE
            );
        }

        String token = tokens.generateToken();

        var response = new VerifyEmailVerificationResponse(
                token,
                now.plusSeconds(600)
        );

        verifications.createProof(
                source,
                tokens.tokenHash(token),
                now
        );

        requests.saveSuccess(
                operation,
                keyHash,
                bodyHmac,
                source.userId(),
                response,
                now
        );

        return Result.success(response);
    }

    private Result replay(
            Verification source,
            VerificationConfirmationRequestStore.Stored stored,
            String operation,
            String keyHash,
            Instant now
    ) {
        if (source.verifiedAt() == null
                || source.invalidatedAt() != null) {
            return Result.failure(
                    ErrorCode.VERIFICATION_EXPIRED
            );
        }

        if (!eligible(source)) {
            return Result.failure(
                    ErrorCode.INVALID_VERIFICATION_CODE
            );
        }

        var proof = verifications.proof(source.id());

        if (proof.isEmpty()
                || proof.get().consumedAt() != null
                || proof.get().invalidatedAt() != null
                || !proof.get().expiresAt().isAfter(now)) {
            return Result.failure(
                    ErrorCode.VERIFICATION_EXPIRED
            );
        }

        var response = requests.response(
                stored,
                operation,
                keyHash
        );

        if (!tokens.matches(
                proof.get().tokenHash(),
                tokens.tokenHash(response.verificationToken())
        ) || !proof.get().expiresAt().equals(response.expiresAt())) {
            return Result.failure(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        }

        return Result.success(response);
    }

    private boolean eligible(Verification source) {
        if (source.purpose() == VerificationPurpose.SIGN_UP) {
            return true;
        }

        return source.userId() != null
                && identities.find(source.email())
                .filter(EmailIdentityLookup.Identity::active)
                .filter(identity ->
                        identity.userId() == source.userId()
                )
                .isPresent();
    }

    public record Result(
            VerifyEmailVerificationResponse response,
            ErrorCode error
    ) {
        static Result success(
                VerifyEmailVerificationResponse response
        ) {
            return new Result(response, null);
        }

        static Result failure(ErrorCode error) {
            return new Result(null, error);
        }
    }
}