package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.dto.request.IssueEmailVerificationRequest;
import com.dadot.server.domain.auth.dto.response.IssueEmailVerificationResponse;
import com.dadot.server.domain.auth.entity.EmailVerification;
import com.dadot.server.domain.auth.entity.VerificationPurpose;
import com.dadot.server.domain.auth.repository.AuthRequestGuardRepository;
import com.dadot.server.domain.auth.repository.EmailIdentityLookup;
import com.dadot.server.domain.auth.repository.EmailVerificationDispatchRepository;
import com.dadot.server.domain.auth.repository.EmailVerificationDispatchRepository.Delivery;
import com.dadot.server.domain.auth.repository.EmailVerificationRepository;
import com.dadot.server.domain.auth.repository.VerificationRequestStore;
import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** 예약·발송 기록·활성화를 짧은 트랜잭션으로 처리한다. 외부 통신은 하지 않는다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, timeout = 10)
public class EmailVerificationDispatchTransaction {
    private final EmailVerificationRepository verifications;
    private final EmailIdentityLookup identities;
    private final AuthRequestGuardRepository guards;
    private final VerificationRequestStore requests;
    private final EmailVerificationDispatchRepository deliveries;
    private final VerificationCrypto hmac;
    private final VerificationTokenCrypto tokens;
    private final Clock clock;

    public record Prepared(IssueEmailVerificationResponse completed, Delivery delivery) {
        public boolean pending() { return completed == null && delivery == null; }
        @Override public String toString() { return "PreparedEmailVerification[REDACTED]"; }
    }
    private record Identity(Long user, String stamp) {
        @Override public String toString() { return "EmailDeliveryIdentity[REDACTED]"; }
    }

    public Prepared reserve(IssueEmailVerificationRequest request, String requestKey, Long actor) {
        var purpose = request.purpose();
        if (purpose == VerificationPurpose.PASSWORD_CHANGE && actor == null) fail(ErrorCode.UNAUTHENTICATED);
        String key = hmac.hmac("AUTH-01-key", requestKey);
        String body = hmac.hmac("AUTH-01-body", request.email(), purpose.name(),
                String.valueOf(purpose == VerificationPurpose.PASSWORD_CHANGE ? actor : null));
        lockKey(key);
        // 기존 계약과 같이 비밀번호 변경은 캐시 응답도 현재 계정 소유권을 확인한다.
        if (purpose == VerificationPurpose.PASSWORD_CHANGE) identity(request.email(), purpose, actor);
        var cached = requests.replay(key, body, now());
        if (cached.isPresent()) return new Prepared(cached.get(), null);
        var keyed = deliveries.byKey(key);
        if (keyed.isPresent() && !tokens.matches(keyed.get().body(), body)) fail(ErrorCode.IDEMPOTENCY_CONFLICT);
        lockEmail(request.email(), purpose);
        Identity identity = identity(request.email(), purpose, actor);
        Instant now = now();
        var pending = deliveries.byEmail(request.email(), purpose);
        if (pending.isPresent()) {
            Delivery previous = pending.get();
            if (previous.createdAt().isAfter(now)) fail(ErrorCode.SERVICE_UNAVAILABLE);
            boolean same = previous.key().equals(key);
            boolean unchanged = Objects.equals(previous.user(), identity.user())
                    && Objects.equals(previous.identityStamp(), identity.stamp());
            if (previous.expiresAt().isAfter(now) && unchanged) {
                if (same && previous.sentAt() != null) return new Prepared(finish(previous), null);
                if (previous.leaseUntil().isAfter(now)) {
                    if (!same) fail(ErrorCode.RATE_LIMITED);
                    return new Prepared(null, null);
                }
                if (same) return new Prepared(null, deliveries.claim(previous, UUID.randomUUID().toString(),
                        leaseUntil(now, previous.expiresAt())));
            }
            deliveries.delete(previous);
        }
        requireQuota(request.email(), purpose, now);
        String id = UUID.randomUUID().toString();
        String context = "AUTH-01-delivery:" + key + ":" + id;
        var delivery = new Delivery(key, body, id, identity.user(), identity.stamp(), request.email(), purpose,
                tokens.encrypt(hmac.generateCode(), context), VerificationTokenCrypto.KEY_VERSION,
                now, now.plusSeconds(600), UUID.randomUUID().toString(), now.plusSeconds(120), null);
        deliveries.insert(delivery);
        return new Prepared(null, delivery);
    }

