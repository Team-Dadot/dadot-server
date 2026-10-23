package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.appconfig.service.SignupTermsService;
import com.dadot.server.domain.auth.dto.request.EmailSignupRequest;
import com.dadot.server.domain.auth.dto.response.EmailSignupResponse;
import com.dadot.server.domain.auth.repository.*;
import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.AccessTokenIssuer;
import com.dadot.server.global.security.SessionJwtAuthenticationConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmailSignupTransaction {

    private final AuthRequestGuardRepository guards;
    private final SignupRequestStore requests;
    private final EmailSignupRepository repository;
    private final EmailIdentityLookup identities;
    private final SignupTermsService terms;
    private final VerificationCrypto hmac;
    private final VerificationTokenCrypto crypto;
    private final PasswordEncoder passwords;
    private final AccessTokenIssuer tokens;
    private final JwtDecoder decoder;
    private final SessionJwtAuthenticationConverter authentication;
    private final Clock clock;

    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            timeout = 15
    )
    public Result signup(
            EmailSignupRequest request,
            String requestKey
    ) {
        String keyHash = hmac.hmac(
                "AUTH-03-key",
                requestKey
        );

        String bodyHmac = hmac.hmac(
                "AUTH-03-body",
                request.email(),
                request.password(),
                request.verificationToken(),
                request.acceptedTermsIds()
                        .stream()
                        .sorted()
                        .toList()
                        .toString()
        );

        guards.lock(
                hmac.hmac("AUTH-03-key-lock", keyHash),
                clock.instant().plus(Duration.ofDays(2))
        );

        var cached = requests.find(keyHash);

        if (cached.isPresent()) {
            return replay(
                    cached.get(),
                    keyHash,
                    bodyHmac,
                    request.email()
            );
        }

        // AUTH-01 재발송과 동일한 잠금을 사용한다.
        guards.lock(
                hmac.hmac(
                        "verification-email-lock",
                        request.email(),
                        "SIGN_UP"
                ),
                clock.instant().plus(Duration.ofDays(2))
        );

        var proof = repository.lockProof(
                        crypto.tokenHash(
                                request.verificationToken()
                        ),
                        request.email()
                )
                .filter(value ->
                        value.usable(clock.instant())
                )
                .orElseThrow(() ->
                        new BusinessException(
                                ErrorCode.INVALID_VERIFICATION_TOKEN
                        )
                );

        if (identities.find(request.email()).isPresent()) {
            return failure(
                    keyHash,
                    bodyHmac,
                    ErrorCode.EMAIL_ALREADY_REGISTERED
            );
        }

        // 전체 가입이 공유하는 약관 잠금 전에 계산한다.
        String passwordHash = passwords.encode(
                request.password()
        );

        SignupTermsService.AcceptedTerms consent;

        try {
            consent = terms.validateAndLock(
                    request.acceptedTermsIds()
            );
        } catch (BusinessException ex) {
            if (ex.getErrorCode() != ErrorCode.TERMS_NOT_ACCEPTED
                    && ex.getErrorCode() != ErrorCode.TERMS_CHANGED
                    && ex.getErrorCode() != ErrorCode.SIGNUP_TERMS_UNAVAILABLE) {
                throw ex;
            }

            return failure(
                    keyHash,
                    bodyHmac,
                    ex.getErrorCode()
            );
        }

        // 해시 계산이나 잠금 대기 중 만료됐는지도 확인한다.
        if (!proof.usable(consent.acceptedAt())) {
            throw new BusinessException(
                    ErrorCode.INVALID_VERIFICATION_TOKEN
            );
        }

        long userId = repository.createUser(
                request.email(),
                passwordHash,
                consent.termsId(),
                consent.acceptedAt()
        );

        String sessionId = UUID.randomUUID().toString();

        Instant issuedAt = consent.acceptedAt()
                .truncatedTo(ChronoUnit.SECONDS);

        Instant expiresAt = issuedAt.plusSeconds(3600);

        repository.createSession(
                sessionId,
                userId,
                issuedAt,
                expiresAt
        );

        String accessToken = tokens.issue(
                userId,
                sessionId,
                0,
                issuedAt,
                expiresAt
        );

        var response = new EmailSignupResponse(
                userId,
                accessToken,
                "Bearer",
                expiresAt,
                false
        );

        repository.consume(
                proof.id(),
                consent.acceptedAt()
        );

        requests.complete(
                keyHash,
                bodyHmac,
                response,
                consent.acceptedAt()
        );

        return new Result(response, null);
    }

    private Result failure(
            String keyHash,
            String bodyHmac,
            ErrorCode error
    ) {
        requests.fail(
                keyHash,
                bodyHmac,
                error,
                clock.instant()
        );

        return new Result(null, error);
    }

    private Result replay(
            SignupRequestStore.Stored stored,
            String keyHash,
            String bodyHmac,
            String email
    ) {
        if (!crypto.matches(
                stored.bodyHmac(),
                bodyHmac
        ) || !stored.expiresAt().isAfter(clock.instant())) {

            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_CONFLICT
            );
        }

        if ("FAILED".equals(stored.status())) {
            return new Result(
                    null,
                    requests.failure(stored)
            );
        }

        var response = requests.response(
                stored,
                keyHash
        );

        try {
            var jwt = decoder.decode(
                    response.accessToken()
            );

            authentication.convert(jwt);

            if (response.userId() != stored.userId()
                    || !Long.toString(stored.userId())
                    .equals(jwt.getSubject())
                    || !response.expiresAt()
                    .equals(jwt.getExpiresAt())
                    || !repository.sameIdentity(
                    stored.userId(),
                    email
            )) {
                throw new BusinessException(
                        ErrorCode.UNAUTHENTICATED
                );
            }

            return new Result(response, null);

        } catch (AuthenticationServiceException ex) {
            throw new BusinessException(
                    ErrorCode.SERVICE_UNAVAILABLE
            );
        } catch (
                JwtException
                | AuthenticationException ex
        ) {
            throw new BusinessException(
                    ErrorCode.UNAUTHENTICATED
            );
        }
    }

    public record Result(
            EmailSignupResponse response,
            ErrorCode error
    ) {
    }
}