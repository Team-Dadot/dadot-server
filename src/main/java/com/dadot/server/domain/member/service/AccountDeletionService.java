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
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
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
    private final AccountReauthenticationLimiter attempts;
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
            AccountReauthenticationLimiter attempts,
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
        this.attempts = attempts;
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
                existing = begin(
                        actor,
                        body,
                        key,
                        remoteAddress
                );
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

    private Stored begin(
            SessionAuthenticationToken actor,
            Delete body,
            Fingerprint key,
            String remoteAddress
    ) {
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

        if (body.provider() == Provider.EMAIL) {
            attempts.consume(actor.getPrincipal().userId());
        }

        Reauthenticated proof;

        try {
            proof = verify(body, identity, key);
        } catch (BusinessException ex) {
            // 첫 요청이 nonce를 소유한 동안 접수가 완료됐을 수 있다.
            // 이 호출은 nonce 실패 표시를 남기거나 코드를 다시
            // 교환하지 않는다.
            if (body.provider() == Provider.APPLE) {
                var accepted = repository.find(key.actor());

                if (accepted.isPresent()) {
                    return accepted.get();
                }
            }

            throw ex;
        }

        try {
            return Objects.requireNonNull(
                    write.execute(tx -> {
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

                        repository.start(
                                UUID.randomUUID().toString(),
                                user,
                                key,
                                current,
                                proof.revocationToken(),
                                clock.instant()
                        );

                        if (!proof.validUntil().isAfter(clock.instant())) {
                            throw Member.reauthenticationFailed();
                        }

                        return repository.find(
                                key.actor()
                        ).orElseThrow();
                    })
            );
        } catch (RuntimeException ex) {
            if (body.provider() == Provider.APPLE) {
                markAppleFailure(body, key);
            }

            throw ex;
        }
    }

    private String appleRequestHash(Fingerprint key) {
        return crypto.tokenHash(
                "member-delete:"
                        + key.actor()
                        + ":"
                        + key.key()
                        + ":"
                        + key.body()
        );
    }

    private void markAppleFailure(
            Delete body,
            Fingerprint key
    ) {
        try {
            // 커밋 응답만 유실된 경우 이미 접수한 작업을
            // 실패로 바꾸지 않는다.
            if (repository.find(key.actor()).isEmpty()) {
                challenges.fail(
                        body.nonce(),
                        appleRequestHash(key)
                );
            }
        } catch (DataAccessException | TransactionException ex) {
            // 이 표시도 실패하면 60초 진행 기한 뒤
            // 새 Apple 인증을 요구한다.
            MemberService.logStorage(
                    "appleDeletionFailureMarker",
                    ex
            );
        }
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

                    String requestHash = appleRequestHash(key);

                    var claim = challenges.consumeForDeletion(
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
                        markAppleFailure(body, key);
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

    private void requireSame(
            Stored stored,
            Fingerprint key
    ) {
        if (!crypto.matches(stored.key(), key.key())
                || !crypto.matches(stored.body(), key.body())) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_CONFLICT
            );
        }

        if (stored.createdAt().isAfter(clock.instant())
                || !stored.expiresAt().isAfter(clock.instant())) {
            throw Member.unauthenticated();
        }
    }
}