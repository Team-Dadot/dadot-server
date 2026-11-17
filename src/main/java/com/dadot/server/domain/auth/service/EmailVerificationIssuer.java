package com.dadot.server.domain.auth.service;

import com.dadot.server.domain.auth.dto.request.IssueEmailVerificationRequest;
import com.dadot.server.domain.auth.dto.response.IssueEmailVerificationResponse;
import com.dadot.server.domain.auth.repository.EmailVerificationDispatchRepository.Delivery;
import com.dadot.server.domain.auth.support.VerificationTokenCrypto;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** SMTP 대기와 동일 키 요청의 대기는 DB 트랜잭션 밖에서 수행한다. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EmailVerificationIssuer {
    private static final Logger log = LoggerFactory.getLogger(EmailVerificationIssuer.class);
    private static final long WAIT_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final EmailVerificationDispatchTransaction transaction;
    private final VerificationTokenCrypto tokens;
    private final VerificationMailer mailer;
    private final Semaphore sends = new Semaphore(5);

    public IssueEmailVerificationResponse issue(IssueEmailVerificationRequest request, String requestKey, Long actor) {
        long started = System.nanoTime();
        while (true) {
            var prepared = transaction.reserve(request, requestKey, actor);
            if (prepared.completed() != null) return prepared.completed();
            if (!prepared.pending()) return send(prepared.delivery());
            if (System.nanoTime()-started >= WAIT_NANOS) throw unavailable();
            try { TimeUnit.MILLISECONDS.sleep(100); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw unavailable(); }
        }
    }

    private IssueEmailVerificationResponse send(Delivery delivery) {
        if (!sends.tryAcquire()) {
            cancel(delivery);
            throw unavailable();
        }
        try {
            try {
                String code = tokens.decrypt(delivery.encryptedCode(), delivery.keyVersion(), delivery.encryptionContext());
                mailer.send(delivery.email(), code, delivery.purpose());
            } catch (RuntimeException ex) {
                cancel(delivery);
                throw ex;
            }
            // SMTP 수락 후 저장이 실패하면 예약을 보존해 같은 키로 복구한다.
            transaction.sent(delivery);
            return transaction.complete(delivery);
        } finally { sends.release(); }
    }

    private void cancel(Delivery delivery) {
        try { transaction.cancel(delivery); }
        catch (RuntimeException ex) {
            // 원문 예외·메일·인증번호는 기록하지 않는다. 예약은 lease 만료 뒤 복구할 수 있다.
            log.warn("Email delivery cancellation unavailable type={}", ex.getClass().getSimpleName());
        }
    }
    private static BusinessException unavailable() { return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE); }
}
