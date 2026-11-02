package com.dadot.server.domain.interview;

import com.dadot.server.domain.interview.model.InterviewGeneration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "app.dart.enabled=false", "app.dart.worker-enabled=false",
        "app.dart.originals.enabled=false", "app.account-deletion.worker-enabled=false",
        "app.quiz-generation.enabled=false", "app.quiz-generation.worker-enabled=false",
        "app.interview-generation.enabled=false", "app.interview-generation.worker-enabled=false",
        "app.interview-evaluation.enabled=false", "app.interview-evaluation.worker-enabled=false"
})
@ActiveProfiles("test")
@Transactional
class InterviewTaskForeignKeyIntegrationTest {

    private static final String CONSTRAINT =
            "fk_task_attempt_context_policy";

    @Autowired
    JdbcTemplate jdbc;

    private long user;
    private long otherUser;
    private long company;
    private long policy;
    private long alternatePolicy;
    private long generationPolicy;
    private long attempt;

    private String context;
    private String alternateContext;
    private String otherContext;

    @BeforeEach
    void fixture() {
        assertEquals(
                "dadot_test_db",
                jdbc.queryForObject(
                        "select database()",
                        String.class
                )
        );

        user = insert(
                "insert into users(nickname) values ('FK검증사용자')"
        );

        otherUser = insert(
                "insert into users(nickname) values ('다른FK사용자')"
        );

        company = insert("""
                insert into companies(
                    dart_corp_code,
                    name,
                    is_service_company
                )
                values ('98989898', 'FK검증기업', true)
                """
        );

        for (long owner : List.of(user, otherUser)) {
            jdbc.update("""
                    insert into user_companies(
                        user_id,
                        company_id,
                        active_slot,
                        custom_job_name
                    )
                    values (?, ?, 1, '개발')
                    """,
                    owner,
                    company
            );
        }

        context = context(user);
        alternateContext = context(user);
        otherContext = context(otherUser);

        long root = insert("""
                insert into interview_policies(
                    policy_key,
                    type,
                    company_id
                )
                values (?, 'EVALUATION', ?)
                """,
                "EVALUATION:" + company + ":0",
                company
        );

        policy = policy(root, 1);
        alternatePolicy = policy(root, 2);

        long generation = insert("""
                insert into interview_policies(
                    policy_key,
                    type,
                    company_id
                )
                values (?, 'GENERATION', ?)
                """,
                "GENERATION:" + company + ":0",
                company
        );

        generationPolicy = policy(generation, 1);

        long question = insert("""
                insert into interview_question_keys(
                    user_id,
                    company_id,
                    question_fingerprint
                )
                values (?, ?, ?)
                """,
                user,
                company,
                InterviewGeneration.sha256(key())
        );

        jdbc.update("""
                insert into interview_daily_questions(
                    user_id,
                    company_id,
                    business_date,
                    question_id,
                    question_slot
                )
                values (?, ?, '2026-11-02', ?, 1)
                """,
                user,
                company,
                question
        );

        attempt = insert("""
                insert into interview_submissions(
                    user_id,
                    company_id,
                    question_id,
                    business_date,
                    daily_slot,
                    attempt_number,
                    request_key_hash,
                    request_body_hmac
                )
                values (?, ?, ?, '2026-11-02', 1, 1, ?, ?)
                """,
                user,
                company,
                question,
                InterviewGeneration.sha256(key()),
                InterviewGeneration.sha256(key())
        );

        jdbc.update("""
                insert into interview_attempts(
                    id,
                    user_id,
                    company_id,
                    context_id,
                    evaluation_policy_version_id,
                    answer_text
                )
                values (?, ?, ?, ?, ?, '실제 답변')
                """,
                attempt,
                user,
                company,
                context,
                policy
        );
    }

    @Test
    void matchingAttemptOwnerContextAndPolicyCanBeStored() {
        String task = evaluation(
                user,
                context,
                policy,
                attempt
        );

        assertEquals(
                1,
                count("""
                        select count(*)
                        from ai_tasks
                        where id = ?
                          and source_attempt_id = ?
                        """,
                        task,
                        attempt
                )
        );
    }

