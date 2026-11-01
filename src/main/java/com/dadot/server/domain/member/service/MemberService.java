package com.dadot.server.domain.member.service;

import com.dadot.server.domain.member.model.Member.*;
import com.dadot.server.domain.member.repository.MemberRepository;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.SessionAuthenticationToken;
import com.dadot.server.global.security.UserAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Clock;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class MemberService {
    private static final Logger log =
            LoggerFactory.getLogger(MemberService.class);

    private final MemberRepository repository;
    private final UserAccess access;
    private final Clock clock;
    private final TransactionTemplate read;
    private final TransactionTemplate write;

    public MemberService(
            MemberRepository repository,
            UserAccess access,
            Clock clock,
            PlatformTransactionManager manager
    ) {
        this.repository = repository;
        this.access = access;
        this.clock = clock;
        read = transaction(manager, true);
        write = transaction(manager, false);
    }

    public Summary summary(SessionAuthenticationToken actor) {
        return storage("summary", () ->
                Objects.requireNonNull(read.execute(tx -> {
                    long user = access.require(actor, false);
                    return repository.summary(user, actor.getSessionId());
                }))
        );
    }

    public Profile nickname(
            SessionAuthenticationToken actor,
            Nickname body
    ) {
        Objects.requireNonNull(body);

        return storage("nickname", () ->
                Objects.requireNonNull(write.execute(tx -> {
                    long user = access.require(actor, true);

                    Profile result = repository.nickname(
                            user,
                            body.nickname(),
                            clock.instant()
                    );

                    access.require(actor, true);
                    return result;
                }))
        );
    }

    static TransactionTemplate transaction(
            PlatformTransactionManager manager,
            boolean readOnly
    ) {
        var tx = new TransactionTemplate(manager);
        tx.setReadOnly(readOnly);
        tx.setTimeout(15);
        tx.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
        tx.setIsolationLevel(
                readOnly
                        ? TransactionDefinition.ISOLATION_REPEATABLE_READ
                        : TransactionDefinition.ISOLATION_READ_COMMITTED
        );
        return tx;
    }

    // SQL·비밀번호·토큰이 포함될 수 있는 예외 메시지와
    // 원인 객체는 로깅하지 않는다.
    @SuppressWarnings("PMD.PreserveStackTrace")
    static <T> T storage(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (
                TransientDataAccessException
                | DataAccessResourceFailureException
                | TransactionException ex
        ) {
            logStorage(operation, ex);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        }
    }

    static void logStorage(String operation, RuntimeException ex) {
        Throwable root = ex;
        SQLException sql = null;

        var visited = Collections.newSetFromMap(
                new IdentityHashMap<Throwable, Boolean>()
        );

        for (
                Throwable cause = ex;
                cause != null && visited.add(cause);
                cause = cause.getCause()
        ) {
            root = cause;

            if (sql == null && cause instanceof SQLException value) {
                sql = value;
            }
        }

        String state = sql == null ? null : sql.getSQLState();

        if (state != null && !state.matches("[0-9A-Z]{5}")) {
            state = null;
        }

        log.warn(
                "Member storage unavailable: operation={}, "
                        + "exceptionClass={}, rootCauseClass={}, "
                        + "sqlState={}, dbErrorCode={}",
                operation,
                ex.getClass().getName(),
                root.getClass().getName(),
                state,
                sql == null ? null : sql.getErrorCode()
        );
    }
}