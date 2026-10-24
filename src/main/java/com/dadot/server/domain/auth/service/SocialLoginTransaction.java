package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.dto.response.SocialLoginResponse;
import com.dadot.server.domain.auth.repository.AuthRequestGuardRepository;
import com.dadot.server.domain.auth.repository.SocialLoginRepository;
import com.dadot.server.domain.auth.repository.SocialLoginRequestStore;
import com.dadot.server.domain.auth.repository.SocialLoginRequestStore.RequestKey;
import com.dadot.server.domain.auth.repository.SocialLoginRequestStore.Result;
import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.AccessTokenIssuer;
import com.dadot.server.global.security.SessionJwtAuthenticationConverter;
import com.dadot.server.infrastructure.kakao.KakaoClient;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(
        isolation = Isolation.READ_COMMITTED,
        timeout = 5
)
public class SocialLoginTransaction {

    private final SocialLoginRepository repository;
    private final SocialLoginRequestStore requests;
    private final AuthRequestGuardRepository guards;
    private final VerificationCrypto hmac;
    private final VerificationTokenCrypto crypto;
    private final AccessTokenIssuer tokens;
    private final JwtDecoder decoder;
    private final SessionJwtAuthenticationConverter authentication;
    private final Clock clock;

    public Optional<SocialLoginResponse> replay(
            RequestKey key
    ) {
        lockRequest(key);
        return restore(key);
    }

    public SocialLoginResponse complete(
            RequestKey key,
            KakaoClient.Verified verified
    ) {
        if (!"KAKAO".equals(key.provider())) {
            throw new IllegalArgumentException(
                    "Kakao request key required"
            );
        }

        return complete(
                key,
                verified.subject(),
                verified.validUntil(),
                null
        );
    }

    public SocialLoginResponse complete(
            RequestKey key,
            String subject,
            Instant validUntil,
            String email
    ) {
        lockRequest(key);

        var restored = restore(key);

        if (restored.isPresent()) {
            return restored.get();
        }

        lockSubject(key.provider(), subject);

        var owner = repository.findUser(
                key.provider(),
                subject
        );

        SocialLoginResponse response;

        if (owner.isPresent()) {
            long userId = owner.get();

            var account = repository.lockAccount(userId)
                    .filter(SocialLoginRepository.Account::active)
                    .orElseThrow(this::invalid);

            if (!repository.lockIdentity(
                    userId,
                    key.provider(),
                    subject
            )) {
                throw invalid();
            }

            var now = clock.instant()
                    .truncatedTo(ChronoUnit.SECONDS);

            if (!validUntil.isAfter(clock.instant())) {
                throw invalid();
            }

            var expiresAt = now.plusSeconds(3600);
            String sessionId = UUID.randomUUID().toString();

            repository.createSession(
                    sessionId,
                    userId,
                    now,
                    expiresAt
            );

            response = SocialLoginResponse.login(
                    userId,
                    tokens.issue(
                            userId,
                            sessionId,
                            account.authVersion(),
                            now,
                            expiresAt
                    ),
                    expiresAt,
                    account.onboarded()
            );

        } else {
            var now = clock.instant()
                    .truncatedTo(ChronoUnit.MICROS);

            if (!validUntil.isAfter(now)) {
                throw invalid();
            }

            String registrationToken = crypto.generateToken();
            var expiresAt = now.plusSeconds(600);

            repository.createProof(
                    UUID.randomUUID().toString(),
                    key.provider(),
                    subject,
                    email,
                    crypto.tokenHash(registrationToken),
                    now,
                    expiresAt
            );

            response = SocialLoginResponse.signup(
                    registrationToken,
                    expiresAt
            );
        }

        requests.complete(
                key,
                new Result(subject, response),
                clock.instant().truncatedTo(ChronoUnit.MICROS)
        );

        return response;
    }

    private Optional<SocialLoginResponse> restore(
            RequestKey key
    ) {
        var stored = requests.find(key);

        if (stored.isEmpty()) {
            return Optional.empty();
        }

        var result = requests.decode(
                key,
                stored.get(),
                clock.instant()
        );

        lockSubject(
                key.provider(),
                result.subject()
        );

        var response = result.response();

        if (response.status()
                == SocialLoginResponse.Status.LOGIN_COMPLETED) {

            repository.lockAccount(response.userId())
                    .filter(SocialLoginRepository.Account::active)
                    .orElseThrow(this::invalid);

            if (!repository.lockIdentity(
                    response.userId(),
                    key.provider(),
                    result.subject()
            )) {
                throw invalid();
            }

            try {
                var jwt = decoder.decode(
                        response.accessToken()
                );

                var authenticated = authentication.convert(jwt);

                if (authenticated == null
                        || !Long.toString(response.userId())
                        .equals(jwt.getSubject())
                        || !response.expiresAt()
                        .equals(jwt.getExpiresAt())) {
                    throw invalid();
                }

            } catch (
                    JwtException
                    | OAuth2AuthenticationException ex
            ) {
                throw invalid();

            } catch (AuthenticationServiceException ex) {
                throw new BusinessException(
                        ErrorCode.SERVICE_UNAVAILABLE
                );
            }

        } else {
            if (repository.findUser(
                    key.provider(),
                    result.subject()
            ).isPresent()
                    || !repository.lockUsableProof(
                    key.provider(),
                    result.subject(),
                    crypto.tokenHash(response.registrationToken()),
                    response.registrationExpiresAt(),
                    clock.instant()
            )) {
                throw invalid();
            }
        }

        return Optional.of(response);
    }

    private void lockRequest(RequestKey key) {
        guards.lock(
                hmac.hmac(
                        "social-login-idempotency",
                        key.scopeHash(),
                        key.keyHash()
                ),
                clock.instant().plus(Duration.ofDays(2))
        );
    }

    // 소셜 회원가입에서도 같은 제공자·subject 잠금을 사용한다.
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

    private BusinessException invalid() {
        return new BusinessException(
                ErrorCode.INVALID_SOCIAL_CREDENTIAL
        );
    }
}