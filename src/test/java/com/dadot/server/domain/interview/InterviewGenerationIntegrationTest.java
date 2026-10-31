package com.dadot.server.domain.interview;

import com.dadot.server.domain.interview.model.InterviewGeneration;
import com.dadot.server.domain.interview.model.InterviewGeneration.*;
import com.dadot.server.domain.interview.repository.InterviewGenerationRepository;
import com.dadot.server.domain.interview.service.InterviewGenerationWorker;
import com.dadot.server.global.security.AccessTokenIssuer;
import com.dadot.server.infrastructure.openai.OpenAiInterviewClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.dart.worker-enabled=false",
        "app.dart.originals.enabled=false",
        "app.quiz-generation.enabled=false",
        "app.quiz-generation.worker-enabled=false",
        "app.interview-generation.enabled=false",
        "app.interview-generation.worker-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InterviewGenerationIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired Clock clock;
    @Autowired AccessTokenIssuer tokens;
    @Autowired InterviewGenerationWorker worker;
    @MockitoSpyBean InterviewGenerationRepository repository;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean OpenAiInterviewClient client;

    private final List<Long> users = new ArrayList<>();
    private long user;
    private long company;
    private long policy;
    private long policyVersion;
    private String token;
    private String session;
    private boolean safe;
    private final AtomicReference<Lease> captured = new AtomicReference<>();

    @BeforeEach
    void setup() {
        safe = false;

        assertEquals(
                "dadot_test_db",
                jdbc.queryForObject("select database()", String.class)
        );

        safe = true;
        reset(client);
        reset(repository);
        users.clear();
        captured.set(null);

        user = account();
        token = login(user);

        company = insert("""
                insert into companies(dart_corp_code, name, is_service_company)
                values ('99006000', '면접기업', true)
                """);

        jdbc.update("""
                insert into user_companies(
                    user_id, company_id, active_slot, custom_job_name
                )
                values (?, ?, 1, '해외영업')
                """, user, company);

        policy = insert("""
                insert into interview_policies(policy_key, type, company_id)
                values (?, 'GENERATION', ?)
                """, "GENERATION:" + company + ":0", company);

        policyVersion = insert("""
                insert into interview_policy_versions(
                    policy_id, version_no, status, prompt_template,
                    rule_config, published_at
                )
                values (
                    ?, 1, 'PUBLISHED', '직무 중심 질문',
                    '{"materialTypes":["EXPERIENCE"],"aggregationRule":null}',
                    current_timestamp(6)
                )
                """, policy);

        jdbc.update("""
                update interview_policies
                set active_version_no = 1 where id = ?
                """, policy);

        for (String type : List.of("ANNUAL", "HALF_YEAR", "QUARTERLY")) {
            source(type);
        }

        doReturn(true).when(client).ready();

        doAnswer(invocation -> {
            Lease lease = invocation.getArgument(0);
            captured.set(lease);
            return output(lease);
        }).when(client).generate(any());

        gate();
    }

    @AfterEach
    void cleanup() {
        if (!safe) return;

        reset(client);
        reset(repository);

        jdbc.update(
                "delete from interview_question_evidence where company_id = ?",
                company
        );
        jdbc.update(
                "delete from interview_drafts where company_id = ?", company
        );
        jdbc.update(
                "delete from interview_attempts where company_id = ?", company
        );
        jdbc.update(
                "delete from interview_submissions where company_id = ?", company
        );
        jdbc.update(
                "delete from interview_daily_questions where company_id = ?", company
        );
        jdbc.update(
                "delete from interview_questions where company_id = ?", company
        );
        jdbc.update(
                "delete from interview_question_keys where company_id = ?", company
        );

        jdbc.update("""
                delete r from ai_task_runs r
                join ai_tasks t on t.id = r.task_id
                where t.company_id = ?
                """, company);

        for (long id : users) {
            jdbc.update(
                    "delete from api_idempotency_requests where user_id = ?", id
            );
        }

        jdbc.update(
                "delete from interview_generation_jobs where company_id = ?",
                company
        );
        jdbc.update("delete from ai_tasks where company_id = ?", company);

        jdbc.update("""
                delete m from ai_context_materials m
                join ai_contexts x on x.id = m.context_id
                where x.company_id = ?
                """, company);

        jdbc.update("delete from ai_contexts where company_id = ?", company);

        jdbc.update("""
                update interview_policies
                set active_version_no = null where id = ?
                """, policy);

        jdbc.update(
                "delete from interview_policy_versions where policy_id = ?",
                policy
        );
        jdbc.update("delete from interview_policies where id = ?", policy);

        jdbc.update(
                "delete from disclosure_sections where company_id = ?", company
        );
        jdbc.update(
                "delete from disclosure_versions where company_id = ?", company
        );
        jdbc.update("""
                update disclosures set corrects_disclosure_id = null
                where company_id = ?
                """, company);
        jdbc.update("delete from disclosures where company_id = ?", company);

        for (long id : users) {
            jdbc.update("""
                    update personal_materials set current_version_no = null
                    where user_id = ?
                    """, id);
            jdbc.update(
                    "delete from material_versions where user_id = ?", id
            );
            jdbc.update(
                    "delete from personal_materials where user_id = ?", id
            );
            jdbc.update(
                    "delete from user_companies where user_id = ?", id
            );
            jdbc.update(
                    "delete from auth_sessions where user_id = ?", id
            );
            jdbc.update("delete from users where id = ?", id);
        }

        jdbc.update("delete from companies where id = ?", company);
    }

    @Test
    void generatesOneQuestionFromThreeReportsAndAllExperienceVersionsWithoutUsingQuota()
            throws Exception {
        material("EXPERIENCE", "경험1", "시장조사 경험");
        material("EXPERIENCE", "경험2", "판매 채널 분석");
        material("COVER_LETTER", null, "전달하면 안 되는 자소서");

        String task = accepted();

        entry(task)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nextAction").value("WAIT_GENERATION"));

        worker.runOnce();

        assertEquals(3, captured.get().input().sources().size());
        assertEquals(
                3,
                captured.get().input().sections().stream()
                        .map(Section::disclosureVersionId).distinct().count()
        );
        assertEquals(2, captured.get().input().experiences().size());
        assertFalse(
                mapper.writeValueAsString(captured.get().input())
                        .contains("전달하면 안 되는 자소서")
        );

        long question = question(task);

        detail(question, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usage.daily.used").value(0))
                .andExpect(jsonPath("$.data.usage.questionUsed").value(0))
                .andExpect(jsonPath("$.data.usage.canSubmit").value(true))
                .andExpect(jsonPath("$.data.evidence.length()").value(1));

        entry(task)
                .andExpect(jsonPath("$.data.nextAction").value("ANSWER_QUESTION"))
                .andExpect(
                        jsonPath("$.data.generationTask.status")
                                .value("SUCCEEDED")
                );

        error(create(key(), token), 409, "EXISTING_QUESTION_AVAILABLE");
    }

    @Test
    void zeroExperienceDoesNotFallBackToCoverLetter() throws Exception {
        material("COVER_LETTER", null, "읽지 않을 자소서");

        String task = accepted();
        worker.runOnce();

        assertTrue(captured.get().input().experiences().isEmpty());
        assertTrue(question(task) > 0);
    }

    @Test
    void missingReportAndInputOverflowFailBeforeTaskCreation() throws Exception {
        jdbc.update("""
                update disclosure_versions v
                join disclosures d on d.id = v.disclosure_id
                set v.status = 'SUSPENDED'
                where d.company_id = ? and d.report_type = 'QUARTERLY'
                """, company);

        error(create(key(), token), 409, "CONTENT_UNAVAILABLE");

        jdbc.update("""
                update disclosure_versions set status = 'PUBLISHED'
                where company_id = ?
                """, company);

        for (int i = 0; i < 9; i++) {
            material("EXPERIENCE", "경험" + i, "가".repeat(5000));
        }

        error(create(key(), token), 409, "INTERVIEW_INPUT_TOO_LARGE");

        assertEquals(
                0,
                count("select count(*) from ai_tasks where company_id = ?", company)
        );
        verify(client, never()).generate(any());
    }

    @Test
    void replayAndDifferentKeysReuseTaskEvenWhenFeatureIsDisabled()
            throws Exception {
        String key = key();
        String task = data(create(key, token)).get("taskId").asText();

        doReturn(false).when(client).ready();

        assertEquals(
                task,
                data(create(key, token).andExpect(status().isAccepted()))
                        .get("taskId").asText()
        );
        assertEquals(
                task,
                data(create(key(), token).andExpect(status().isAccepted()))
                        .get("taskId").asText()
        );
        assertEquals(
                1,
                count("select count(*) from ai_tasks where company_id = ?", company)
        );
    }

    @Test
    void expiredTombstoneNeverCreatesAnotherTask() throws Exception {
        String key = key();
        create(key, token).andExpect(status().isAccepted());

        jdbc.update("""
                update api_idempotency_requests
                set created_at = '2000-01-01', expires_at = '2000-01-02'
                where user_id = ?
                """, user);

        error(create(key, token), 409, "IDEMPOTENCY_KEY_EXPIRED");

        assertEquals(
                1,
                count("select count(*) from ai_tasks where company_id = ?", company)
        );
    }

    @Test
    void concurrentDifferentKeysCreateOnlyOneLogicalTask() throws Exception {
        var values = concurrent(
                () -> data(create(key(), token)
                        .andExpect(status().isAccepted()))
                        .get("taskId").asText(),
                () -> data(create(key(), token)
                        .andExpect(status().isAccepted()))
                        .get("taskId").asText()
        );

        assertEquals(values.get(0), values.get(1));
        assertEquals(
                1,
                count("select count(*) from ai_contexts where company_id = ?", company)
        );
        assertEquals(
                2,
                count(
                        "select count(*) from api_idempotency_requests where user_id = ?",
                        user
                )
        );
    }

    @Test
    void userCannotReadOtherUsersTaskOrQuestion() throws Exception {
        String other = login(account());
        String task = accepted();

        worker.runOnce();

        detail(question(task), other).andExpect(status().isNotFound());

        mvc.perform(
                get(entryPath())
                        .param("taskId", task)
                        .header("Authorization", "Bearer " + other)
        ).andExpect(status().isNotFound());

        mvc.perform(get(entryPath())).andExpect(status().isUnauthorized());
    }

    @Test
    void sourceSuspensionBeforeCallOrDuringCallNeverSavesQuestion()
            throws Exception {
        String first = accepted();

        jdbc.update("""
                update disclosure_versions set status = 'SUSPENDED'
                where company_id = ?
                """, company);

        worker.runOnce();

        assertEquals("FAILED", state(first));
        verify(client, never()).generate(any());

        jdbc.update("""
                update disclosure_versions set status = 'PUBLISHED'
                where company_id = ?
                """, company);

        gate();
        String second = accepted();

        doAnswer(invocation -> {
            Lease lease = invocation.getArgument(0);

            jdbc.update("""
                    update disclosure_versions set status = 'SUSPENDED'
                    where company_id = ?
                    """, company);

            return output(lease);
        }).when(client).generate(any());

        worker.runOnce();

        assertEquals("FAILED", state(second));
        assertEquals(
                0,
                count(
                        "select count(*) from interview_questions where company_id = ?",
                        company
                )
        );
    }

    @Test
    void disablingServiceCompanyDuringCallRejectsLateQuestion()
            throws Exception {
        String task = accepted();

        doAnswer(invocation -> {
            Lease lease = invocation.getArgument(0);

            jdbc.update(
                    "update companies set is_service_company = false where id = ?",
                    company
            );

            return output(lease);
        }).when(client).generate(any());

        worker.runOnce();

        assertEquals("FAILED", state(task));
        assertEquals(
                0,
                count(
                        "select count(*) from interview_questions where company_id = ?",
                        company
                )
        );

        error(create(key(), token), 403, "FORBIDDEN");
    }

    @Test
    void deletingExperienceDuringCallCancelsTaskAndRejectsLateResult()
            throws Exception {
        long material = material("EXPERIENCE", "경험", "해외 시장 조사");
        String task = accepted();

        doAnswer(invocation -> {
            Lease lease = invocation.getArgument(0);

            mvc.perform(
                    delete("/v1/users/me/experiences/" + material)
                            .header("Authorization", "Bearer " + token)
            ).andExpect(status().isNoContent());

            return output(lease);
        }).when(client).generate(any());

        worker.runOnce();

        assertEquals("CANCELLED", state(task));
        assertEquals(
                0,
                count(
                        "select count(*) from interview_questions where company_id = ?",
                        company
                )
        );
        assertEquals(
                0,
                count(
                        "select count(*) from material_versions where material_id = ?",
                        material
                )
        );
        assertEquals(
                0,
                count("""
                        select count(*) from interview_generation_jobs
                        where active_company_id = ?
                        """, company)
        );
    }

    @Test
    void deletingExperienceAfterSuccessPreservesQuestionAndEvidence()
            throws Exception {
        long material = material("EXPERIENCE", "경험", "시장조사");
        String task = accepted();

        worker.runOnce();
        long question = question(task);

        mvc.perform(
                delete("/v1/users/me/experiences/" + material)
                        .header("Authorization", "Bearer " + token)
        ).andExpect(status().isNoContent());

        detail(question, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence.length()").value(1));

        assertEquals("SUCCEEDED", state(task));
    }

    @Test
    void experienceEditPreservesAcceptedInputVersion() throws Exception {
        long material = material("EXPERIENCE", "경험", "원래 경험");
        String task = accepted();

        mvc.perform(
                patch("/v1/users/me/experiences/" + material)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(
                                "{\"version\":1,\"content\":\"수정 경험\"}"
                        )
        ).andExpect(status().isOk());

        worker.runOnce();

        assertEquals(
                "원래 경험",
                captured.get().input().experiences().getFirst().content()
        );
        assertEquals("SUCCEEDED", state(task));
    }

    @Test
    void invalidCitationAndDeletedQuestionFingerprintAreRejected()
            throws Exception {
        String first = accepted();

        doReturn(new Output(
                new Generated(
                        "질문",
                        List.of(new Quote(1, 1, "없는 인용"))
                ),
                null
        )).when(client).generate(any());

        worker.runOnce();
        assertEquals("FAILED", state(first));

        reset(client);
        doReturn(true).when(client).ready();
        doAnswer(
                invocation -> output(invocation.getArgument(0))
        ).when(client).generate(any());

        jdbc.update("""
                insert into interview_question_keys(
                    user_id, company_id, question_fingerprint, deleted_at
                )
                values (?, ?, ?, current_timestamp(6))
                """,
                user, company,
                InterviewGeneration.fingerprint(questionText())
        );

        gate();
        String second = accepted();
        worker.runOnce();

        assertEquals("FAILED", state(second));
        assertEquals(
                0,
                count(
                        "select count(*) from interview_questions where company_id = ?",
                        company
                )
        );
    }

    @Test
    void retryableFailureUsesSameTaskAndStopsAfterThreeRuns() throws Exception {
        String task = accepted();

        doThrow(
                new Failure("AI_HTTP_429", true, 2, null)
        ).when(client).generate(any());

        for (int i = 0; i < 3; i++) {
            gate();

            jdbc.update("""
                    update ai_tasks set available_at = '2000-01-01'
                    where id = ?
                    """, task);

            worker.runOnce();
        }

        assertEquals("FAILED", state(task));
        assertEquals(
                3,
                count("select count(*) from ai_task_runs where task_id = ?", task)
        );
        assertEquals(
                0,
                count(
                        "select count(*) from interview_questions where company_id = ?",
                        company
                )
        );
    }

    @Test
    void expiredWorkerCannotSaveAfterAnotherWorkerClaimsTask() throws Exception {
        String task = accepted();
        var tx = new TransactionTemplate(manager);

        var first = tx.execute(
                s -> repository.claim(clock.instant())
        );
        assertNotNull(first);

        Lease lease = tx.execute(
                s -> repository.load(first, clock.instant())
        );

        jdbc.update("""
                update ai_tasks set lease_until = '2000-01-01'
                where id = ?
                """, task);

        gate();

        var second = tx.execute(
                s -> repository.claim(clock.instant())
        );

        assertNotNull(second);
        assertTrue(second.fence() > first.fence());

        tx.executeWithoutResult(
                s -> repository.save(first, output(lease), clock.instant())
        );

        assertEquals(
                0,
                count(
                        "select count(*) from interview_questions where company_id = ?",
                        company
                )
        );

        tx.executeWithoutResult(
                s -> repository.fail(
                        second,
                        new Failure("TEST_DONE", false),
                        clock.instant()
                )
        );
    }

    @Test
    void todayQuestionSlotsDoNotBlockRemainingAttemptOnIncludedQuestion()
            throws Exception {
        String task = accepted();
        worker.runOnce();

        long question = question(task);
        LocalDate today = clock.instant()
                .atZone(InterviewGeneration.KST).toLocalDate();

        consume(question, today, 1, 1, 1);
        consume(question, today, 1, 2, 2);

        for (int i = 2; i <= 3; i++) {
            long deleted = insert("""
                    insert into interview_question_keys(
                        user_id, company_id, question_fingerprint, deleted_at
                    )
                    values (?, ?, ?, current_timestamp(6))
                    """,
                    user, company,
                    InterviewGeneration.sha256("deleted-" + i)
            );

            consume(deleted, today, i, i + 1, 1);
        }

        detail(question, token)
                .andExpect(
                        jsonPath("$.data.usage.daily.questionRemaining").value(0)
                )
                .andExpect(
                        jsonPath("$.data.usage.questionIncludedToday").value(true)
                )
                .andExpect(
                        jsonPath("$.data.usage.questionRemaining").value(1)
                )
                .andExpect(jsonPath("$.data.usage.canSubmit").value(true));

        entry(null).andExpect(
                jsonPath("$.data.nextAction").value("ANSWER_QUESTION")
        );

        error(create(key(), token), 409, "DAILY_LIMIT_EXCEEDED");
    }

    @Test
    void draftHasPriorityAndGenerationDoesNotConsumeSubmittedQuota()
            throws Exception {
        String task = accepted();
        worker.runOnce();
        long question = question(task);

        jdbc.update("""
                insert into interview_drafts(
                    question_id, user_id, company_id, draft_text
                )
                values (?, ?, ?, '저장 초안')
                """, question, user, company);

        entry(null).andExpect(
                jsonPath("$.data.nextAction").value("RESUME_DRAFT")
        );

        detail(question, token).andExpect(
                jsonPath("$.data.draft.text").value("저장 초안")
        );

        assertEquals(
                0,
                count(
                        "select count(*) from interview_submissions where company_id = ?",
                        company
                )
        );
    }

    @Test
    void failedStorageRollsBackQuestionEvidenceAndTaskCompletionTogether()
            throws Exception {
        String task = accepted();

        doAnswer(invocation -> {
            invocation.callRealMethod();

            throw new org.springframework.dao.TransientDataAccessResourceException(
                    "test storage failure"
            );
        }).when(repository).save(any(), any(), any());

        assertThrows(
                org.springframework.dao.TransientDataAccessResourceException.class,
                worker::runOnce
        );

        assertEquals("PROCESSING", state(task));
        assertEquals(
                0,
                count(
                        "select count(*) from interview_questions where company_id = ?",
                        company
                )
        );
        assertEquals(
                0,
                count(
                        "select count(*) from interview_question_keys where company_id = ?",
                        company
                )
        );

        reset(repository);

        jdbc.update("""
                update ai_tasks set lease_until = '2000-01-01'
                where id = ?
                """, task);

        gate();
        worker.runOnce();

        assertEquals("SUCCEEDED", state(task));
        assertEquals(
                1,
                count(
                        "select count(*) from interview_questions where company_id = ?",
                        company
                )
        );
    }

    @Test
    void threeLifetimeAttemptsDoNotResetOnAnotherDayOrCompanyReactivation()
            throws Exception {
        String task = accepted();
        worker.runOnce();
        long question = question(task);

        LocalDate yesterday = clock.instant()
                .atZone(InterviewGeneration.KST)
                .toLocalDate().minusDays(1);

        for (int i = 1; i <= 3; i++) {
            consume(question, yesterday, 1, i, i);
        }

        jdbc.update("""
                update user_companies
                set active_slot = null, excluded_at = current_timestamp(6)
                where user_id = ?
                """, user);

        jdbc.update("""
                update user_companies
                set active_slot = 1, excluded_at = null
                where user_id = ?
                """, user);

        detail(question, token)
                .andExpect(jsonPath("$.data.usage.daily.used").value(0))
                .andExpect(jsonPath("$.data.usage.questionRemaining").value(0))
                .andExpect(jsonPath("$.data.usage.canSubmit").value(false));
    }

    @Test
    void deletedQuestionDoesNotReappearThroughSameCreationKey() throws Exception {
        String key = key();
        String task = data(create(key, token)).get("taskId").asText();

        worker.runOnce();
        long question = question(task);

        jdbc.update(
                "delete from interview_question_evidence where question_id = ?",
                question
        );
        jdbc.update(
                "delete from interview_questions where id = ?", question
        );
        jdbc.update("""
                update interview_question_keys
                set deleted_at = current_timestamp(6) where id = ?
                """, question);

        error(create(key, token), 404, "RESOURCE_NOT_FOUND");

        assertEquals(
                1,
                count("select count(*) from ai_tasks where company_id = ?", company)
        );
    }

    @Test
    void generationHourlyLimitRejectsNewLogicalTask() throws Exception {
        String first = accepted();

        jdbc.update(
                "update ai_tasks set status = 'FAILED' where id = ?", first
        );
        jdbc.update("""
                update interview_generation_jobs
                set active_user_id = null, active_company_id = null
                where task_id = ?
                """, first);

        for (int i = 0; i < 19; i++) {
            jdbc.update("""
                    insert into ai_tasks(
                        id, user_id, company_id, type, status,
                        context_id, policy_version_id,
                        request_key_hash, request_body_hmac
                    )
                    select ?, user_id, company_id, type, 'FAILED',
                           context_id, policy_version_id, ?, request_body_hmac
                    from ai_tasks where id = ?
                    """, key(), InterviewGeneration.sha256(key()), first);
        }

        error(create(key(), token), 429, "RATE_LIMIT_EXCEEDED");

        assertEquals(
                1,
                count("select count(*) from ai_contexts where company_id = ?", company)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", " ", "null", "{\"materialIds\":[1]}"
    })
    void generationRejectsAnyBody(String body) throws Exception {
        mvc.perform(
                post(createPath())
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key())
                        .contentType("application/json")
                        .content(body)
        ).andExpect(status().isBadRequest());
    }

    private void consume(
            long question,
            LocalDate day,
            int questionSlot,
            int dailySlot,
            int attempt
    ) {
        jdbc.update("""
                insert ignore into interview_daily_questions(
                    user_id, company_id, business_date, question_id, question_slot
                )
                values (?, ?, ?, ?, ?)
                """, user, company, day, question, questionSlot);

        jdbc.update("""
                insert into interview_submissions(
                    user_id, company_id, question_id, business_date,
                    daily_slot, attempt_number, request_key_hash, request_body_hmac
                )
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                user, company, question, day, dailySlot, attempt,
                InterviewGeneration.sha256(key()),
                InterviewGeneration.sha256(key())
        );
    }

    private String accepted() throws Exception {
        return data(
                create(key(), token).andExpect(status().isAccepted())
        ).get("taskId").asText();
    }

    private ResultActions create(String key, String credential)
            throws Exception {
        return mvc.perform(
                post(createPath())
                        .header("Authorization", "Bearer " + credential)
                        .header("Idempotency-Key", key)
        );
    }

    private ResultActions entry(String task) throws Exception {
        var request = get(entryPath())
                .header("Authorization", "Bearer " + token);

        if (task != null) request.param("taskId", task);

        return mvc.perform(request).andExpect(status().isOk());
    }

    private ResultActions detail(long question, String credential)
            throws Exception {
        return mvc.perform(
                get("/v1/interview-questions/" + question)
                        .header("Authorization", "Bearer " + credential)
        );
    }

    private JsonNode data(ResultActions result) throws Exception {
        return mapper.readTree(
                result.andReturn().getResponse().getContentAsString(
                        java.nio.charset.StandardCharsets.UTF_8
                )
        ).get("data");
    }

    private void error(
            ResultActions result,
            int status,
            String code
    ) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(jsonPath("$.error.code").value(code));
    }

    private String createPath() {
        return "/v1/companies/" + company + "/interview-question-tasks";
    }

    private String entryPath() {
        return "/v1/companies/" + company + "/interview-entry";
    }

    private String state(String task) {
        return jdbc.queryForObject(
                "select status from ai_tasks where id = ?",
                String.class,
                task
        );
    }

    private long question(String task) {
        return count(
                "select id from interview_questions where generation_task_id = ?",
                task
        );
    }

    private void gate() {
        jdbc.update("""
                update interview_generation_control
                set next_call_at = '2000-01-01' where id = 1
                """);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String questionText() {
        return "공시 근거를 바탕으로 해외영업 전략을 설명해 주세요.";
    }

    private Output output(Lease lease) {
        Section section = lease.input().sections().getFirst();

        return new Output(
                new Generated(
                        questionText(),
                        List.of(new Quote(
                                section.disclosureVersionId(),
                                section.sectionId(),
                                section.content()
                        ))
                ),
                new Metadata("stub-request", "gpt-6-luna", 10, 10)
        );
    }

    private long account() {
        long id = insert(
                "insert into users(nickname) values ('면접테스트')"
        );

        users.add(id);
        return id;
    }

    private String login(long id) {
        session = key();
        var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);

        jdbc.update("""
                insert into auth_sessions(id, user_id, created_at, expires_at)
                values (?, ?, ?, ?)
                """,
                session,
                id,
                LocalDateTime.ofInstant(now, ZoneOffset.UTC),
                LocalDateTime.ofInstant(
                        now.plusSeconds(3600), ZoneOffset.UTC
                )
        );

        return tokens.issue(id, session, 0, now, now.plusSeconds(3600));
    }

    private long material(String type, String title, String text) {
        long id = insert("""
                insert into personal_materials(user_id, type)
                values (?, ?)
                """, user, type);

        insert("""
                insert into material_versions(
                    material_id, user_id, material_type,
                    version_no, title, content_text
                )
                values (?, ?, ?, 1, ?, ?)
                """, id, user, type, title, text);

        jdbc.update("""
                update personal_materials
                set current_version_no = 1 where id = ?
                """, id);

        return id;
    }

    private void source(String type) {
        long disclosure = insert("""
                insert into disclosures(
                    company_id, receipt_no, report_name, report_type,
                    disclosed_on, source_url
                )
                values (?, ?, ?, ?, '2026-10-20', 'https://dart.fss.or.kr/')
                """,
                company,
                key().replace("-", "").substring(0, 20),
                type + " 보고서",
                type
        );

        long version = insert("""
                insert into disclosure_versions(
                    disclosure_id, company_id, version_no, status,
                    content_hash, extractor_version
                )
                values (?, ?, 1, 'PUBLISHED', ?, 'test-v1')
                """,
                disclosure,
                company,
                InterviewGeneration.sha256(type)
        );

        insert("""
                insert into disclosure_sections(
                    disclosure_version_id, company_id, section_key,
                    heading, content_text
                )
                values (
                    ?, ?, 'business',
                    '해외영업 사업 전략', '해외 판매 채널을 확대합니다.'
                )
                """, version, company);
    }

    private long count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();

        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS
            );

            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }

            return statement;
        }, key);

        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private static <T> List<T> concurrent(
            Callable<T> first,
            Callable<T> second
    ) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);

            var a = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return first.call();
            });

            var b = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return second.call();
            });

            start.countDown();

            return List.of(
                    a.get(20, TimeUnit.SECONDS),
                    b.get(20, TimeUnit.SECONDS)
            );
        }
    }
}