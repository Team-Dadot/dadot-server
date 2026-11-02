package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.appconfig.service.SignupTermsService;
import com.dadot.server.domain.auth.dto.request.SocialSignupRequest;
import com.dadot.server.domain.auth.dto.response.SocialSignupResponse;
import com.dadot.server.domain.auth.repository.AuthRequestGuardRepository;
import com.dadot.server.domain.auth.repository.SocialSignupRepository;
import com.dadot.server.domain.auth.repository.SocialSignupRequestStore;
import com.dadot.server.domain.auth.repository.SocialSignupRequestStore.RequestKey;
import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.AccessTokenIssuer;
import com.dadot.server.global.security.SessionJwtAuthenticationConverter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SocialSignupTransaction {

    private final AuthRequestGuardRepository guards;
    private final SocialSignupRequestStore requests;
    private final SocialSignupRepository repository;
    private final SignupTermsService terms;
    private final VerificationCrypto hmac;
    private final VerificationTokenCrypto crypto;
    private final AccessTokenIssuer tokens;
    private final JwtDecoder decoder;
    private final SessionJwtAuthenticationConverter authentication;
    private final Clock clock;

    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            timeout = 10
    )
    public Result signup(
            SocialSignupRequest request,
            RequestKey key
    ) {
        guards.lock(
                hmac.hmac(
                        "AUTH-11-key-lock",
                        key.keyHash()
                ),
                clock.instant().plus(Duration.ofDays(2))
        );

        var cached = requests.find(key);

        // 증빙 만료·소비 검사보다 성공 요청 복구를 먼저 확인한다.
        if (cached.isPresent()) {
            return replay(key, cached.get());
        }

        String tokenHash = crypto.tokenHash(
                request.registrationToken()
        );

        var source = repository.findSource(tokenHash)
                .orElseThrow(this::invalidProof);

        lockSubject(
                source.provider(),
                source.subject()
        );

        var proof = repository.lockProof(
                        source,
                        tokenHash
                )
                .filter(value ->
                        value.usable(clock.instant())
                )
                .orElseThrow(this::invalidProof);

        if (repository.registered(source)) {
            return failure(
                    key,
                    ErrorCode.SOCIAL_ACCOUNT_ALREADY_REGISTERED
            );
        }

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
                    key,
                    ex.getErrorCode()
            );
        }

        // 약관 잠금을 기다리는 사이 만료됐는지도 확인한다.
        if (!proof.usable(consent.acceptedAt())) {
            throw invalidProof();
        }

        long userId = repository.createUser(
                proof,
                consent.termsId(),
                consent.acceptedAt()
        );

        var issuedAt = consent.acceptedAt()
                .truncatedTo(ChronoUnit.SECONDS);

        var expiresAt = issuedAt.plusSeconds(3600);
        String sessionId = UUID.randomUUID().toString();

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

        var response = new SocialSignupResponse(
                userId,
                accessToken,
                "Bearer",
                expiresAt,
                false
        );

        repository.consumeAndInvalidateOthers(
                source,
                consent.acceptedAt()
        );

        requests.complete(
                key,
                new SocialSignupRequestStore.Success(
                        source.provider(),
                        source.subject(),
                        response
                ),
                consent.acceptedAt()
        );

        return new Result(response, null);
    }

    private Result replay(
            RequestKey key,
            SocialSignupRequestStore.Stored stored
    ) {
        requests.validate(
                key,
                stored,
                clock.instant()
        );

        if ("FAILED".equals(stored.status())) {
            return new Result(
                    null,
                    requests.failure(stored)
            );
        }

        var success = requests.success(key, stored);
        var response = success.response();

        lockSubject(
                success.provider(),
                success.subject()
        );

        if (response.userId() != stored.userId()
                || !repository.lockActiveAccount(
                response.userId()
        )
                || !repository.lockIdentity(
                response.userId(),
                success.provider(),
                success.subject()
        )) {
            throw new BusinessException(
                    ErrorCode.UNAUTHENTICATED
            );
        }

        try {
            var jwt = decoder.decode(
                    response.accessToken()
            );

            var authenticated =
                    authentication.convert(jwt);

            if (authenticated == null
                    || !Long.toString(response.userId())
                    .equals(jwt.getSubject())
                    || !response.expiresAt()
                    .equals(jwt.getExpiresAt())) {
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

    private Result failure(
            RequestKey key,
            ErrorCode error
    ) {
        requests.fail(
                key,
                error,
                clock.instant()
                        .truncatedTo(ChronoUnit.MICROS)
        );

        return new Result(null, error);
    }

    // AUTH-05와 동일한 잠금 키를 사용한다.
    private void lockSubject(
            String provider,
            String subject
    ) {
        guards.lock(
                hmac.hmac(
                        "social-subject",
                        provider,
                        subject
                ),
                clock.instant().plus(Duration.ofDays(2))
        );
    }

    private BusinessException invalidProof() {
        return new BusinessException(
                ErrorCode.INVALID_REGISTRATION_TOKEN
        );
    }

    public record Result(
            SocialSignupResponse response,
            ErrorCode error
    ) {
    }
}