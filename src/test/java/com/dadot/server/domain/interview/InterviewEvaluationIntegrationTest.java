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
import com.dadot.server.domain.interview.repository.InterviewSubmissionRequestStore;
import com.dadot.server.global.security.AccessTokenIssuer;
import com.dadot.server.infrastructure.openai.OpenAiInterviewClient;
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
class InterviewEvaluationIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired AccessTokenIssuer tokens;
    @MockitoSpyBean Clock clock;
    @MockitoSpyBean InterviewSubmissionRequestStore requests;
    @MockitoSpyBean OpenAiInterviewClient client;
    @MockitoSpyBean OpenAiEvaluationClient evaluationClient;
    @MockitoSpyBean InterviewEvaluationResultRepository resultRepository;
    @Autowired InterviewEvaluationJobRepository jobs;
    @Autowired InterviewEvaluationWorker worker;
    @Autowired PlatformTransactionManager manager;

    private final List<Long> users = new ArrayList<>();
    private long user, company, question, section, evaluationPolicy;
    private String token;
    private boolean safe;

    @BeforeEach
    void setup() {
        safe = false;
        assertEquals("dadot_test_db", jdbc.queryForObject("select database()", String.class));
        safe = true;
        reset(clock, requests, client, evaluationClient, resultRepository);
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
        long generation = policy("GENERATION");
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
        clearInvocations(client);
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
        reset(clock, requests, client, evaluationClient, resultRepository);
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
            jdbc.update("update personal_materials set current_version_no = null where user_id = ?", id);
            for (String table : List.of("material_versions", "personal_materials", "user_companies", "auth_sessions")) {
                jdbc.update("delete from " + table + " where user_id = ?", id);
            }
            jdbc.update("delete from users where id = ?", id);
        }
        jdbc.update("delete from companies where id = ?", company);
    }

    @Test
    void processingHasNoResultAndWorkerStoresGroundedScoresAtomically() throws Exception {
        material("EXPERIENCE", "캐시 개선", "응답 시간을 줄였습니다.");
        JsonNode accepted = accepted(question);
        long attempt = accepted.get("attemptId").asLong();
        assertTrue(evaluation(attempt).get("result").isNull());
        assertEquals("PROCESSING", evaluation(attempt).get("status").asText());
        worker.runOnce();
        JsonNode result = evaluation(attempt).get("result");
        assertEquals(80, result.get("totalScore").asInt());
        assertEquals(80, result.get("scorePercent").asInt());
        assertEquals("READY", result.get("readinessStatus").asText());
        assertTrue(result.get("completionDecision").asBoolean());
        assertEquals(4, result.get("criteria").size());
        assertEquals(1, result.get("relatedMaterials").size());
        assertEquals("EXPERIENCE", result.get("relatedMaterials").get(0).get("sourceType").asText());
        assertEquals(1, count("select count(*) from interview_submissions where user_id = ?", user));
        assertEquals(4, count("select count(*) from evaluation_scores where evaluation_id = (select id from interview_evaluations where attempt_id = ?)", attempt));
        assertEquals(200, count("select output_tokens from ai_task_runs where task_id = ?", accepted.get("taskId").asText()));
    }

    @Test
    void seventySixIsNeedsImprovementAndZeroExperienceStillWorks() throws Exception {
        doAnswer(invocation -> {
            Lease lease = invocation.getArgument(0);
            return new Output(EvaluationFixtures.output(lease.input(), 19), null);
        }).when(evaluationClient).evaluate(any());
        long attempt = accepted(question).get("attemptId").asLong();
        worker.runOnce();
        JsonNode result = evaluation(attempt).get("result");
        assertEquals(76, result.get("totalScore").asInt());
        assertEquals("보완 필요", result.get("readinessLabel").asText());
        assertFalse(result.get("completionDecision").asBoolean());
        assertEquals(0, result.get("relatedMaterials").size());
    }

    @Test
    void usesSubmissionSnapshotAfterExperienceUpdateAndKeepsOldPolicyVersion() throws Exception {
        long material = material("EXPERIENCE", "초기 경험", "제출 당시 내용");
        long attempt = accepted(question).get("attemptId").asLong();
        insert("insert into material_versions(material_id,user_id,material_type,version_no,title,content_text) values (?,?,'EXPERIENCE',2,'수정 경험','제출 후 수정')", material, user);
        jdbc.update("update personal_materials set current_version_no = 2 where id = ?", material);
        jdbc.update("update interview_policy_versions set status = 'SUPERSEDED' where id = ?", evaluationPolicy);
        jdbc.update("update interview_policies set active_version_no = null where type = 'EVALUATION' and company_id = ?", company);
        doAnswer(invocation -> {
            Lease lease = invocation.getArgument(0);
            assertEquals("제출 당시 내용", lease.input().experiences().getFirst().content());
            assertEquals(1, lease.input().experiences().getFirst().version());
            return output(lease);
        }).when(evaluationClient).evaluate(any());
        worker.runOnce();
        assertEquals(evaluationPolicy, evaluation(attempt).get("result").get("rubricVersionId").asLong());
        assertEquals(1, evaluation(attempt).get("result").get("relatedMaterials").get(0).get("sourceVersion").asInt());
    }

    @Test
    void experienceDeletionAfterSuccessRemovesOnlyCardsAndPreservesScoreAndAnswer() throws Exception {
        long material = material("EXPERIENCE", "경험", "연결 가능한 원문");
        long attempt = accepted(question).get("attemptId").asLong();
        worker.runOnce();
        mvc.perform(delete("/v1/users/me/experiences/" + material).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        JsonNode view = evaluation(attempt);
        assertEquals("SUCCEEDED", view.get("status").asText());
        assertEquals(80, view.get("result").get("totalScore").asInt());
        assertEquals(0, view.get("result").get("relatedMaterials").size());
        assertEquals("최종 답변", view.get("attempt").get("text").asText());
        assertEquals(1, count("select count(*) from interview_submissions where user_id = ?", user));
    }

    @Test
    void deletionDuringHttpCancelsAndLateResultCannotRecreateAnything() throws Exception {
        long material = material("EXPERIENCE", "경험", "삭제할 원문");
        JsonNode accepted = accepted(question);
        doAnswer(invocation -> {
            Lease lease = invocation.getArgument(0);
            mvc.perform(delete("/v1/users/me/experiences/" + material).header("Authorization", "Bearer " + token))
                    .andExpect(status().isNoContent());
            return output(lease);
        }).when(evaluationClient).evaluate(any());
        worker.runOnce();
        JsonNode view = evaluation(accepted.get("attemptId").asLong());
        assertEquals("CANCELLED", view.get("status").asText());
        assertTrue(view.get("result").isNull());
        error(retry(accepted.get("attemptId").asLong(), key()), 409, "CONTEXT_UNAVAILABLE");
        assertEquals(0, count("select count(*) from interview_evaluations where user_id = ?", user));
    }

    @Test
    void stoppedEvidencePreventsProviderCall() throws Exception {
        long attempt = accepted(question).get("attemptId").asLong();
        jdbc.update("update disclosure_versions set status = 'SUSPENDED' where company_id = ?", company);
        worker.runOnce();
        assertEquals("CONTENT_UNAVAILABLE", evaluation(attempt).get("error").get("code").asText());
        verify(evaluationClient, never()).evaluate(any());
    }

    @Test
    void stoppedEvidenceDuringHttpPreventsResultSave() throws Exception {
        long attempt = accepted(question).get("attemptId").asLong();
        doAnswer(invocation -> {
            Lease lease = invocation.getArgument(0);
            jdbc.update("update disclosure_versions set status = 'SUSPENDED' where company_id = ?", company);
            return output(lease);
        }).when(evaluationClient).evaluate(any());
        worker.runOnce();
        assertEquals("FAILED", evaluation(attempt).get("status").asText());
        assertTrue(evaluation(attempt).get("result").isNull());
    }

    @Test
    void suspendedPolicyDuringHttpInvalidatesLateResult() throws Exception {
        long attempt = accepted(question).get("attemptId").asLong();
        doAnswer(invocation -> {
            Lease lease = invocation.getArgument(0);
            jdbc.update("update interview_policy_versions set status = 'SUSPENDED' where id = ?", evaluationPolicy);
            return output(lease);
        }).when(evaluationClient).evaluate(any());
        worker.runOnce();
        assertEquals("CANCELLED", evaluation(attempt).get("status").asText());
        assertEquals("NOT_CONFIGURED", evaluation(attempt).get("policyStatus").asText());
        assertEquals(0, count("select count(*) from interview_evaluations where user_id = ?", user));
    }

    @Test
    void accountInvalidationDuringHttpDoesNotStoreLateResult() throws Exception {
        JsonNode accepted = accepted(question);
        doAnswer(invocation -> {
            Lease lease = invocation.getArgument(0);
            jdbc.update("update users set status = 'DELETING' where id = ?", user);
            return output(lease);
        }).when(evaluationClient).evaluate(any());
        worker.runOnce();
        assertEquals("CANCELLED", jdbc.queryForObject("select status from ai_tasks where id = ?", String.class, accepted.get("taskId").asText()));
        assertEquals(0, count("select count(*) from interview_evaluations where user_id = ?", user));
    }

    @Test
    void retryUsesSameTaskWithoutChargingAndReplaySurvivesSuccess() throws Exception {
        JsonNode accepted = accepted(question);
        long attempt = accepted.get("attemptId").asLong(); String task = accepted.get("taskId").asText();
        String originalHash = jdbc.queryForObject("select request_key_hash from ai_tasks where id = ?", String.class, task);
        fail(task); String key = key();
        JsonNode retried = data(retry(attempt, key).andExpect(status().isAccepted()));
        assertEquals(task, retried.get("taskId").asText());
        retry(attempt, key()).andExpect(status().isAccepted());
        worker.runOnce();
        assertEquals(retried, data(retry(attempt, key).andExpect(status().isAccepted())));
        error(retry(attempt, key()), 409, "EVALUATION_ALREADY_SUCCEEDED");
        assertEquals(originalHash, jdbc.queryForObject("select request_key_hash from ai_tasks where id = ?", String.class, task));
        assertEquals(1, count("select count(*) from interview_submissions where user_id = ?", user));
        assertEquals(1, count("select count(*) from ai_tasks where user_id = ? and type = 'INTERVIEW_EVALUATION'", user));
        assertEquals(1, count("select count(*) from ai_task_runs where task_id = ?", task));
    }

    @Test
    void retryIsIdempotentUnderConcurrentRequests() throws Exception {
        JsonNode accepted = accepted(question); long attempt = accepted.get("attemptId").asLong();
        fail(accepted.get("taskId").asText()); String key = key();
        List<JsonNode> results = concurrent(
                () -> data(retry(attempt, key).andExpect(status().isAccepted())),
                () -> data(retry(attempt, key).andExpect(status().isAccepted())));
        assertEquals(results.getFirst(), results.getLast());
        assertEquals(0, count("select attempts from interview_evaluation_jobs where task_id = ?", accepted.get("taskId").asText()));
        assertEquals(1, count("select count(*) from api_idempotency_requests where user_id = ? and operation like '%/evaluation-tasks'", user));
    }

    @Test
    void automaticRetriesAreBoundedAndManualRetryPreservesRunNumbers() throws Exception {
        JsonNode accepted = accepted(question); String task = accepted.get("taskId").asText();
        doThrow(new Failure("AI_HTTP_429", true, 2, null)).when(evaluationClient).evaluate(any());
        for (int i = 0; i < 3; i++) {
            gate(); jdbc.update("update ai_tasks set available_at = '2000-01-01' where id = ?", task); worker.runOnce();
        }
        assertEquals("FAILED", evaluation(accepted.get("attemptId").asLong()).get("status").asText());
        assertEquals(3, count("select count(*) from ai_task_runs where task_id = ?", task));
        retry(accepted.get("attemptId").asLong(), key()).andExpect(status().isAccepted());
        doAnswer(invocation -> output(invocation.getArgument(0))).when(evaluationClient).evaluate(any());
        gate(); worker.runOnce();
        assertEquals(4, count("select max(run_no) from ai_task_runs where task_id = ?", task));
        assertEquals("SUCCEEDED", evaluation(accepted.get("attemptId").asLong()).get("status").asText());
    }

    @Test
    void staleLeaseCannotSaveAfterAnotherWorkerClaims() throws Exception {
        JsonNode accepted = accepted(question); String task = accepted.get("taskId").asText();
        Claim first = tx().execute(s -> jobs.claim(clock.instant()));
        Lease old = tx().execute(s -> jobs.load(first, clock.instant()));
        jdbc.update("update ai_tasks set lease_until = '2000-01-01' where id = ?", task); gate();
        Claim second = tx().execute(s -> jobs.claim(clock.instant()));
        assertTrue(second.fence() > first.fence());
        tx().executeWithoutResult(s -> resultRepository.save(first, output(old), clock.instant()));
        assertEquals(0, count("select count(*) from interview_evaluations where user_id = ?", user));
        Lease current = tx().execute(s -> jobs.load(second, clock.instant()));
        tx().executeWithoutResult(s -> resultRepository.save(second, output(current), clock.instant()));
        assertEquals(1, count("select count(*) from interview_evaluations where user_id = ?", user));
    }

    @Test
    void parallelWorkersOnlyEvaluateOnce() throws Exception {
        accepted(question);
        concurrent(() -> { worker.runOnce(); return true; }, () -> { worker.runOnce(); return true; });
        verify(evaluationClient, times(1)).evaluate(any());
        assertEquals(1, count("select count(*) from interview_evaluations where user_id = ?", user));
    }

    @Test
    void storageFailureRollsBackResultScoresLinksAndTaskSuccessTogether() throws Exception {
        material("EXPERIENCE", "경험", "원문");
        JsonNode accepted = accepted(question); String task = accepted.get("taskId").asText();
        doAnswer(invocation -> {
            invocation.callRealMethod(); throw new TransientDataAccessResourceException("test-only failure");
        }).when(resultRepository).save(any(), any(), any());
        assertThrows(TransientDataAccessResourceException.class, worker::runOnce);
        assertEquals(0, count("select count(*) from interview_evaluations where user_id = ?", user));
        assertEquals("PROCESSING", evaluation(accepted.get("attemptId").asLong()).get("status").asText());
        reset(resultRepository);
        jdbc.update("update ai_tasks set lease_until = '2000-01-01' where id = ?", task); gate(); worker.runOnce();
        assertEquals(1, count("select count(*) from interview_evaluations where user_id = ?", user));
    }

    @Test
    void invalidModelOutputDoesNotStorePartialResult() throws Exception {
        JsonNode accepted = accepted(question);
        doAnswer(invocation -> {
            Lease lease = invocation.getArgument(0); Generated value = output(lease).generated();
            return new Output(new Generated("가".repeat(31), value.overallFeedback(), value.criteria(), value.strengths(),
                    value.improvements(), value.relatedMaterials()), null);
        }).when(evaluationClient).evaluate(any());
        worker.runOnce();
        assertEquals("AI_INVALID_OUTPUT", evaluation(accepted.get("attemptId").asLong()).get("error").get("code").asText());
        assertEquals(0, count("select count(*) from interview_evaluations where user_id = ?", user));
    }

    @Test
    void oversizedInputIsRejectedBeforeChargingOrQueueing() throws Exception {
        for (int i = 0; i < 9; i++) material("EXPERIENCE", "경험" + i, "가".repeat(5000));
        error(submit(question, key(), "{\"text\":\"최종 답변\"}"), 409, "INTERVIEW_INPUT_TOO_LARGE");
        assertNoSubmissions();
        assertEquals(1, count("select count(*) from ai_contexts where user_id = ?", user));
    }

    @Test
    void corruptedCoverLetterContextIsRejectedInsteadOfSilentlyFiltered() throws Exception {
        long material = material("COVER_LETTER", null, "참고하면 안 되는 내용");
        JsonNode accepted = accepted(question); String task = accepted.get("taskId").asText();
        jdbc.update("insert into ai_context_materials(context_id,material_version_id,user_id) select t.context_id,v.id,t.user_id from ai_tasks t join material_versions v on v.material_id = ? where t.id = ?", material, task);
        worker.runOnce();
        assertEquals("CANCELLED", evaluation(accepted.get("attemptId").asLong()).get("status").asText());
        verify(evaluationClient, never()).evaluate(any());
    }

    @Test
    void legacyTaskWithoutJobIsPreparedFromItsOriginalSnapshot() throws Exception {
        JsonNode accepted = accepted(question);
        jdbc.update("delete from interview_evaluation_jobs where task_id = ?", accepted.get("taskId").asText());
        worker.runOnce();
        assertEquals("SUCCEEDED", evaluation(accepted.get("attemptId").asLong()).get("status").asText());
    }

    @Test
    void immutableInputChangesAreDetectedByFingerprint() throws Exception {
        long material = material("EXPERIENCE", "경험", "처음 내용");
        long attempt = accepted(question).get("attemptId").asLong();
        jdbc.update("update material_versions set content_text = '변조된 원문' where material_id = ?", material);
        worker.runOnce();
        assertEquals("AI_POLICY_CHANGED", evaluation(attempt).get("error").get("code").asText());
        verify(evaluationClient, never()).evaluate(any());
    }

    @Test
    void retryRateLimitBoundsNewFailedRequeuesWithoutAdditionalUsage() throws Exception {
        JsonNode accepted = accepted(question); long attempt = accepted.get("attemptId").asLong();
        for (int i = 0; i < 20; i++) retry(attempt, key()).andExpect(status().isAccepted());
        fail(accepted.get("taskId").asText());
        error(retry(attempt, key()), 429, "RATE_LIMIT_EXCEEDED");
        assertEquals(1, count("select count(*) from interview_submissions where user_id = ?", user));
    }

    @Test
    void expiredRetryKeyCannotCreateAnotherRun() throws Exception {
        JsonNode accepted = accepted(question); long attempt = accepted.get("attemptId").asLong(); String key = key();
        retry(attempt, key).andExpect(status().isAccepted());
        jdbc.update("update api_idempotency_requests set created_at = '2026-10-29', expires_at = '2026-10-30' where user_id = ? and operation like '%/evaluation-tasks'", user);
        error(retry(attempt, key), 409, "IDEMPOTENCY_KEY_EXPIRED");
        assertEquals(0, count("select count(*) from ai_task_runs where task_id = ?", accepted.get("taskId").asText()));
    }

    @Test
    void pastEvaluationRemainsReadableAfterCompanyExclusion() throws Exception {
        long attempt = accepted(question).get("attemptId").asLong();
        jdbc.update("update user_companies set active_slot = null, excluded_at = current_timestamp(6) where user_id = ?", user);
        worker.runOnce();
        assertEquals("SUCCEEDED", evaluation(attempt).get("status").asText());
        assertEquals("COMPANY_INACTIVE", evaluation(attempt).get("usage").get("blockedReason").asText());
    }

    @Test
    void deletedAttemptsAndOtherOwnersCannotReadOrRetry() throws Exception {
        JsonNode accepted = accepted(question); long attempt = accepted.get("attemptId").asLong();
        String other = login(account());
        mvc.perform(get("/v1/interview-attempts/" + attempt + "/evaluation").header("Authorization", "Bearer " + other)).andExpect(status().isNotFound());
        mvc.perform(post("/v1/interview-attempts/" + attempt + "/evaluation-tasks").header("Authorization", "Bearer " + other).header("Idempotency-Key", key())).andExpect(status().isNotFound());
        String key = key(); retry(attempt, key).andExpect(status().isAccepted());
        jdbc.update("delete from interview_attempts where id = ?", attempt);
        error(retry(attempt, key), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void endpointsRejectBodiesUnexpectedQueriesAndMissingAuthentication() throws Exception {
        long attempt = accepted(question).get("attemptId").asLong();
        mvc.perform(get("/v1/interview-attempts/" + attempt + "/evaluation")).andExpect(status().isUnauthorized());
        for (String body : List.of("{}", "null", " ")) mvc.perform(post("/v1/interview-attempts/" + attempt + "/evaluation-tasks")
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", key()).contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/interview-attempts/" + attempt + "/evaluation").header("Authorization", "Bearer " + token).param("extra", "1")).andExpect(status().isBadRequest());
        error(retry(attempt, "invalid-key"), 400, "INVALID_INPUT");
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

    private void finish() {
        jdbc.update("update ai_tasks set status = 'SUCCEEDED', completed_at = current_timestamp(6) where user_id = ? and type = 'INTERVIEW_EVALUATION'", user);
    }

    private void assertNoSubmissions() {
        for (String table : List.of("interview_submissions", "interview_attempts", "interview_daily_questions", "api_idempotency_requests")) {
            assertEquals(0, count("select count(*) from " + table + " where user_id = ?", user), table);
        }
        assertEquals(0, count("select count(*) from ai_tasks where user_id = ? and type = 'INTERVIEW_EVALUATION'", user));
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