    @Test
    void nonexistentAttemptCannotCreateOrphanTask() {
        rejects(
                1452,
                () -> evaluation(
                        user,
                        context,
                        policy,
                        9_007_199_254_740_000L
                )
        );

        assertEquals(
                0,
                count(
                        "select count(*) from ai_tasks where company_id = ?",
                        company
                )
        );
    }

    @Test
    void differentContextOfSameOwnerIsRejected() {
        rejects(
                1452,
                () -> evaluation(
                        user,
                        alternateContext,
                        policy,
                        attempt
                )
        );
    }

    @Test
    void differentOwnerWithTheirValidContextIsRejected() {
        rejects(
                1452,
                () -> evaluation(
                        otherUser,
                        otherContext,
                        policy,
                        attempt
                )
        );
    }

    @Test
    void differentExistingPolicyVersionIsRejected() {
        rejects(
                1452,
                () -> evaluation(
                        user,
                        context,
                        alternatePolicy,
                        attempt
                )
        );
    }

    @Test
    void referencedAnswerCannotBeDeletedBeforeItsTask() {
        String task = evaluation(
                user,
                context,
                policy,
                attempt
        );

        rejects(
                1451,
                () -> jdbc.update(
                        "delete from interview_attempts where id = ?",
                        attempt
                )
        );

        assertEquals(
                1,
                count(
                        "select count(*) from interview_attempts where id = ?",
                        attempt
                )
        );

        assertEquals(
                1,
                jdbc.update(
                        "delete from ai_tasks where id = ?",
                        task
                )
        );

        assertEquals(
                1,
                jdbc.update(
                        "delete from interview_attempts where id = ?",
                        attempt
                )
        );

        assertEquals(
                1,
                count(
                        "select count(*) from interview_submissions where id = ?",
                        attempt
                )
        );
    }

    @Test
    void referencedAnswerContextCannotBeChangedIndependently() {
        evaluation(
                user,
                context,
                policy,
                attempt
        );

        rejects(
                1451,
                () -> jdbc.update("""
                        update interview_attempts
                        set context_id = ?
                        where id = ?
                        """,
                        alternateContext,
                        attempt
                )
        );
    }

    @Test
    void existingTaskContextCannotBeChangedIndependently() {
        String task = evaluation(
                user,
                context,
                policy,
                attempt
        );

        rejects(
                1452,
                () -> jdbc.update(
                        "update ai_tasks set context_id = ? where id = ?",
                        alternateContext,
                        task
                )
        );
    }

    @Test
    void existingTaskCannotBeReboundToMissingAttempt() {
        String task = evaluation(
                user,
                context,
                policy,
                attempt
        );

        rejects(
                1452,
                () -> jdbc.update("""
                        update ai_tasks
                        set source_attempt_id = ?
                        where id = ?
                        """,
                        9_007_199_254_740_000L,
                        task
                )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INTERVIEW_GENERATION",
            "DISCLOSURE_SYNC",
            "QUIZ_GENERATION"
    })
    void otherTaskTypesKeepNullableAnswerReference(String type) {
        String task = key();
        boolean generation = type.equals("INTERVIEW_GENERATION");

        jdbc.update("""
                insert into ai_tasks(
                    id,
                    user_id,
                    company_id,
                    type,
                    context_id,
                    policy_version_id,
                    source_attempt_id,
                    request_key_hash,
                    request_body_hmac
                )
                values (?, ?, ?, ?, ?, ?, null, ?, ?)
                """,
                task,
                user,
                company,
                type,
                generation ? context : null,
                generation ? generationPolicy : null,
                InterviewGeneration.sha256(key()),
                InterviewGeneration.sha256(key())
        );

        assertEquals(
                1,
                count("""
                        select count(*)
                        from ai_tasks
                        where id = ?
                          and source_attempt_id is null
                        """,
                        task
                )
        );
    }

