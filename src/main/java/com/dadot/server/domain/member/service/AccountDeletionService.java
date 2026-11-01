package com.dadot.server.domain.member.service;

import com.dadot.server.domain.auth.service.AppleLoginChallengeService;
import com.dadot.server.domain.auth.service.VerificationIpLimiter;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.domain.member.model.Member;
import com.dadot.server.domain.member.model.Member.*;
import com.dadot.server.domain.member.repository.AccountDeletionRepository;
import com.dadot.server.domain.member.repository.AccountDeletionRepository.*;
import com.dadot.server.domain.member.repository.MemberRepository;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.SessionAuthenticationToken;
import com.dadot.server.global.security.UserAccess;
import com.dadot.server.infrastructure.apple.AppleClient;
import com.dadot.server.infrastructure.kakao.KakaoAccountClient;
import com.dadot.server.infrastructure.kakao.KakaoClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AccountDeletionService {
    private final AccountDeletionRepository repository;
    private final MemberRepository members;
    private final UserAccess access;
    private final VerificationIpLimiter limiter;
    private final PasswordEncoder passwords;
    private final VerificationTokenCrypto crypto;
    private final AppleClient apple;
    private final KakaoClient kakao;
    private final KakaoAccountClient kakaoAccounts;
    private final AppleLoginChallengeService challenges;
    private final AccountDeletionWorker worker;
    private final Clock clock;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public AccountDeletionService(
            AccountDeletionRepository repository,
            MemberRepository members,
            UserAccess access,
            VerificationIpLimiter limiter,
            PasswordEncoder passwords,
            VerificationTokenCrypto crypto,
            AppleClient apple,
            KakaoClient kakao,
            KakaoAccountClient kakaoAccounts,
            AppleLoginChallengeService challenges,
            AccountDeletionWorker worker,
            Clock clock,
            PlatformTransactionManager manager
    ) {
        this.repository = repository;
        this.members = members;
        this.access = access;
        this.limiter = limiter;
        this.passwords = passwords;
        this.crypto = crypto;
        this.apple = apple;
        this.kakao = kakao;
        this.kakaoAccounts = kakaoAccounts;
        this.challenges = challenges;
        this.worker = worker;
        this.clock = clock;
        read = MemberService.transaction(manager, true);
        write = MemberService.transaction(manager, false);
    }

    public void delete(
            SessionAuthenticationToken actor,
            Delete body,
            String idempotencyKey,
            String remoteAddress
    ) {
        if (actor == null || !actor.isAuthenticated()) {
            throw Member.unauthenticated();
        }

        if (body == null
                || idempotencyKey == null
                || !idempotencyKey.matches(
                "(?i)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"
        )) {
            throw Member.invalid();
        }

        MemberService.storage("delete", () -> {
            Fingerprint key = repository.fingerprint(
                    actor,
                    idempotencyKey,
                    body
            );

            Stored existing = repository.find(
                    key.actor()
            ).orElse(null);

            if (existing == null) {
                limiter.consume(remoteAddress);

                Identity identity = Objects.requireNonNull(
                        read.execute(tx -> {
                            long user = access.require(actor, false);
                            return members.identity(user, false);
                        })
                );

                if (identity.provider() != body.provider()) {
                    throw Member.reauthenticationFailed();
                }

                Reauthenticated proof = verify(body, identity, key);

                existing = Objects.requireNonNull(write.execute(tx -> {
                    long user = actor.getPrincipal().userId();

                    repository.lockSources(
                            user,
                            identity,
                            clock.instant()
                    );

                    var raced = repository.find(key.actor());

                    if (raced.isPresent()) {
                        return raced.get();
                    }

                    access.require(actor, true);

                    Identity current = members.identity(user, true);

                    if (!current.equals(identity)
                            || !proof.validUntil().isAfter(
                            clock.instant()
                    )) {
                        throw Member.reauthenticationFailed();
                    }

                    String id = UUID.randomUUID().toString();

                    repository.start(
                            id,
                            user,
                            key,
                            current,
                            proof.revocationToken(),
                            clock.instant()
                    );

                    return repository.find(
                            key.actor()
                    ).orElseThrow();
                }));
            }

            requireSame(existing, key);

            if (!existing.succeeded()) {
                worker.process(existing.id());

                existing = repository.find(
                        key.actor()
                ).orElseThrow();

                if (!existing.succeeded()) {
                    throw new BusinessException(
                            ErrorCode.ACCOUNT_DELETION_PENDING
                    );
                }
            }

            return Boolean.TRUE;
        });
    }

    private Reauthenticated verify(
            Delete body,
            Identity identity,
            Fingerprint key
    ) {
        try {
            return switch (body.provider()) {
                case EMAIL -> {
                    if (!passwords.matches(
                            body.password(),
                            identity.passwordHash()
                    )) {
                        throw Member.reauthenticationFailed();
                    }

                    yield new Reauthenticated(
                            identity,
                            clock.instant().plusSeconds(60),
                            null
                    );
                }
                case KAKAO -> {
                    kakaoAccounts.checkConfigured();

                    var verified = kakao.verify(body.accessToken());

                    if (!identity.subject().equals(verified.subject())) {
                        throw Member.reauthenticationFailed();
                    }

                    yield new Reauthenticated(
                            identity,
                            verified.validUntil(),
                            null
                    );
                }
                case APPLE -> {
                    var verified = apple.verifyIdentity(
                            body.identityToken(),
                            body.nonce()
                    );

                    if (!identity.subject().equals(verified.subject())) {
                        throw Member.reauthenticationFailed();
                    }

                    String requestHash = crypto.tokenHash(
                            "member-delete:"
                                    + key.actor()
                                    + ":"
                                    + key.key()
                                    + ":"
                                    + key.body()
                    );

                    var claim = challenges.consume(
                            body.nonce(),
                            requestHash
                    );

                    if (!claim.owned()) {
                        throw new BusinessException(
                                ErrorCode.SERVICE_UNAVAILABLE
                        );
                    }

                    try {
                        var prepared = apple.prepareDeletion(
                                body.authorizationCode(),
                                body.nonce(),
                                verified
                        );

                        yield new Reauthenticated(
                                identity,
                                prepared.identity().validUntil().isBefore(
                                        claim.expiresAt()
                                )
                                        ? prepared.identity().validUntil()
                                        : claim.expiresAt(),
                                prepared.refreshToken()
                        );
                    } catch (RuntimeException ex) {
                        try {
                            challenges.fail(body.nonce(), requestHash);
                        } catch (RuntimeException ignored) {
                            // 이미 소비한 코드를 재교환하지 않는다.
                        }

                        throw ex;
                    }
                }
            };
        } catch (BusinessException ex) {
            if (ex.getErrorCode() == ErrorCode.INVALID_SOCIAL_CREDENTIAL) {
                throw Member.reauthenticationFailed();
            }

            throw ex;
        }
    }

    private void requireSame(Stored stored, Fingerprint key) {
        if (!crypto.matches(stored.key(), key.key())
                || !crypto.matches(stored.body(), key.body())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
        }

        if (stored.createdAt().isAfter(clock.instant())
                || !stored.expiresAt().isAfter(clock.instant())) {
            throw Member.unauthenticated();
        }
    }
}