package com.dadot.server.domain.interview;

import com.dadot.server.domain.interview.model.InterviewGeneration.Preparation;
import com.dadot.server.domain.interview.repository.InterviewReadRepository;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "app.dart.worker-enabled=false",
        "app.dart.originals.enabled=false",
        "app.quiz-generation.worker-enabled=false",
        "app.interview-generation.worker-enabled=false",
        "app.interview-evaluation.worker-enabled=false",
        "spring.datasource.hikari.maximum-pool-size=5"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InterviewPreparationLockIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired InterviewReadRepository reads;
    @Autowired PlatformTransactionManager manager;

    private long firstUser;
    private long secondUser;
    private long firstCompany;
    private long secondCompany;
    private long category;
    private long job;
    private boolean safe;

    @BeforeEach
    void setup() {
        safe = false;
        assertEquals("dadot_test_db", jdbc.queryForObject("select database()", String.class));
        safe = true;
        firstUser = insert("insert into users(nickname) values ('잠금검사1')");
        secondUser = insert("insert into users(nickname) values ('잠금검사2')");
        firstCompany = company();
        secondCompany = company();
        category = insert("insert into job_categories(name) values (?)", "lock-" + UUID.randomUUID());
        job = insert("insert into jobs(category_id, name) values (?, '개발')", category);
        prepare(firstUser, firstCompany);
        prepare(secondUser, firstCompany);
        prepare(firstUser, secondCompany);
        prepare(secondUser, secondCompany);
    }

    @AfterEach
    void cleanup() {
        if (!safe) return;
        jdbc.update("delete from user_companies where user_id in (?, ?)", firstUser, secondUser);
        jdbc.update("delete from users where id in (?, ?)", firstUser, secondUser);
        jdbc.update("delete from companies where id in (?, ?)", firstCompany, secondCompany);
        jdbc.update("delete from jobs where id = ?", job);
        jdbc.update("delete from job_categories where id = ?", category);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void differentUsersOfTheSameCompanyCanHoldPreparationLocksTogether(boolean dictionaryJob) throws Exception {
        if (dictionaryJob) useDictionaryJob();
        whileHeld(() -> reads.preparation(firstUser, firstCompany, true), release -> {
            Preparation other = write(() -> reads.preparation(secondUser, firstCompany, true));
            assertEquals(secondUser, other.userId());
            assertEquals(firstCompany, other.companyId());
            assertEquals(dictionaryJob ? job : null, other.jobId());
            assertEquals(dictionaryJob ? "개발" : "직접입력", other.jobName());
            assertTrue(other.active());
        });
    }

    @Test
    void differentCompaniesCanReadTheSameDictionaryJobTogether() throws Exception {
        useDictionaryJob();
        whileHeld(() -> reads.preparation(firstUser, firstCompany, true), release ->
                assertEquals(job, write(() -> reads.preparation(secondUser, secondCompany, true)).jobId()));
    }

    @Test
    void theSameUserStillSerializesAcrossDifferentCompanies() throws Exception {
        whileHeld(() -> reads.preparation(firstUser, firstCompany, true), release ->
                assertLockTimeout(() -> write(() -> reads.preparation(firstUser, secondCompany, true))));
    }

    @Test
    void aPreparationLockStillProtectsTheUsersPersonalizationVersion() throws Exception {
        whileHeld(() -> reads.preparation(firstUser, firstCompany, true), release ->
                assertLockTimeout(() -> write(() -> jdbc.update(
                        "update users set personalization_version = personalization_version + 1 where id = ?", firstUser))));
    }

    @Test
    void aPreparationLockStillProtectsTheUsersCompanySelectionAndJob() throws Exception {
        whileHeld(() -> reads.preparation(firstUser, firstCompany, true), release ->
                assertLockTimeout(() -> write(() -> jdbc.update("""
                        update user_companies set custom_job_name = '변경', version = version + 1
                        where user_id = ? and company_id = ?
                        """, firstUser, firstCompany))));
    }

    @Test
    void companyStatusChangesStillWaitForReadersToFinish() throws Exception {
        whileHeld(() -> reads.preparation(firstUser, firstCompany, true), release -> {
            assertLockTimeout(() -> write(() -> jdbc.update(
                    "update companies set is_service_company = false where id = ?", firstCompany)));
            release.run();
        });
        assertEquals(1, write(() -> jdbc.update(
                "update companies set is_service_company = false where id = ?", firstCompany)));
        assertFalse(write(() -> reads.preparation(firstUser, firstCompany, true)).active());
    }

    @Test
    void dictionaryJobChangesStillWaitForReadersToFinish() throws Exception {
        useDictionaryJob();
        whileHeld(() -> reads.preparation(firstUser, firstCompany, true), release ->
                assertLockTimeout(() -> write(() -> jdbc.update("update jobs set name = '변경' where id = ?", job))));
        assertEquals(1, write(() -> jdbc.update("update jobs set name = '변경' where id = ?", job)));
        assertEquals("변경", write(() -> reads.preparation(firstUser, firstCompany, true)).jobName());
    }

    @Test
    void ordinaryReadsDoNotAcquireTheNewWriteLocks() throws Exception {
        useDictionaryJob();
        whileHeld(() -> reads.preparation(firstUser, firstCompany, false), release -> {
            assertEquals(1, write(() -> jdbc.update("update companies set name = '변경' where id = ?", firstCompany)));
            assertEquals(1, write(() -> jdbc.update("update jobs set name = '변경' where id = ?", job)));
            assertEquals(1, write(() -> jdbc.update("update users set nickname = '변경' where id = ?", firstUser)));
        });
    }

    @Test
    void readersWaitForACompanyWriterAndThenSeeItsCommittedStatus() throws Exception {
        whileHeld(() -> jdbc.update("update companies set is_service_company = false where id = ?", firstCompany), release -> {
            try (var executor = Executors.newSingleThreadExecutor()) {
                var started = new CountDownLatch(1);
                var reader = executor.submit(() -> write(() -> {
                    started.countDown();
                    return reads.preparation(firstUser, firstCompany, true);
                }));
                try {
                    assertTrue(started.await(5, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> reader.get(100, TimeUnit.MILLISECONDS));
                } finally {
                    release.run();
                }
                assertFalse(reader.get(5, TimeUnit.SECONDS).active());
            }
        });
    }

    @Test
    void lockingReadsUseCommittedValuesAfterAnEarlierReadInAWriteTransaction() throws Exception {
        transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
            assertEquals("직접입력", reads.preparation(firstUser, firstCompany, false).jobName());
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> write(() -> {
                    jdbc.update("update companies set name = '새 기업' where id = ?", firstCompany);
                    jdbc.update("update user_companies set custom_job_name = '새 직무' where user_id = ? and company_id = ?",
                            firstUser, firstCompany);
                    return jdbc.update("update users set personalization_version = 9 where id = ?", firstUser);
                })).get(5, TimeUnit.SECONDS);
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
            Preparation current = reads.preparation(firstUser, firstCompany, true);
            assertEquals("새 기업", current.companyName());
            assertEquals("새 직무", current.jobName());
            assertEquals(9, current.personalizationVersion());
        });
    }

    @Test
    void rollbackReleasesUserPreparationAndCommonDataLocks() {
        transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).executeWithoutResult(status -> {
            reads.preparation(firstUser, firstCompany, true);
            status.setRollbackOnly();
        });
        assertEquals(1, write(() -> jdbc.update("update companies set name = '변경' where id = ?", firstCompany)));
        assertEquals(firstUser, write(() -> reads.preparation(firstUser, firstCompany, true)).userId());
    }

    @Test
    void missingPreparationRetainsTheNotFoundResponse() {
        jdbc.update("delete from user_companies where user_id = ? and company_id = ?", firstUser, firstCompany);
        var error = assertThrows(BusinessException.class, () -> write(() -> reads.preparation(firstUser, firstCompany, true)));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error.getErrorCode());
    }

    @Test
    void missingUserRetainsTheNotFoundResponse() {
        jdbc.update("delete from user_companies where user_id = ?", firstUser);
        jdbc.update("delete from users where id = ?", firstUser);
        var error = assertThrows(BusinessException.class, () -> write(() -> reads.preparation(firstUser, firstCompany, true)));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error.getErrorCode());
    }

    @Test
    void excludedPreparationsRemainReadableAndInactive() {
        jdbc.update("""
                update user_companies set active_slot = null, excluded_at = current_timestamp(6)
                where user_id = ? and company_id = ?
                """, firstUser, firstCompany);
        Preparation preparation = write(() -> reads.preparation(firstUser, firstCompany, true));
        assertFalse(preparation.active());
        assertEquals("직접입력", preparation.jobName());
    }

    private void prepare(long user, long company) {
        jdbc.update("""
                insert into user_companies(user_id, company_id, active_slot, custom_job_name)
                values (?, ?, ?, '직접입력')
                """, user, company, company == firstCompany ? 1 : 2);
    }

    private void useDictionaryJob() {
        jdbc.update("update user_companies set job_id = ?, custom_job_name = null where user_id in (?, ?)",
                job, firstUser, secondUser);
    }

    private long company() {
        return insert("insert into companies(dart_corp_code, name, is_service_company) values (?, '기업', true)",
                String.format("%08d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000_000)));
    }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private <T> T write(Supplier<T> action) {
        return transaction(TransactionDefinition.ISOLATION_READ_COMMITTED).execute(status -> {
            int previous = Objects.requireNonNull(jdbc.queryForObject("select @@session.innodb_lock_wait_timeout", Integer.class));
            jdbc.execute("set session innodb_lock_wait_timeout = 1");
            try {
                return action.get();
            } finally {
                jdbc.execute("set session innodb_lock_wait_timeout = " + previous);
            }
        });
    }

    private TransactionTemplate transaction(int isolation) {
        var transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(isolation);
        transaction.setTimeout(10);
        return transaction;
    }

    private void whileHeld(Runnable acquire, WhileHeld action) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var owner = executor.submit(() -> write(() -> {
                acquire.run();
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("lock holder timed out");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
                return null;
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                action.run(release::countDown);
            } finally {
                release.countDown();
                owner.get(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void assertLockTimeout(Runnable operation) {
        var failure = assertThrows(DataAccessException.class, operation::run);
        Throwable cause = failure;
        while (!(cause instanceof SQLException) && cause.getCause() != null) cause = cause.getCause();
        assertInstanceOf(SQLException.class, cause);
        assertEquals(1205, ((SQLException) cause).getErrorCode(), "의도한 잠금 대기 시간 초과인지 확인");
    }

    @FunctionalInterface
    private interface WhileHeld {
        void run(Runnable release) throws Exception;
    }
}
