package com.dadot.server.domain.member.service;

import com.dadot.server.domain.member.model.Member.Provider;
import com.dadot.server.domain.member.repository.AccountDeletionRepository;
import com.dadot.server.domain.member.repository.AccountDeletionRepository.Lease;
import com.dadot.server.domain.member.repository.MemberRepository;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.infrastructure.apple.AppleClient;
import com.dadot.server.infrastructure.kakao.KakaoAccountClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

@Component
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AccountDeletionWorker {
    private static final Logger log =
            LoggerFactory.getLogger(AccountDeletionWorker.class);

    private final AccountDeletionRepository repository;
    private final MemberRepository members;
    private final AppleClient apple;
    private final KakaoAccountClient kakao;
    private final Clock clock;
    private final boolean workerEnabled;
    private final TransactionTemplate write;

    public AccountDeletionWorker(
            AccountDeletionRepository repository,
            MemberRepository members,
            AppleClient apple,
            KakaoAccountClient kakao,
            Clock clock,
            @Value("${app.account-deletion.worker-enabled:true}")
            boolean workerEnabled,
            PlatformTransactionManager manager
    ) {
        this.repository = repository;
        this.members = members;
        this.apple = apple;
        this.kakao = kakao;
        this.clock = clock;
        this.workerEnabled = workerEnabled;
        write = MemberService.transaction(manager, false);
    }

    @Scheduled(
            initialDelayString = "${app.account-deletion.delay-ms:30000}",
            fixedDelayString = "${app.account-deletion.delay-ms:30000}"
    )
    public void tick() {
        if (!workerEnabled) {
            return;
        }

        try {
            for (String id : repository.ready(clock.instant())) {
                process(id);
            }

            repository.clean(clock.instant());
        } catch (DataAccessException | TransactionException ex) {
            MemberService.logStorage("deleteMaintenance", ex);
        }
    }

    public void process(String id) {
        Lease lease = null;

        try {
            Optional<Lease> claimed = write.execute(tx -> {
                var user = repository.user(id);

                if (user.isEmpty()) {
                    return Optional.empty();
                }

                repository.lockDeletingUser(user.get());

                return repository.claim(
                        id,
                        UUID.randomUUID().toString(),
                        clock.instant()
                );
            });

            if (claimed == null || claimed.isEmpty()) {
                return;
            }

            lease = claimed.get();

            var credentials = repository.credentials(lease);

            if (!lease.revoked()) {
                if (lease.provider() == Provider.APPLE) {
                    apple.revoke(credentials.revocationToken());
                } else if (lease.provider() == Provider.KAKAO) {
                    kakao.unlink(credentials.subject());
                }

                Lease current = lease;

                write.executeWithoutResult(tx -> {
                    repository.lockDeletingUser(current.user());
                    repository.revoked(current, clock.instant());
                });
            }

            Lease current = lease;

            write.executeWithoutResult(tx -> {
                var identity = members.identity(current.user(), false);

                repository.lockSources(
                        current.user(),
                        identity,
                        clock.instant()
                );

                repository.lockDeletingUser(current.user());

                if (!repository.owned(current, clock.instant())) {
                    return;
                }

                if (!identity.subject().equals(credentials.subject())
                        || identity.provider() != current.provider()) {
                    throw new IllegalStateException(
                            "Deletion identity changed"
                    );
                }

                repository.purge(
                        current,
                        identity,
                        clock.instant()
                );
            });
        } catch (DataAccessException | TransactionException ex) {
            MemberService.logStorage("deleteWorker", ex);
            retry(lease, "STORAGE_UNAVAILABLE");
        } catch (BusinessException ex) {
            log.warn(
                    "Member deletion pending: reason=PROVIDER_UNAVAILABLE"
            );
            retry(lease, "PROVIDER_UNAVAILABLE");
        } catch (RuntimeException ex) {
            log.error(
                    "Member deletion pending: reason=INTERNAL_FAILURE, "
                            + "exceptionClass={}",
                    ex.getClass().getName()
            );
            retry(lease, "INTERNAL_FAILURE");
        }
    }

    private void retry(Lease lease, String reason) {
        if (lease == null) {
            return;
        }

        try {
            write.executeWithoutResult(tx ->
                    repository.retry(lease, clock.instant(), reason)
            );
        } catch (DataAccessException | TransactionException ex) {
            MemberService.logStorage("deleteRetry", ex);
        }
    }
}