    @Test
    void migrationCreatesExpectedOrderedColumnsAndRestrictRules() {
        assertEquals(
                List.of(
                        "source_attempt_id",
                        "context_id",
                        "user_id",
                        "policy_version_id"
                ),
                jdbc.query("""
                        select column_name
                        from information_schema.key_column_usage
                        where constraint_schema = database()
                          and table_name = 'ai_tasks'
                          and constraint_name = ?
                        order by ordinal_position
                        """,
                        (rs, row) -> rs.getString(1),
                        CONSTRAINT
                )
        );

        assertEquals(
                List.of(
                        "id",
                        "context_id",
                        "user_id",
                        "evaluation_policy_version_id"
                ),
                jdbc.query("""
                        select referenced_column_name
                        from information_schema.key_column_usage
                        where constraint_schema = database()
                          and table_name = 'ai_tasks'
                          and constraint_name = ?
                        order by ordinal_position
                        """,
                        (rs, row) -> rs.getString(1),
                        CONSTRAINT
                )
        );

        assertEquals(
                List.of("RESTRICT:RESTRICT"),
                jdbc.query("""
                        select concat(delete_rule, ':', update_rule)
                        from information_schema.referential_constraints
                        where constraint_schema = database()
                          and table_name = 'ai_tasks'
                          and constraint_name = ?
                        """,
                        (rs, row) -> rs.getString(1),
                        CONSTRAINT
                )
        );
    }

    private String evaluation(
            long owner,
            String snapshot,
            long version,
            long answer
    ) {
        String task = key();

        jdbc.update("""
                insert into ai_tasks(
                    id,
                    user_id,
                    company_id,
                    type,
                    context_id,
                    policy_version_id,
                    source_attempt_id,
                    request_key_hash,
                    request_body_hmac
                )
                values (?, ?, ?, 'INTERVIEW_EVALUATION', ?, ?, ?, ?, ?)
                """,
                task,
                owner,
                company,
                snapshot,
                version,
                answer,
                InterviewGeneration.sha256(key()),
                InterviewGeneration.sha256(key())
        );

        return task;
    }

    private void rejects(
            int expectedCode,
            Executable action
    ) {
        DataIntegrityViolationException error = assertThrows(
                DataIntegrityViolationException.class,
                action
        );

        SQLException sql = assertInstanceOf(
                SQLException.class,
                error.getMostSpecificCause()
        );

        assertEquals(
                expectedCode,
                sql.getErrorCode()
        );

        assertTrue(
                sql.getMessage().contains(CONSTRAINT),
                "Expected rejection by the new composite FK"
        );
    }

    private String context(long owner) {
        String value = key();

        jdbc.update("""
                insert into ai_contexts(
                    id,
                    user_id,
                    company_id,
                    job_is_custom,
                    job_name_snapshot,
                    personalization_version
                )
                values (?, ?, ?, true, '개발', 0)
                """,
                value,
                owner,
                company
        );

        return value;
    }

    private long policy(
            long parent,
            int version
    ) {
        return insert("""
                insert into interview_policy_versions(
                    policy_id,
                    version_no,
                    status,
                    prompt_template,
                    rule_config
                )
                values (?, ?, 'DRAFT', 'FK 검증', '{}')
                """,
                parent,
                version
        );
    }

    private long count(
            String sql,
            Object... args
    ) {
        return Objects.requireNonNull(
                jdbc.queryForObject(
                        sql,
                        Long.class,
                        args
                )
        );
    }

    private long insert(
            String sql,
            Object... args
    ) {
        var key = new GeneratedKeyHolder();

        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    sql,
                    Statement.RETURN_GENERATED_KEYS
            );

            for (int i = 0; i < args.length; i++) {
                statement.setObject(
                        i + 1,
                        args[i]
                );
            }

            return statement;
        }, key);

        return Objects.requireNonNull(
                key.getKey()
        ).longValue();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}