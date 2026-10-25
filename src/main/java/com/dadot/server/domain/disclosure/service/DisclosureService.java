package com.dadot.server.domain.disclosure.service;

import com.dadot.server.domain.auth.repository.LogoutRepository;
import com.dadot.server.domain.auth.support.VerificationCrypto;
import com.dadot.server.domain.company.support.CompanyLogoUrlResolver;
import com.dadot.server.domain.disclosure.model.DisclosureSync;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Accepted;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Request;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Result;
import com.dadot.server.domain.disclosure.model.DisclosureSync.Search;
import com.dadot.server.domain.disclosure.model.DisclosureSync.TaskResult;
import com.dadot.server.domain.disclosure.repository.DisclosureRepository;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.SessionAuthenticationToken;
import com.dadot.server.infrastructure.dart.DartClient;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class DisclosureService {
    private final DisclosureRepository repository;
    private final JdbcTemplate jdbc;
    private final LogoutRepository sessions;
    private final VerificationCrypto crypto;
    private final CompanyLogoUrlResolver logos;
    private final DartClient dart;
    private final Clock clock;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public DisclosureService(
            DisclosureRepository repository,
            JdbcTemplate jdbc,
            LogoutRepository sessions,
            VerificationCrypto crypto,
            CompanyLogoUrlResolver logos,
            DartClient dart,
            Clock clock,
            PlatformTransactionManager manager
    ) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.crypto = crypto;
        this.logos = logos;
        this.dart = dart;
        this.clock = clock;

        write = new TransactionTemplate(manager);
        write.setTimeout(10);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        read = new TransactionTemplate(manager);
        read.setReadOnly(true);
        read.setTimeout(10);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Accepted start(
            SessionAuthenticationToken actor,
            long companyId,
            String key,
            Request request
    ) {
        if (companyId < 1 || companyId > 9_007_199_254_740_991L || request == null)
            throw DisclosureSync.invalid();

        DisclosureSync.uuid(key);

        String operation =
                "POST /v1/admin/companies/" + companyId + "/disclosure-sync-tasks";
        String scope = crypto.hmac("disclosure-admin-scope-v1", actor.getName());
        String keyHash = crypto.hmac("disclosure-key-v1", operation, key);
        String bodyHash = crypto.hmac(
                "disclosure-body-v1", operation, repository.json(request)
        );

        return unavailable(() -> Objects.requireNonNull(write.execute(status -> {
            requireAdmin(actor, true);

            var replay = repository.replay(
                    scope, operation, keyHash, bodyHash, clock.instant()
            );
            if (replay != null) return replay;

            if (request.toDate().isAfter(
                    LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")))))
                throw DisclosureSync.invalid();

            repository.companyCode(companyId);

            if (!dart.ready())
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);

            var result = repository.create(
                    actor.getPrincipal().userId(), companyId,
                    scope, operation, keyHash, bodyHash, request, clock.instant()
            );

            requireAdmin(actor, true);
            return result;
        })));
    }

    public Result search(SessionAuthenticationToken actor, Search request) {
        return unavailable(() -> Objects.requireNonNull(read.execute(status -> {
            requireAdmin(actor, false);

            TaskResult task = request.syncTaskId() == null
                    ? null
                    : repository.task(request.syncTaskId(), request.companyId());

            long total = repository.countVersions(request);

            return new Result(
                    repository.versions(request, logos::resolve),
                    request.page(), request.size(), total,
                    (total + request.size() - 1) / request.size(),
                    ((long) request.page() + 1) * request.size() < total,
                    task
            );
        })));
    }

    private void requireAdmin(SessionAuthenticationToken actor, boolean lock) {
        var accounts = jdbc.query(
                "select role, status, auth_version from users where id = ?"
                        + (lock ? " for update" : ""),
                (rs, n) -> new Object[]{
                        rs.getString(1), rs.getString(2), rs.getInt(3)
                },
                actor.getPrincipal().userId()
        );

        if (accounts.isEmpty()
                || !"ACTIVE".equals(accounts.getFirst()[1])
                || (int) accounts.getFirst()[2] != actor.getAuthVersion())
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);

        if (!"ADMIN".equals(accounts.getFirst()[0]))
            throw new BusinessException(ErrorCode.FORBIDDEN);

        if (lock) {
            var session = sessions.lockSession(
                    actor.getPrincipal().userId(), actor.getSessionId()
            ).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

            if (!session.usable(clock.instant()))
                throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        } else {
            var now = DisclosureRepository.utc(clock.instant());

            if (Objects.requireNonNull(jdbc.queryForObject("""
                    select count(*) from auth_sessions
                    where id = ? and user_id = ?
                      and revoked_at is null and created_at <= ? and expires_at > ?
                    """, Long.class, actor.getSessionId(),
                    actor.getPrincipal().userId(), now, now)) != 1)
                throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
    }

    private <T> T unavailable(Supplier<T> action) {
        try {
            return action.get();
        } catch (
                TransientDataAccessException
                | DataAccessResourceFailureException
                | CannotCreateTransactionException
                | TransactionTimedOutException ex
        ) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }
}