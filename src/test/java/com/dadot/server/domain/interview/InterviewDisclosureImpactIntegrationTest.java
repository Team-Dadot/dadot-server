package com.dadot.server.domain.interview;

import com.dadot.server.domain.interview.model.InterviewEvaluation;
import com.dadot.server.domain.interview.model.InterviewGeneration;
import com.dadot.server.domain.interview.model.InterviewPolicy;
import com.dadot.server.domain.interview.repository.InterviewDisclosureImpactRepository;
import com.dadot.server.domain.interview.repository.InterviewEvaluationJobRepository;
import com.dadot.server.domain.interview.service.InterviewEvaluationWorker;
import com.dadot.server.domain.interview.service.InterviewGenerationWorker;
import com.dadot.server.global.security.AccessTokenIssuer;
import com.dadot.server.infrastructure.openai.OpenAiEvaluationClient;
import com.dadot.server.infrastructure.openai.OpenAiInterviewClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

@SpringBootTest(properties = {
        "app.dart.worker-enabled=false", "app.dart.originals.enabled=false",
        "app.account-deletion.worker-enabled=false",
        "app.interview-generation.worker-enabled=false", "app.interview-evaluation.worker-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InterviewDisclosureImpactIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @Autowired AccessTokenIssuer tokens;
    @Autowired InterviewEvaluationWorker evaluationWorker;
    @Autowired InterviewGenerationWorker generationWorker;
    @Autowired InterviewEvaluationJobRepository evaluationJobs;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean Clock clock;
    @MockitoSpyBean InterviewDisclosureImpactRepository impact;
    @MockitoSpyBean OpenAiInterviewClient generator;
    @MockitoSpyBean OpenAiEvaluationClient evaluator;

    private final List<Long> users = new ArrayList<>();
    private long admin, user, generatingUser, company, generationPolicy, evaluationPolicy;
    private String adminToken, token, generatingToken;
    private Report annual, half, quarter;
    private boolean safe;
    private record Report(long filing, long version, long section) {}

    @BeforeEach
    void setup() {
        safe = false;
        assertEquals("dadot_test_db", jdbc.queryForObject("select database()", String.class));
        safe = true;
        reset(clock, impact, generator, evaluator);
        doReturn(Instant.now().truncatedTo(ChronoUnit.SECONDS)).when(clock).instant();
        users.clear();
        admin = account("ADMIN");
        user = account("USER");
        generatingUser = account("USER");
        adminToken = login(admin);
        token = login(user);
        generatingToken = login(generatingUser);
        company = insert("""
                insert into companies(dart_corp_code, name, is_service_company)
                values ('99009000', '공시연동테스트', true)
                """);
        for (long owner : List.of(user, generatingUser)) preparation(owner);
        generationPolicy = policy("GENERATION");
        evaluationPolicy = policy("EVALUATION");
        annual = report("ANNUAL", "PUBLISHED");
        half = report("HALF_YEAR", "PUBLISHED");
        quarter = report("QUARTERLY", "PUBLISHED");
        doReturn(true).when(generator).ready();
        doReturn(true).when(evaluator).ready();
        doAnswer(call -> generationOutput(call.getArgument(0))).when(generator).generate(any());
        doAnswer(call -> evaluationOutput(call.getArgument(0))).when(evaluator).evaluate(any());
        for (String control : List.of("interview_generation_control", "interview_evaluation_control")) {
            jdbc.update("update " + control + " set next_call_at = '1970-01-01' where id = 1");
        }
    }

    @AfterEach
    void cleanup() {
        if (!safe) return;
        InterviewTestDataCleanup.deleteEvaluationTasks(jdbc, company);
        reset(impact, generator, evaluator);
        jdbc.update("delete l from evaluation_material_links l join interview_evaluations e on e.id = l.evaluation_id where e.user_id in (?, ?)", user, generatingUser);
        jdbc.update("delete s from evaluation_scores s join interview_evaluations e on e.id = s.evaluation_id where e.user_id in (?, ?)", user, generatingUser);
        jdbc.update("delete from interview_evaluations where user_id in (?, ?)", user, generatingUser);
        jdbc.update("delete r from ai_task_runs r join ai_tasks t on t.id = r.task_id where t.company_id = ?", company);
        for (String table : List.of("interview_question_evidence", "interview_drafts", "interview_attempts",
                "interview_submissions", "interview_daily_questions", "interview_questions", "interview_question_keys")) {
            jdbc.update("delete from " + table + " where company_id = ?", company);
        }
        for (long owner : users) jdbc.update("delete from api_idempotency_requests where user_id = ?", owner);
        jdbc.update("delete j from interview_generation_jobs j join ai_tasks t on t.id = j.task_id where t.company_id = ?", company);
        jdbc.update("delete j from interview_evaluation_jobs j join ai_tasks t on t.id = j.task_id where t.company_id = ?", company);
        jdbc.update("delete from ai_tasks where company_id = ?", company);
        jdbc.update("delete m from ai_context_materials m join ai_contexts x on x.id = m.context_id where x.company_id = ?", company);
        jdbc.update("delete from ai_contexts where company_id = ?", company);
        jdbc.update("delete c from evaluation_criteria c join interview_policy_versions v on v.id = c.policy_version_id join interview_policies p on p.id = v.policy_id where p.company_id = ?", company);
        jdbc.update("update interview_policies set active_version_no = null where company_id = ?", company);
        jdbc.update("delete v from interview_policy_versions v join interview_policies p on p.id = v.policy_id where p.company_id = ?", company);
        jdbc.update("delete from interview_policies where company_id = ?", company);
        jdbc.update("update disclosures set corrects_disclosure_id = null where company_id = ?", company);
        for (String table : List.of("disclosure_sections", "disclosure_versions", "disclosures")) {
            jdbc.update("delete from " + table + " where company_id = ?", company);
        }
        for (long owner : users) {
            jdbc.update("delete from admin_audit_events where actor_user_id = ?", owner);
            for (String table : List.of("user_companies", "auth_sessions")) {
                jdbc.update("delete from " + table + " where user_id = ?", owner);
            }
            jdbc.update("delete from users where id = ?", owner);
        }
        jdbc.update("delete from companies where id = ?", company);
        reset(clock);
    }

    @Test
    void countsQuestionsOnceAndCancelsPendingTasksWithoutDeletingHistory() throws Exception {
        long first = question(user, annual);
        long secondOwner = account("USER");
        preparation(secondOwner);
        long second = question(secondOwner, annual);
        long extra = insert("""
                insert into disclosure_sections(disclosure_version_id, company_id, section_key, content_text)
                values (?, ?, 'extra', '서비스를 확대합니다.')
                """, annual.version(), company);
        evidence(first, user, extra);
        submit(first).andExpect(status().isAccepted());
        String evaluation = jdbc.queryForObject("select id from ai_tasks where source_attempt_id is not null and user_id = ?", String.class, user);
        String generation = generation();
        var claim = new TransactionTemplate(manager).execute(tx -> evaluationJobs.claim(clock.instant()));
        assertNotNull(claim);
        review(annual, "SUSPENDED").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affectedInterviewCount").value(2));
        cancelled(evaluation);
        cancelled(generation);
        assertEquals("CANCELLED", jdbc.queryForObject("select status from ai_task_runs where id = ?", String.class, claim.runId()));
        assertEquals(2, count("select count(*) from interview_questions where company_id = ?", company));
        assertEquals(1, count("select count(*) from interview_attempts where company_id = ?", company));
        assertEquals(1, count("select count(*) from interview_submissions where company_id = ?", company));
        assertEquals(0, count("select count(*) from interview_generation_jobs where task_id = ? and active_user_id is not null", generation));
        mvc.perform(get("/v1/interview-questions/" + first).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.usage.canSubmit").value(false));
        assertNotEquals(first, second);
    }

    @Test
    void unusedQuestionSourceStillCancelsGenerationButNotUnrelatedEvaluation() throws Exception {
        long question = question(user, annual);
        JsonNode answer = data(submit(question).andExpect(status().isAccepted()));
        String generation = generation();
        review(quarter, "SUSPENDED").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affectedInterviewCount").value(0));
        cancelled(generation);
        assertEquals("PROCESSING", state(answer.get("taskId").asString()));
        evaluationWorker.runOnce();
        assertEquals("SUCCEEDED", state(answer.get("taskId").asString()));
    }

    @Test
    void cancellationBeforeWorkerCallAvoidsProviderCalls() throws Exception {
        long question = question(user, annual);
        String evaluation = data(submit(question)).get("taskId").asString();
        String generation = generation();
        review(annual, "SUSPENDED").andExpect(status().isOk());
        generationWorker.runOnce();
        evaluationWorker.runOnce();
        cancelled(generation);
        cancelled(evaluation);
        verify(generator, never()).generate(any());
        verify(evaluator, never()).evaluate(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopAndRepublishDuringHttpCannotStoreOldResult(boolean evaluation) throws Exception {
        String task;
        long question = question(user, annual);
        if (evaluation) {
            task = data(submit(question)).get("taskId").asString();
            doAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                review(annual, "SUSPENDED").andExpect(status().isOk());
                republish(annual);
                return evaluationOutput(call.getArgument(0));
            }).when(evaluator).evaluate(any());
            evaluationWorker.runOnce();
        } else {
            task = generation();
            doAnswer(call -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                review(annual, "SUSPENDED").andExpect(status().isOk());
                republish(annual);
                return generationOutput(call.getArgument(0));
            }).when(generator).generate(any());
            generationWorker.runOnce();
        }
        cancelled(task);
        assertEquals("PUBLISHED", sourceState(annual.version()));
        assertEquals(1, count("select count(*) from interview_questions where company_id = ?", company));
        assertEquals(0, count("select count(*) from interview_evaluations where user_id = ?", user));
        assertEquals(1, count("select count(*) from ai_task_runs where task_id = ? and status = 'CANCELLED'", task));
        if (!evaluation) {
            String next = generation();
            assertNotEquals(task, next);
            assertEquals("PROCESSING", state(next));
        }
    }

    @Test
    void successfulEvaluationRemainsReadableAndUsageIsNotReset() throws Exception {
        long question = question(user, annual);
        JsonNode answer = data(submit(question));
        evaluationWorker.runOnce();
        long attempt = answer.get("attemptId").longValue();
        String path = "/v1/interview-attempts/" + attempt + "/evaluation";
        JsonNode before = data(mvc.perform(get(path).header("Authorization", "Bearer " + token)));
        review(annual, "SUSPENDED").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affectedInterviewCount").value(1));
        JsonNode after = data(mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
        assertEquals(before.get("result"), after.get("result"));
        assertEquals("SUCCEEDED", state(answer.get("taskId").asString()));
        assertEquals(1, count("select count(*) from interview_submissions where user_id = ?", user));
        submit(question).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONTENT_UNAVAILABLE"));
    }

    @Test
    void correctionLinkRetiresOldSourceAndCancelsItsTasks() throws Exception {
        long question = question(user, annual);
        String task = data(submit(question)).get("taskId").asString();
        Report correction = report("ANNUAL", "DRAFT");
        var body = request(correction, "IN_REVIEW");
        body.put("correctionOf", annual.filing());
        send(correction, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affectedInterviewCount").value(1));
        assertEquals("SUSPENDED", sourceState(annual.version()));
        cancelled(task);
        mvc.perform(get("/v1/interview-questions/" + question).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.evidence[0].isSuperseded").value(true));
    }

    @Test
    void publishingNewVersionOnlyAffectsOldVersionEvidence() throws Exception {
        long question = question(user, annual);
        String task = data(submit(question)).get("taskId").asString();
        Report newer = content(annual.filing(), "APPROVED", 2);
        review(newer, "PUBLISHED").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affectedInterviewCount").value(1));
        assertEquals("SUPERSEDED", sourceState(annual.version()));
        assertEquals("PUBLISHED", sourceState(newer.version()));
        cancelled(task);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "FAILED", "CANCELLED"})
    void terminalTasksAreNotOverwritten(String status) throws Exception {
        long question = question(user, annual);
        String task = data(submit(question)).get("taskId").asString();
        jdbc.update("update ai_tasks set status = ?, last_error_code = 'ORIGINAL', completed_at = ? where id = ?", status, utc(clock.instant()), task);
        review(annual, "SUSPENDED").andExpect(status().isOk());
        assertEquals(status, state(task));
        assertEquals("ORIGINAL", jdbc.queryForObject("select last_error_code from ai_tasks where id = ?", String.class, task));
    }

    @Test
    void unrelatedDisclosureLeavesTasksRunning() throws Exception {
        long question = question(user, annual);
        String evaluation = data(submit(question)).get("taskId").asString();
        String generation = generation();
        Report unrelated = report("ANNUAL", "DRAFT");
        review(unrelated, "IN_REVIEW").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affectedInterviewCount").value(0));
        assertEquals("PROCESSING", state(evaluation));
        assertEquals("PROCESSING", state(generation));
    }

    @Test
    void failureAfterCancellationRollsBackDisclosureTasksAndAudits() throws Exception {
        long question = question(user, annual);
        String task = data(submit(question)).get("taskId").asString();
        String generation = generation();
        var claim = new TransactionTemplate(manager).execute(tx -> evaluationJobs.claim(clock.instant()));
        assertNotNull(claim);
        doAnswer(call -> {
            call.callRealMethod();
            throw new TransientDataAccessResourceException("test storage failure");
        }).when(impact).invalidate(anyLong(), anySet(), any());
        review(annual, "SUSPENDED").andExpect(status().isServiceUnavailable());
        assertEquals("PUBLISHED", sourceState(annual.version()));
        assertEquals(1, revision(annual));
        assertEquals("PROCESSING", state(task));
        assertEquals("PROCESSING", state(generation));
        assertEquals("PROCESSING", jdbc.queryForObject("select status from ai_task_runs where id = ?", String.class, claim.runId()));
        assertEquals(0, count("select count(*) from admin_audit_events where actor_user_id = ?", admin));
        reset(impact);
        review(annual, "SUSPENDED").andExpect(status().isOk());
        cancelled(task);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "{}", "[null,null,null]"})
    void malformedSnapshotFailsClosedWithoutPartialCancellation(String sources) throws Exception {
        long question = question(user, annual);
        String task = data(submit(question)).get("taskId").asString();
        String generation = generation();
        jdbc.update("update interview_generation_jobs set sources_json = ? where task_id = ?", sources, generation);
        review(annual, "SUSPENDED").andExpect(status().isServiceUnavailable());
        assertEquals("PUBLISHED", sourceState(annual.version()));
        assertEquals("PROCESSING", state(task));
    }

    @RepeatedTest(3)
    void submissionRaceIsEitherRejectedOrAcceptedThenCancelled() throws Exception {
        long question = question(user, annual);
        var responses = concurrent(
                () -> review(annual, "SUSPENDED").andReturn().getResponse().getStatus(),
                () -> submit(question).andReturn().getResponse().getStatus());
        assertEquals(200, responses.getFirst());
        if (responses.get(1) == 202) {
            assertEquals(1, count("select count(*) from ai_tasks where source_attempt_id is not null and company_id = ? and status = 'CANCELLED'", company));
            assertEquals(1, count("select count(*) from interview_submissions where company_id = ?", company));
        } else {
            assertEquals(409, responses.get(1));
            assertEquals(0, count("select count(*) from interview_submissions where company_id = ?", company));
        }
    }

    @Test
    void competingAdminUpdatesUseExpectedVersion() throws Exception {
        long question = question(user, annual);
        String task = data(submit(question)).get("taskId").asString();
        var body = request(annual, "SUSPENDED");
        var responses = new ArrayList<>(concurrent(
                () -> send(annual, body).andReturn().getResponse().getStatus(),
                () -> send(annual, body).andReturn().getResponse().getStatus()));
        responses.sort(Integer::compareTo);
        assertEquals(List.of(200, 409), responses);
        cancelled(task);
    }

    @Test
    void cancellationSpansMultipleBatches() throws Exception {
        for (int i = 0; i < 201; i++) {
            long owner = account("USER");
            preparation(owner);
            long question = question(owner, annual);
            mvc.perform(post("/v1/interview-questions/" + question + "/attempts")
                            .header("Authorization", "Bearer " + login(owner)).header("Idempotency-Key", key())
                            .contentType("application/json").content("{\"text\":\"서비스를 확대하겠습니다.\"}"))
                    .andExpect(status().isAccepted());
        }
        review(annual, "SUSPENDED").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affectedInterviewCount").value(201));
        assertEquals(201, count("select count(*) from ai_tasks where company_id = ? and type = 'INTERVIEW_EVALUATION' and status = 'CANCELLED'", company));
        assertEquals(201, count("select count(*) from interview_questions where company_id = ?", company));
    }

    private long account(String role) {
        long id = insert("insert into users(nickname, role) values ('공시영향테스트', ?)", role);
        users.add(id);
        return id;
    }

    private void preparation(long owner) {
        jdbc.update("insert into user_companies(user_id, company_id, active_slot, custom_job_name) values (?, ?, 1, '백엔드')", owner, company);
    }

    private String login(long owner) {
        String session = key();
        Instant now = clock.instant();
        jdbc.update("insert into auth_sessions(id, user_id, created_at, expires_at) values (?, ?, ?, ?)", session, owner, utc(now), utc(now.plusSeconds(3600)));
        return tokens.issue(owner, session, 0, now, now.plusSeconds(3600));
    }

    private long policy(String type) {
        long parent = insert("insert into interview_policies(policy_key, type, company_id) values (?, ?, ?)", type + ":" + company + ":0", type, company);
        String config = "{\"materialTypes\":[\"EXPERIENCE\"],\"aggregationRule\":"
                + (type.equals("EVALUATION") ? "\"" + InterviewPolicy.AGGREGATION + "\"" : "null") + "}";
        long version = insert("""
                insert into interview_policy_versions(policy_id, version_no, status, prompt_template, rule_config, published_at)
                values (?, 1, 'PUBLISHED', '검증 지침', ?, current_timestamp(6))
                """, parent, config);
        if (type.equals("EVALUATION")) for (int i = 0; i < 4; i++) {
            jdbc.update("insert into evaluation_criteria(policy_version_id, position, code, name, description) values (?, ?, ?, ?, '채점 기준')",
                    version, i + 1, InterviewPolicy.CODES.get(i), InterviewPolicy.LABELS.get(i));
        }
        jdbc.update("update interview_policies set active_version_no = 1 where id = ?", parent);
        return version;
    }

    private Report report(String type, String status) {
        long filing = insert("""
                insert into disclosures(company_id, receipt_no, report_name, report_type, disclosed_on, source_url)
                values (?, ?, '공시 보고서', ?, '2026-10-01', 'https://dart.fss.or.kr/')
                """, company, key().replace("-", "").substring(0, 20), type);
        return content(filing, status, 1);
    }

    private Report content(long filing, String status, int number) {
        long version = insert("""
                insert into disclosure_versions(disclosure_id, company_id, version_no, status, original_object_key, content_hash, extractor_version)
                values (?, ?, ?, ?, 'original.zip', ?, 'test-v1')
                """, filing, company, number, status, InterviewGeneration.sha256(key()));
        long section = insert("""
                insert into disclosure_sections(disclosure_version_id, company_id, section_key, heading, content_text)
                values (?, ?, 'business', '사업의 내용', '서비스를 확대합니다.')
                """, version, company);
        return new Report(filing, version, section);
    }

    private long question(long owner, Report source) {
        String context = key(), task = key(), fingerprint = InterviewGeneration.sha256(key());
        jdbc.update("insert into ai_contexts(id, user_id, company_id, job_is_custom, job_name_snapshot, personalization_version) values (?, ?, ?, true, '백엔드', 0)", context, owner, company);
        jdbc.update("""
                insert into ai_tasks(id, user_id, company_id, type, status, context_id, policy_version_id, request_key_hash, request_body_hmac)
                values (?, ?, ?, 'INTERVIEW_GENERATION', 'SUCCEEDED', ?, ?, ?, ?)
                """, task, owner, company, context, generationPolicy, InterviewGeneration.sha256(key()), InterviewGeneration.sha256(key()));
        long id = insert("insert into interview_question_keys(user_id, company_id, question_fingerprint) values (?, ?, ?)", owner, company, fingerprint);
        jdbc.update("""
                insert into interview_questions(id, user_id, company_id, generation_task_id, context_id, generation_policy_version_id, question_text, question_fingerprint)
                values (?, ?, ?, ?, ?, ?, '서비스 확장에 어떻게 대응하겠습니까?', ?)
                """, id, owner, company, task, context, generationPolicy, fingerprint);
        evidence(id, owner, source.section());
        return id;
    }

    private void evidence(long question, long owner, long section) {
        jdbc.update("insert into interview_question_evidence(question_id, user_id, company_id, section_id, quotation) values (?, ?, ?, ?, '서비스를 확대합니다.')", question, owner, company, section);
    }

    private ResultActions submit(long question) throws Exception {
        return mvc.perform(post("/v1/interview-questions/" + question + "/attempts")
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", key())
                .contentType("application/json").content("{\"text\":\"서비스를 확대하겠습니다.\"}"));
    }

    private String generation() throws Exception {
        return data(mvc.perform(post("/v1/companies/" + company + "/interview-question-tasks")
                        .header("Authorization", "Bearer " + generatingToken).header("Idempotency-Key", key()))
                .andExpect(status().isAccepted())).get("taskId").asString();
    }

    private LinkedHashMap<String, Object> request(Report report, String status) {
        var body = new LinkedHashMap<String, Object>();
        body.put("expectedVersion", revision(report));
        body.put("disclosureVersionId", report.version());
        body.put("status", status);
        body.put("reason", "공시 변경 검증");
        return body;
    }

    private ResultActions review(Report report, String status) throws Exception {
        return send(report, request(report, status));
    }

    private ResultActions send(Report report, Object body) throws Exception {
        return mvc.perform(patch("/v1/admin/disclosures/" + report.filing())
                .header("Authorization", "Bearer " + adminToken).contentType("application/json")
                .content(json.writeValueAsString(body)));
    }

    private void republish(Report report) throws Exception {
        for (String status : List.of("IN_REVIEW", "APPROVED", "PUBLISHED")) review(report, status).andExpect(status().isOk());
    }

    private InterviewGeneration.Output generationOutput(InterviewGeneration.Lease lease) {
        var source = lease.input().sections().getFirst();
        return new InterviewGeneration.Output(new InterviewGeneration.Generated(
                "공시를 바탕으로 서비스 확장 전략을 설명해 주세요.",
                List.of(new InterviewGeneration.Quote(source.disclosureVersionId(), source.sectionId(), source.content()))), null);
    }

    private InterviewEvaluation.Output evaluationOutput(InterviewEvaluation.Lease lease) {
        var scores = InterviewPolicy.CODES.stream().map(code -> new InterviewEvaluation.Score(
                code, 19, "구체적으로 설명해 주세요.", "제출한 답변을 기준으로 평가했습니다.", lease.input().answer())).toList();
        return new InterviewEvaluation.Output(new InterviewEvaluation.Generated("경험을 구체적으로 말해 주세요.",
                "서비스 확장 방향과 본인의 역할을 연결해 주세요.", scores, List.of(), List.of(), List.of()), null);
    }

    private void cancelled(String task) {
        assertEquals("CANCELLED", state(task));
        assertEquals("CONTENT_UNAVAILABLE", jdbc.queryForObject("select last_error_code from ai_tasks where id = ?", String.class, task));
        assertEquals(0, count("select count(*) from ai_tasks where id = ? and (lease_owner is not null or lease_until is not null)", task));
    }

    private String state(String task) { return jdbc.queryForObject("select status from ai_tasks where id = ?", String.class, task); }
    private String sourceState(long version) { return jdbc.queryForObject("select status from disclosure_versions where id = ?", String.class, version); }
    private long revision(Report report) { return count("select version from disclosures where id = ?", report.filing()); }
    private long count(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private JsonNode data(ResultActions response) throws Exception { return json.readTree(response.andReturn().getResponse().getContentAsByteArray()).get("data"); }

    private long insert(String sql, Object... args) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
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
    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
}