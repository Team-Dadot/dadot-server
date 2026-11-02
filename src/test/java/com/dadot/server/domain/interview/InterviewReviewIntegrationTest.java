package com.dadot.server.domain.interview;

import com.dadot.server.domain.interview.model.InterviewGeneration;
import com.dadot.server.domain.interview.model.InterviewEvaluation.*;
import com.dadot.server.domain.interview.model.InterviewGeneration.Failure;
import com.dadot.server.domain.interview.model.InterviewGeneration.Metadata;
import com.dadot.server.domain.interview.repository.InterviewEvaluationJobRepository;
import com.dadot.server.domain.interview.repository.InterviewEvaluationResultRepository;
import com.dadot.server.domain.interview.service.InterviewEvaluationWorker;
import com.dadot.server.infrastructure.openai.OpenAiEvaluationClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.global.security.AccessTokenIssuer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.dart.worker-enabled=false", "app.dart.originals.enabled=false",
        "app.quiz-generation.enabled=false", "app.quiz-generation.worker-enabled=false",
        "app.interview-generation.enabled=false", "app.interview-generation.worker-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InterviewReviewIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired AccessTokenIssuer tokens;
    @MockitoSpyBean Clock clock;
    @MockitoSpyBean OpenAiEvaluationClient evaluationClient;
    @MockitoSpyBean InterviewEvaluationResultRepository resultRepository;
    @Autowired InterviewEvaluationJobRepository jobs;
    @MockitoSpyBean com.dadot.server.domain.interview.repository.InterviewReviewRepository reviews;
    @Autowired InterviewEvaluationWorker worker;
    @Autowired PlatformTransactionManager manager;

    private final List<Long> users = new ArrayList<>();
    private long user, company, question, section, evaluationPolicy, generationPolicy;
    private String token;
    private boolean safe;

    @BeforeEach
    void setup() {
        safe = false;
        assertEquals("dadot_test_db", jdbc.queryForObject("select database()", String.class));
        safe = true;
        reset(clock, evaluationClient, resultRepository, reviews);
        doReturn(Instant.parse("2026-10-31T14:59:00.123456Z")).when(clock).instant();
        users.clear();
        user = account();
        token = login(user);
        company = insert("""
                insert into companies(dart_corp_code, name, is_service_company)
                values ('99007000', '답변테스트기업', true)
                """);
        jdbc.update("""
                insert into user_companies(user_id, company_id, active_slot, custom_job_name)
                values (?, ?, 1, '백엔드')
                """, user, company);
        jdbc.update("insert into user_onboarding(user_id, completed_at) values (?, current_timestamp(6))", user);
        jdbc.update("insert into user_preferences(user_id, selected_company_id) values (?, ?)", user, company);
        long generation = policy("GENERATION");
        generationPolicy = generation;
        evaluationPolicy = policy("EVALUATION");
        long disclosure = insert("""
                insert into disclosures(company_id, receipt_no, report_name, report_type, disclosed_on, source_url)
                values (?, ?, '사업보고서', 'ANNUAL', '2026-10-20', 'https://dart.fss.or.kr/')
                """, company, key().replace("-", "").substring(0, 20));
        long version = insert("""
                insert into disclosure_versions(disclosure_id, company_id, version_no, status, content_hash, extractor_version)
                values (?, ?, 1, 'PUBLISHED', ?, 'test-v1')
                """, disclosure, company, InterviewGeneration.sha256(key()));
        section = insert("""
                insert into disclosure_sections(disclosure_version_id, company_id, section_key, heading, content_text)
                values (?, ?, 'business', '서비스', '서비스를 확대합니다.')
                """, version, company);
        question = question(generation);
        doReturn(true).when(evaluationClient).ready();
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            Lease lease = invocation.getArgument(0);
            return output(lease);
        }).when(evaluationClient).evaluate(any());
        gate();
    }

    @AfterEach
    void cleanup() {
        if (!safe) return;
        InterviewTestDataCleanup.deleteEvaluationTasks(jdbc, company);
        reset(clock, evaluationClient, resultRepository, reviews);
        jdbc.update("delete l from evaluation_material_links l join interview_evaluations e on e.id = l.evaluation_id where e.user_id = ?", user);
        jdbc.update("delete s from evaluation_scores s join interview_evaluations e on e.id = s.evaluation_id where e.user_id = ?", user);
        jdbc.update("delete from interview_evaluations where user_id = ?", user);
        jdbc.update("delete r from ai_task_runs r join ai_tasks t on t.id = r.task_id where t.company_id = ?", company);
        for (String table : List.of("interview_question_evidence", "interview_drafts", "interview_attempts",
                "interview_submissions", "interview_daily_questions", "interview_questions", "interview_question_keys")) {
            jdbc.update("delete from " + table + " where company_id = ?", company);
        }
        for (long id : users) jdbc.update("delete from api_idempotency_requests where user_id = ?", id);
        jdbc.update("delete from ai_tasks where company_id = ?", company);
        jdbc.update("delete m from ai_context_materials m join ai_contexts x on x.id = m.context_id where x.company_id = ?", company);
        jdbc.update("delete from ai_contexts where company_id = ?", company);
        jdbc.update("delete c from evaluation_criteria c join interview_policy_versions v on v.id = c.policy_version_id join interview_policies p on p.id = v.policy_id where p.company_id = ?", company);
        jdbc.update("update interview_policies set active_version_no = null where company_id = ?", company);
        jdbc.update("delete v from interview_policy_versions v join interview_policies p on p.id = v.policy_id where p.company_id = ?", company);
        jdbc.update("delete from interview_policies where company_id = ?", company);
        for (String table : List.of("disclosure_sections", "disclosure_versions", "disclosures")) {
            jdbc.update("delete from " + table + " where company_id = ?", company);
        }
        for (long id : users) {
            for (String table : List.of("quiz_answers", "quiz_set_items", "quiz_sets", "user_preferences", "user_onboarding")) {
                jdbc.update("delete from " + table + " where user_id = ?", id);
            }
            jdbc.update("update personal_materials set current_version_no = null where user_id = ?", id);
            for (String table : List.of("material_versions", "personal_materials", "user_companies", "auth_sessions")) {
                jdbc.update("delete from " + table + " where user_id = ?", id);
            }
            jdbc.update("delete from users where id = ?", id);
        }
        jdbc.update("delete from companies where id = ?", company);
    }

    @Test
    void questionsIncludeUnansweredAndDraftsInStableCreationOrder() throws Exception {
        long second = question(generationPolicy);
        jdbc.update("update interview_questions set created_at = '2026-10-31 00:00:00' where user_id = ?", user);
        draft(question, "저장한 초안", null).andExpect(status().isOk());
        JsonNode page = questionPage("&size=1");
        assertEquals(2, page.get("totalElements").asInt());
        assertEquals(2, page.get("totalPages").asInt());
        assertTrue(page.get("hasNext").asBoolean());
        assertEquals(second, page.get("content").get(0).get("questionId").asLong());
        JsonNode last = questionPage("&page=1&size=1").get("content").get(0);
        assertEquals(question, last.get("questionId").asLong());
        assertTrue(last.get("hasDraft").asBoolean());
        assertTrue(last.get("latestAttempt").isNull());
        assertEquals(0, last.get("questionUsed").asInt());
        assertEquals(3, last.get("questionRemaining").asInt());
        assertEquals(0, questionPage("&page=2147483647&size=100").get("content").size());
        verify(evaluationClient, never()).evaluate(any());
    }

    @Test
    void latestFailedAnswerNeverFallsBackToEarlierSuccess() throws Exception {
        answer(question, 20);
        JsonNode latest = accepted(question);
        fail(latest.get("taskId").asText());
        JsonNode row = questionPage("").get("content").get(0);
        assertEquals(2, row.get("latestAttempt").get("attemptNumber").asInt());
        assertTrue(row.get("latestScorePercent").isNull());
        assertTrue(row.get("completionDecision").isNull());
        assertTrue(row.get("readinessStatus").isNull());
    }

    @Test
    void attemptDeletionKeepsNumbersDraftsUsageAndIdempotencyTombstones() throws Exception {
        JsonNode first = answer(question, 19);
        JsonNode second = answer(question, 21);
        draft(question, "다음 답변 초안", null).andExpect(status().isOk());
        long attempt = second.get("attemptId").asLong();
        removeAttempt(attempt).andExpect(status().isNoContent()).andExpect(content().string(""));
        removeAttempt(attempt).andExpect(status().isNoContent());
        JsonNode page = attemptPage(question);
        assertEquals(1, page.get("totalElements").asInt());
        assertEquals(first.get("attemptId").asLong(), page.get("content").get(0).get("attemptId").asLong());
        JsonNode row = questionPage("").get("content").get(0);
        assertEquals(76, row.get("latestScorePercent").asInt());
        assertTrue(row.get("hasDraft").asBoolean());
        assertEquals(2, row.get("questionUsed").asInt());
        assertEquals(1, row.get("questionRemaining").asInt());
        assertEquals(1, count("select count(*) from api_idempotency_requests where user_id = ? and task_id is null", user));
        error(detail(attempt, token), 404, "RESOURCE_NOT_FOUND");
        error(retry(attempt, key()), 404, "RESOURCE_NOT_FOUND");
        JsonNode third = data(submit(question, key(), "{\"text\":\"세 번째 답변\"}").andExpect(status().isAccepted()));
        assertEquals(3, third.get("attemptNumber").asInt());
        assertEquals(3, third.get("usage").get("questionUsed").asInt());
        assertEquals(3, third.get("usage").get("daily").get("used").asInt());
    }

    @Test
    void deletingEveryAttemptCannotRefundLifetimeOrDailySlots() throws Exception {
        List<Long> attempts = new ArrayList<>();
        for (int i = 0; i < 3; i++) attempts.add(answer(question, 20).get("attemptId").asLong());
        for (long attempt : attempts) removeAttempt(attempt).andExpect(status().isNoContent());
        assertEquals(0, attemptPage(question).get("totalElements").asInt());
        JsonNode row = questionPage("").get("content").get(0);
        assertTrue(row.get("latestAttempt").isNull());
        assertTrue(row.get("latestScorePercent").isNull());
        assertEquals(3, row.get("questionUsed").asInt());
        error(submit(question, key(), "{\"text\":\"새 답변\"}"), 409, "QUESTION_LIMIT_EXCEEDED");
        assertEquals(3, count("select count(*) from interview_submissions where user_id = ?", user));
        assertEquals(1, count("select count(*) from interview_daily_questions where user_id = ?", user));
    }

    @Test
    void questionDeletionRemovesAllPrivateRecordsButKeepsFingerprintAndConsumption() throws Exception {
        material("EXPERIENCE", "경험", "연결할 원문");
        answer(question, 20);
        JsonNode active = accepted(question);
        draft(question, "삭제할 초안", null).andExpect(status().isOk());
        String generation = jdbc.queryForObject("select generation_task_id from interview_questions where id = ?", String.class, question);
        jdbc.update("""
                insert into api_idempotency_requests(user_id,task_id,scope_hash,operation,key_hash,body_hmac,status,response_http_status,response_payload,expires_at)
                values (?, ?, ?, 'POST /v1/companies/test/interview-question-tasks', ?, ?, 'SUCCEEDED', 202, '{}', date_add(current_timestamp(6), interval 1 day))
                """, user, generation, InterviewGeneration.sha256(key()), InterviewGeneration.sha256(key()), InterviewGeneration.sha256(key()));
        removeQuestion(question).andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
        removeQuestion(question).andExpect(status().isNoContent());
        removeAttempt(active.get("attemptId").asLong()).andExpect(status().isNoContent());
        for (String table : List.of("interview_questions", "interview_attempts", "interview_drafts", "interview_question_evidence",
                "interview_evaluations", "ai_contexts", "ai_context_materials", "ai_tasks")) {
            assertEquals(0, count("select count(*) from " + table + " where user_id = ?", user), table);
        }
        assertEquals(0, count("select count(*) from evaluation_material_links"));
        assertEquals(0, count("select count(*) from evaluation_scores"));
        assertEquals(1, count("select count(*) from interview_question_keys where id = ? and deleted_at is not null", question));
        assertEquals(2, count("select count(*) from interview_submissions where user_id = ?", user));
        assertEquals(1, count("select count(*) from interview_daily_questions where user_id = ?", user));
        assertEquals(3, count("select count(*) from api_idempotency_requests where user_id = ? and task_id is null", user));
        error(submit(question, key(), "{\"text\":\"복원 불가\"}"), 404, "RESOURCE_NOT_FOUND");
        mvc.perform(get("/v1/interview-questions/" + question).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionDuringHttpPreventsLateEvaluationFromReturning(boolean entireQuestion) throws Exception {
        JsonNode accepted = accepted(question);
        long attempt = accepted.get("attemptId").asLong();
        doAnswer(invocation -> {
            Lease lease = invocation.getArgument(0);
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            if (entireQuestion) removeQuestion(question).andExpect(status().isNoContent());
            else removeAttempt(attempt).andExpect(status().isNoContent());
            return output(lease);
        }).when(evaluationClient).evaluate(any());
        worker.runOnce();
        assertEquals(0, count("select count(*) from interview_evaluations where user_id = ?", user));
        assertEquals(0, count("select count(*) from ai_tasks where id = ?", accepted.get("taskId").asText()));
        error(detail(attempt, token), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void staleClaimCannotStoreOrFailADeletedTask() throws Exception {
        JsonNode accepted = accepted(question);
        Claim claim = tx().execute(s -> jobs.claim(clock.instant()));
        Lease lease = tx().execute(s -> jobs.load(claim, clock.instant()));
        removeAttempt(accepted.get("attemptId").asLong()).andExpect(status().isNoContent());
        tx().executeWithoutResult(s -> resultRepository.save(claim, output(lease), clock.instant()));
        tx().executeWithoutResult(s -> jobs.fail(claim, new Failure("AI_CONNECTION_ERROR", true), clock.instant()));
        assertEquals(0, count("select count(*) from ai_task_runs where task_id = ?", claim.taskId()));
        assertEquals(0, count("select count(*) from interview_evaluations where user_id = ?", user));
    }

    @Test
    void concurrentDeletesRemainIdempotent() throws Exception {
        JsonNode accepted = accepted(question); long attempt = accepted.get("attemptId").asLong();
        concurrent(() -> removeQuestion(question).andExpect(status().isNoContent()).andReturn().getResponse().getStatus(),
                () -> removeAttempt(attempt).andExpect(status().isNoContent()).andReturn().getResponse().getStatus());
        concurrent(() -> removeQuestion(question).andExpect(status().isNoContent()).andReturn().getResponse().getStatus(),
                () -> removeQuestion(question).andExpect(status().isNoContent()).andReturn().getResponse().getStatus());
        assertEquals(1, count("select count(*) from interview_submissions where user_id = ?", user));
    }

    @Test
    void concurrentSubmissionAndAttemptDeletionCannotReuseAttemptNumber() throws Exception {
        long first = answer(question, 20).get("attemptId").asLong();
        concurrent(() -> removeAttempt(first).andExpect(status().isNoContent()).andReturn().getResponse().getStatus(),
                () -> submit(question, key(), "{\"text\":\"다음 답변\"}").andExpect(status().isAccepted()).andReturn().getResponse().getStatus());
        assertEquals(2, count("select count(*) from interview_submissions where user_id = ?", user));
        assertEquals(2, attemptPage(question).get("content").get(0).get("attemptNumber").asInt());
    }

    @Test
    void concurrentSubmissionAndQuestionDeletionCannotReviveQuestion() throws Exception {
        answer(question, 20);
        var outcomes = concurrent(() -> removeQuestion(question).andReturn().getResponse().getStatus(),
                () -> submit(question, key(), "{\"text\":\"다음 답변\"}").andReturn().getResponse().getStatus());
        assertEquals(204, outcomes.getFirst());
        assertTrue(List.of(202, 404).contains(outcomes.getLast()));
        assertEquals(0, count("select count(*) from interview_questions where id = ?", question));
        assertEquals(0, count("select count(*) from interview_attempts where user_id = ?", user));
        assertTrue(count("select count(*) from interview_submissions where user_id = ?", user) >= 1);
    }

    @Test
    void storageFailureRollsBackDeletionAndReturnsServiceUnavailable() throws Exception {
        long attempt = answer(question, 20).get("attemptId").asLong();
        doAnswer(invocation -> { invocation.callRealMethod(); throw new TransientDataAccessResourceException("test-only"); })
                .when(reviews).deleteQuestion(eq(user), eq(question), any());
        error(removeQuestion(question), 503, "SERVICE_UNAVAILABLE");
        assertEquals(1, questionPage("").get("totalElements").asInt());
        assertEquals("SUCCEEDED", evaluation(attempt).get("status").asText());
        assertEquals(0, count("select count(*) from api_idempotency_requests where user_id = ? and task_id is null", user));
    }

    @Test
    void sessionInvalidatedBeforeCommitRollsBackAllChanges() throws Exception {
        long attempt = answer(question, 20).get("attemptId").asLong();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            jdbc.update("update auth_sessions set revoked_at = ? where user_id = ?", utc(clock.instant()), user);
            return null;
        }).when(reviews).deleteAttempt(eq(user), eq(question), eq(attempt), any());
        error(removeAttempt(attempt), 401, "UNAUTHENTICATED");
        detail(attempt, token).andExpect(status().isOk());
        assertEquals(80, evaluation(attempt).get("result").get("totalScore").asInt());
    }

    @Test
    void excludedCompanyRemainsInReviewsUntilItsLastRecordIsDeleted() throws Exception {
        jdbc.update("update user_companies set active_slot = null, excluded_at = current_timestamp(6) where user_id = ?", user);
        JsonNode companies = data(mvc.perform(get("/v1/reviews/companies").header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
        assertEquals(1, companies.get("totalElements").asInt());
        assertEquals(1, companies.get("content").get(0).get("interviewQuestionCount").asInt());
        assertFalse(companies.get("content").get(0).get("isActive").asBoolean());
        assertEquals(1, questionPage("").get("totalElements").asInt());
        mvc.perform(get("/v1/reviews/quizzes").param("companyId", Long.toString(company)).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        removeQuestion(question).andExpect(status().isNoContent());
        companies = data(mvc.perform(get("/v1/reviews/companies").header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
        assertEquals(0, companies.get("totalElements").asInt());
        mvc.perform(get("/v1/reviews/interviews").param("companyId", Long.toString(company)).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    }

    @Test
    void dashboardUsesLatestRemainingAnswerOnceAndSeparatesNonSuccessStates() throws Exception {
        answer(question, 19);
        answer(question, 21);
        long processingQuestion = question(generationPolicy), failedQuestion = question(generationPolicy);
        accepted(processingQuestion);
        JsonNode failed = accepted(failedQuestion); fail(failed.get("taskId").asText());
        JsonNode summary = dashboard().get("interviewEvaluationSummary");
        assertEquals(84, summary.get("averageScorePercent").asInt());
        assertEquals(1, summary.get("evaluatedQuestionCount").asInt());
        assertEquals(1, summary.get("processingQuestionCount").asInt());
        assertEquals(1, summary.get("failedQuestionCount").asInt());
        assertEquals("CONFIGURED", summary.get("policyStatus").asText());
        jdbc.update("update ai_tasks set status = 'CANCELLED' where id = ?", failed.get("taskId").asText());
        summary = dashboard().get("interviewEvaluationSummary");
        assertEquals(0, summary.get("failedQuestionCount").asInt());
        assertEquals(1, summary.get("cancelledQuestionCount").asInt());
    }

    @Test
    void averageRoundsHalfUpAndDeletionRecalculatesRepresentative() throws Exception {
        JsonNode old = answer(question, 19);
        JsonNode latest = answer(question, 20);
        long secondQuestion = question(generationPolicy);
        doAnswer(invocation -> {
            Lease lease = invocation.getArgument(0); Generated base = output(lease).generated();
            var scores = new ArrayList<>(base.criteria()); Score first = scores.getFirst();
            scores.set(0, new Score(first.criterionCode(), 19, first.feedback(), first.reason(), first.answerQuote()));
            return new Output(new Generated(base.summaryFeedback(), base.overallFeedback(), scores, base.strengths(), base.improvements(), base.relatedMaterials()), null);
        }).when(evaluationClient).evaluate(any());
        accepted(secondQuestion); gate(); worker.runOnce();
        assertEquals(80, dashboard().get("interviewEvaluationSummary").get("averageScorePercent").asInt());
        removeAttempt(latest.get("attemptId").asLong()).andExpect(status().isNoContent());
        assertEquals(78, dashboard().get("interviewEvaluationSummary").get("averageScorePercent").asInt());
        removeAttempt(old.get("attemptId").asLong()).andExpect(status().isNoContent());
        removeQuestion(secondQuestion).andExpect(status().isNoContent());
        assertTrue(dashboard().get("interviewEvaluationSummary").get("averageScorePercent").isNull());
    }

    @Test
    void companyAndHomeDailyUsageIncludeDeletedSubmissionsAndResetAtKstMidnight() throws Exception {
        long attempt = answer(question, 20).get("attemptId").asLong();
        removeAttempt(attempt).andExpect(status().isNoContent());
        JsonNode home = dashboard();
        assertEquals(1, home.get("selectedCompany").get("interviewToday").get("used").asInt());
        assertEquals(1, home.get("selectedCompany").get("interviewToday").get("questionUsed").asInt());
        JsonNode companies = data(mvc.perform(get("/v1/users/me/companies").header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
        assertEquals(1, companies.get("content").get(0).get("interviewToday").get("used").asInt());
        doReturn(Instant.parse("2026-10-31T15:00:00Z")).when(clock).instant();
        home = dashboard();
        assertEquals("2026-11-01", home.get("businessDate").asText());
        assertEquals(0, home.get("selectedCompany").get("interviewToday").get("used").asInt());
        assertEquals(1, questionPage("").get("content").get(0).get("questionUsed").asInt());
    }

    @Test
    void historicalResultsSurviveSuspendedEvidenceAndMissingActivePolicy() throws Exception {
        answer(question, 20);
        jdbc.update("update disclosure_versions set status = 'SUSPENDED' where company_id = ?", company);
        jdbc.update("update interview_policy_versions set status = 'SUSPENDED' where id = ?", evaluationPolicy);
        JsonNode summary = dashboard().get("interviewEvaluationSummary");
        assertEquals("NOT_CONFIGURED", summary.get("policyStatus").asText());
        assertEquals(80, summary.get("averageScorePercent").asInt());
        assertEquals(80, questionPage("").get("content").get(0).get("latestScorePercent").asInt());
    }

    @Test
    void sharedContextRemainsWhenAnotherRecordStillReferencesIt() throws Exception {
        JsonNode accepted = accepted(question); long attempt = accepted.get("attemptId").asLong();
        String context = jdbc.queryForObject("select context_id from interview_attempts where id = ?", String.class, attempt);
        String sharingTask = key();
        jdbc.update("""
                insert into ai_tasks(id, user_id, company_id, type, status, context_id,
                    policy_version_id, request_key_hash, request_body_hmac)
                values (?, ?, ?, 'INTERVIEW_GENERATION', 'SUCCEEDED', ?, ?, ?, ?)
                """, sharingTask, user, company, context, generationPolicy,
                InterviewGeneration.sha256(key()), InterviewGeneration.sha256(key()));
        removeAttempt(attempt).andExpect(status().isNoContent());
        assertEquals(1, count("select count(*) from ai_contexts where id = ?", context));
        assertEquals(1, count("select count(*) from ai_tasks where id = ?", sharingTask));
        mvc.perform(get("/v1/interview-questions/" + question).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void originalSubmissionReplayCannotRestoreDeletedAttempt() throws Exception {
        String originalKey = key();
        JsonNode accepted = data(submit(question, originalKey, "{\"text\":\"최종 답변\"}").andExpect(status().isAccepted()));
        removeAttempt(accepted.get("attemptId").asLong()).andExpect(status().isNoContent());
        error(submit(question, originalKey, "{\"text\":\"최종 답변\"}"), 404, "RESOURCE_NOT_FOUND");
        assertEquals(1, count("select count(*) from interview_submissions where user_id = ?", user));
    }

    @Test
    void ownershipIsCheckedEvenForDeletionReplays() throws Exception {
        long attempt = accepted(question).get("attemptId").asLong();
        String own = token; token = login(account());
        mvc.perform(get("/v1/interview-questions/" + question + "/attempts").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        removeQuestion(question).andExpect(status().isNotFound());
        removeAttempt(attempt).andExpect(status().isNotFound());
        mvc.perform(get("/v1/reviews/interviews").param("companyId", Long.toString(company)).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        token = own; removeQuestion(question).andExpect(status().isNoContent());
        token = login(users.getLast()); removeAttempt(attempt).andExpect(status().isNotFound());
        token = own; removeAttempt(attempt).andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"&page=-1", "&size=0", "&size=101", "&page=2147483648", "&page=", "&page=1&page=2", "&filter=READY", "&size=abc"})
    void invalidQueryIsRejected(String suffix) throws Exception {
        mvc.perform(get("/v1/reviews/interviews?companyId=" + company + suffix).header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", " "})
    void allEndpointsRejectRequestBodies(String body) throws Exception {
        long attempt = accepted(question).get("attemptId").asLong();
        for (var request : List.of(get("/v1/reviews/interviews?companyId=" + company),
                get("/v1/interview-questions/" + question + "/attempts"),
                delete("/v1/interview-questions/" + question), delete("/v1/interview-attempts/" + attempt))) {
            mvc.perform(request.header("Authorization", "Bearer " + token).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void missingAuthenticationUnknownIdsAndDeleteQueriesAreRejected() throws Exception {
        mvc.perform(get("/v1/reviews/interviews").param("companyId", Long.toString(company))).andExpect(status().isUnauthorized());
        mvc.perform(delete("/v1/interview-questions/" + question)).andExpect(status().isUnauthorized());
        removeQuestion(InterviewPolicy.MAX_ID).andExpect(status().isNotFound());
        removeAttempt(InterviewPolicy.MAX_ID).andExpect(status().isNotFound());
        removeAttempt(0).andExpect(status().isBadRequest());
        mvc.perform(delete("/v1/interview-questions/" + question).param("extra", "1").header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/reviews/interviews").header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
    }

    @Test
    void queueProcessesNextQuestionAfterDeletingPendingAttempt() throws Exception {
        long old = accepted(question).get("attemptId").asLong();
        long next = question(generationPolicy);
        long attempt = accepted(next).get("attemptId").asLong();
        removeAttempt(old).andExpect(status().isNoContent());
        gate(); worker.runOnce();
        assertEquals("SUCCEEDED", evaluation(attempt).get("status").asText());
        verify(evaluationClient, times(1)).evaluate(any());
    }

    @Test
    void dashboardContinuesDraftAndThenReturnsToQuizWhenQuestionIsDeleted() throws Exception {
        draft(question, "작성 중인 답변", null).andExpect(status().isOk());
        assertEquals("CONTINUE_INTERVIEW", dashboard().get("nextLearningAction").asText());
        removeQuestion(question).andExpect(status().isNoContent());
        assertEquals("START_QUIZ", dashboard().get("nextLearningAction").asText());
    }

    @Test
    void dashboardComparesQuizAndDraftActivityAndDoesNotIgnoreRemainingAnswersToday() throws Exception {
        draft(question, "작성 중인 답변", null).andExpect(status().isOk());
        long set = insert("""
                insert into quiz_sets(user_id,company_id,business_date,mode,status,created_at)
                values (?, ?, '2026-10-31', 'NORMAL', 'IN_PROGRESS', ?)
                """, user, company, utc(clock.instant().plusSeconds(1)));
        assertEquals("CONTINUE_QUIZ", dashboard().get("nextLearningAction").asText());
        jdbc.update("update quiz_sets set status = 'COMPLETED', completed_at = current_timestamp(6), accuracy_after_correct = 0, accuracy_after_total = 0 where id = ?", set);
        jdbc.update("delete from interview_drafts where question_id = ?", question);
        var questionIds = List.of(question, question(generationPolicy), question(generationPolicy));
        for (long q : questionIds) answer(q, 20);
        JsonNode view = dashboard();
        assertEquals(0, view.get("selectedCompany").get("interviewToday").get("questionRemaining").asInt());
        assertEquals("START_INTERVIEW", view.get("nextLearningAction").asText());
        for (long q : questionIds) { answer(q, 20); answer(q, 20); }
        assertEquals("REVIEW", dashboard().get("nextLearningAction").asText());
    }

    @Test
    void representativeUsesLifetimeAttemptOrderEvenWhenWallClockMovesBackwards() throws Exception {
        JsonNode first = answer(question, 19);
        JsonNode second = answer(question, 21);
        jdbc.update("update interview_submissions set submitted_at = ? where id = ?", utc(clock.instant().plusSeconds(60)), first.get("attemptId").asLong());
        JsonNode detail = data(mvc.perform(get("/v1/interview-questions/" + question)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
        assertEquals(second.get("attemptId").asLong(), detail.get("latestAttempt").get("attemptId").asLong());
        assertEquals(second.get("attemptId").asLong(), questionPage("").get("content").get(0).get("latestAttempt").get("attemptId").asLong());
        assertEquals(84, dashboard().get("interviewEvaluationSummary").get("averageScorePercent").asInt());
    }

    private JsonNode answer(long q, int score) throws Exception {
        doAnswer(invocation -> {
            Lease lease = invocation.getArgument(0);
            return new Output(EvaluationFixtures.output(lease.input(), score), null);
        }).when(evaluationClient).evaluate(any());
        JsonNode accepted = accepted(q); gate(); worker.runOnce();
        assertEquals("SUCCEEDED", evaluation(accepted.get("attemptId").asLong()).get("status").asText());
        return accepted;
    }

    private JsonNode questionPage(String query) throws Exception {
        return data(mvc.perform(get("/v1/reviews/interviews?companyId=" + company + query)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
    }

    private JsonNode attemptPage(long q) throws Exception {
        return data(mvc.perform(get("/v1/interview-questions/" + q + "/attempts")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
    }

    private ResultActions removeQuestion(long q) throws Exception {
        return mvc.perform(delete("/v1/interview-questions/" + q).header("Authorization", "Bearer " + token));
    }

    private ResultActions removeAttempt(long attempt) throws Exception {
        return mvc.perform(delete("/v1/interview-attempts/" + attempt).header("Authorization", "Bearer " + token));
    }

    private JsonNode dashboard() throws Exception {
        return data(mvc.perform(get("/v1/users/me/dashboard").header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
    }

    private JsonNode evaluation(long attempt) throws Exception {
        return data(mvc.perform(get("/v1/interview-attempts/" + attempt + "/evaluation")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
    }

    private ResultActions retry(long attempt, String key) throws Exception {
        return mvc.perform(post("/v1/interview-attempts/" + attempt + "/evaluation-tasks")
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", key));
    }

    private void gate() {
        jdbc.update("update interview_evaluation_control set next_call_at = '2000-01-01' where id = 1");
    }

    private Output output(Lease lease) {
        return new Output(EvaluationFixtures.output(lease.input(), 20), new Metadata("test-request", "gpt-6-luna", 100, 200));
    }

    private void fail(String task) {
        jdbc.update("update ai_tasks set status = 'FAILED', lease_owner = null, lease_until = null, last_error_code = 'AI_CONNECTION_ERROR' where id = ?", task);
    }

    private TransactionTemplate tx() { return new TransactionTemplate(manager); }

    private long account() {
        long id = insert("insert into users(nickname) values ('답변테스트')");
        users.add(id);
        return id;
    }

    private String login(long id) {
        String session = key();
        Instant now = clock.instant().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant expiry = now.plusSeconds(3600);
        jdbc.update("insert into auth_sessions(id, user_id, created_at, expires_at) values (?, ?, ?, ?)",
                session, id, utc(now), utc(expiry));
        return tokens.issue(id, session, 0, now, expiry);
    }

    private long policy(String type) {
        long policy = insert("insert into interview_policies(policy_key, type, company_id) values (?, ?, ?)", type + ":" + company + ":0", type, company);
        String aggregation = type.equals("EVALUATION") ? "\"" + InterviewPolicy.AGGREGATION + "\"" : "null";
        long version = insert("""
                insert into interview_policy_versions(policy_id, version_no, status, prompt_template, rule_config, published_at)
                values (?, 1, 'PUBLISHED', '검증된 평가 지침', ?, current_timestamp(6))
                """, policy, "{\"materialTypes\":[\"EXPERIENCE\"],\"aggregationRule\":" + aggregation + "}");
        if (type.equals("EVALUATION")) for (int i = 0; i < 4; i++) {
            jdbc.update("""
                    insert into evaluation_criteria(policy_version_id, position, code, name, description)
                    values (?, ?, ?, ?, '채점 기준')
                    """, version, i + 1, InterviewPolicy.CODES.get(i), InterviewPolicy.LABELS.get(i));
        }
        jdbc.update("update interview_policies set active_version_no = 1 where id = ?", policy);
        return version;
    }

    private long question(long generation) {
        String context = key(), task = key(), fingerprint = InterviewGeneration.sha256(key());
        jdbc.update("""
                insert into ai_contexts(id, user_id, company_id, job_is_custom, job_name_snapshot, personalization_version)
                values (?, ?, ?, true, '백엔드', 0)
                """, context, user, company);
        jdbc.update("""
                insert into ai_tasks(id, user_id, company_id, type, status, context_id, policy_version_id, request_key_hash, request_body_hmac)
                values (?, ?, ?, 'INTERVIEW_GENERATION', 'SUCCEEDED', ?, ?, ?, ?)
                """, task, user, company, context, generation, InterviewGeneration.sha256(key()), InterviewGeneration.sha256(key()));
        long id = insert("insert into interview_question_keys(user_id, company_id, question_fingerprint) values (?, ?, ?)", user, company, fingerprint);
        jdbc.update("""
                insert into interview_questions(id, user_id, company_id, generation_task_id, context_id,
                    generation_policy_version_id, question_text, question_fingerprint)
                values (?, ?, ?, ?, ?, ?, '서비스 확장에 어떻게 대응하겠습니까?', ?)
                """, id, user, company, task, context, generation, fingerprint);
        jdbc.update("insert into interview_question_evidence(question_id, user_id, company_id, section_id, quotation) values (?, ?, ?, ?, '서비스를 확대합니다.')", id, user, company, section);
        return id;
    }

    private long material(String type, String title, String text) {
        long material = insert("insert into personal_materials(user_id, type) values (?, ?)", user, type);
        insert("insert into material_versions(material_id, user_id, material_type, version_no, title, content_text) values (?, ?, ?, 1, ?, ?)", material, user, type, type.equals("COVER_LETTER") ? null : title, text);
        jdbc.update("update personal_materials set current_version_no = 1 where id = ?", material);
        return material;
    }

    private ResultActions draft(long q, String text, Long version) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("text", text); body.put("version", version);
        return mvc.perform(put(draftPath(q)).header("Authorization", "Bearer " + token)
                .contentType("application/json").content(mapper.writeValueAsString(body)));
    }

    private ResultActions submit(long q, String key, String body) throws Exception {
        return mvc.perform(post("/v1/interview-questions/" + q + "/attempts")
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                .contentType("application/json").content(body));
    }

    private ResultActions detail(long id, String credential) throws Exception {
        return mvc.perform(get("/v1/interview-attempts/" + id).header("Authorization", "Bearer " + credential));
    }

    private JsonNode accepted(long q) throws Exception {
        return data(submit(q, key(), "{\"text\":\"최종 답변\"}").andExpect(status().isAccepted()));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("data");
    }

    private void error(ResultActions result, int status, String code) throws Exception {
        result.andExpect(status().is(status)).andExpect(jsonPath("$.error.code").value(code));
    }

    private long count(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
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

    private static <T> List<T> concurrent(Callable<T> first, Callable<T> second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return first.call(); });
            var b = executor.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return second.call(); });
            start.countDown();
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        }
    }

    private static String key() { return UUID.randomUUID().toString(); }
    private static String draftPath(long q) { return "/v1/interview-questions/" + q + "/draft"; }
    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}