    public void sent(Delivery expected) {
        lock(expected);
        if (requests.replay(expected.key(), expected.body(), now()).isPresent()) return;
        Delivery current = deliveries.byKey(expected.key()).orElseThrow(EmailVerificationDispatchTransaction::unavailable);
        if (!current.id().equals(expected.id()) || !current.owner().equals(expected.owner())
                || !current.leaseUntil().isAfter(now()) || !current.expiresAt().isAfter(now())) throw unavailable();
        if (current.sentAt() == null) deliveries.sent(current, now());
    }

    public IssueEmailVerificationResponse complete(Delivery expected) {
        lock(expected);
        var cached = requests.replay(expected.key(), expected.body(), now());
        if (cached.isPresent()) return cached.get();
        Delivery current = deliveries.byKey(expected.key()).orElseThrow(EmailVerificationDispatchTransaction::unavailable);
        if (!tokens.matches(current.body(), expected.body()) || current.sentAt() == null) throw unavailable();
        return finish(current);
    }

    public void cancel(Delivery expected) {
        lock(expected);
        deliveries.cancel(expected);
    }

    private IssueEmailVerificationResponse finish(Delivery delivery) {
        if (!delivery.expiresAt().isAfter(now())) throw unavailable();
        Identity current = identity(delivery.email(), delivery.purpose(), delivery.user());
        if (!Objects.equals(current.user(), delivery.user())
                || !Objects.equals(current.stamp(), delivery.identityStamp())) throw unavailable();
        String code = tokens.decrypt(delivery.encryptedCode(), delivery.keyVersion(), delivery.encryptionContext());
        var created = utc(delivery.createdAt());
        verifications.invalidatePrevious(delivery.email(), delivery.purpose(), created);
        verifications.saveAndFlush(EmailVerification.issue(delivery.id(), delivery.user(), delivery.email(),
                delivery.purpose(), hmac.codeDigest(delivery.id(), delivery.email(), delivery.purpose().name(), code), created));
        var response = new IssueEmailVerificationResponse(delivery.id(), delivery.expiresAt(), delivery.createdAt().plusSeconds(60));
        requests.complete(delivery.key(), delivery.body(), delivery.user(), response, delivery.createdAt());
        deliveries.delete(delivery);
        return response;
    }

    private Identity identity(String email, VerificationPurpose purpose, Long actor) {
        var account = identities.find(email);
        if (purpose == VerificationPurpose.SIGN_UP) {
            if (account.isPresent()) fail(ErrorCode.EMAIL_ALREADY_REGISTERED);
            return new Identity(null, null);
        }
        Long user = account.filter(EmailIdentityLookup.Identity::active).map(EmailIdentityLookup.Identity::userId).orElse(null);
        if (purpose == VerificationPurpose.PASSWORD_CHANGE && (actor == null || !actor.equals(user))) {
            fail(actor == null ? ErrorCode.UNAUTHENTICATED : ErrorCode.FORBIDDEN);
        }
        // 미가입/비활성 PASSWORD_RESET도 동일한 중립 발송·응답 경로를 사용한다.
        if (user == null) return new Identity(null, null);
        String stamp = deliveries.identityStamp(user, email);
        if (stamp == null) throw unavailable();
        return new Identity(user, stamp);
    }

    private void requireQuota(String email, VerificationPurpose purpose, Instant now) {
        var utc = utc(now);
        var latest = verifications.findFirstByEmailAndPurposeOrderBySentAtDesc(email, purpose);
        if (latest.isPresent() && latest.get().getSentAt().plusSeconds(60).isAfter(utc)) fail(ErrorCode.RATE_LIMITED);
        if (verifications.countByEmailAndPurposeAndSentAtGreaterThan(email, purpose, utc.minusHours(1)) >= 5) {
            fail(ErrorCode.RATE_LIMITED);
        }
    }
    private void lock(Delivery delivery) { lockKey(delivery.key()); lockEmail(delivery.email(), delivery.purpose()); }
    private void lockKey(String key) { guards.lock(hmac.hmac("AUTH-01-key-lock", key), now().plusSeconds(172800)); }
    private void lockEmail(String email, VerificationPurpose purpose) {
        guards.lock(hmac.hmac("verification-email-lock", email, purpose.name()), now().plusSeconds(172800));
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static Instant leaseUntil(Instant now, Instant expires) {
        return now.plusSeconds(120).isBefore(expires) ? now.plusSeconds(120) : expires;
    }
    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    private static BusinessException unavailable() { return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
    private static void fail(ErrorCode error) { throw new BusinessException(error); }
}
