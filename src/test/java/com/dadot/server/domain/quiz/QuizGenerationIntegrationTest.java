package com.dadot.server.domain.quiz;

import com.dadot.server.domain.quiz.model.QuizGeneration;
import com.dadot.server.domain.quiz.model.QuizGeneration.*;
import com.dadot.server.domain.quiz.repository.QuizBankRepository;
import com.dadot.server.domain.quiz.repository.QuizGenerationRepository;
import com.dadot.server.domain.quiz.service.QuizGenerationWorker;
import com.dadot.server.global.security.AccessTokenIssuer;
import com.dadot.server.infrastructure.openai.OpenAiQuizClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.dart.worker-enabled=false", "app.dart.originals.enabled=false",
        "app.quiz-generation.worker-enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuizGenerationIntegrationTest {
    private static final AtomicInteger CORP = new AtomicInteger(44000000);
    private static final String ORIGINAL = "연구개발에 투자합니다.";
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired AccessTokenIssuer tokens;
    @Autowired Clock clock;
    @Autowired QuizGenerationWorker worker;
    @Autowired QuizGenerationRepository repository;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean OpenAiQuizClient client;
    @MockitoSpyBean QuizBankRepository bank;
    private long user, company;
    private String token;
    private final List<Long> versions = new ArrayList<>();
    private boolean safe;

    @BeforeEach
    void setup() {
        safe = false;
        assertEquals("dadot_test_db", jdbc.queryForObject("select database()", String.class));
        safe = true;
        reset(client, bank);
        versions.clear();
        user = insert("insert into users(nickname, role) values ('quiz44-test', 'ADMIN')");
        company = insert("insert into companies(dart_corp_code, name) values (?, 'AI테스트기업')",
                String.valueOf(CORP.incrementAndGet()));
        var issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        String session = key();
        jdbc.update("insert into auth_sessions(id, user_id, created_at, expires_at) values (?, ?, ?, ?)",
                session, user, LocalDateTime.ofInstant(issued, ZoneOffset.UTC),
                LocalDateTime.ofInstant(issued.plusSeconds(3600), ZoneOffset.UTC));
        token = tokens.issue(user, session, 0, issued, issued.plusSeconds(3600));
        for (String type : List.of("ANNUAL", "HALF_YEAR", "QUARTERLY")) versions.add(source(type));
        when(client.ready()).thenReturn(true);
        when(client.policyHash()).thenReturn("a".repeat(64));
        doAnswer(invocation -> output(invocation.getArgument(0))).when(client).generate(any());
        allowClaim();
    }

    @AfterEach
    void cleanup() {
        if (!safe) return;
        reset(client, bank);
        jdbc.update("delete from api_idempotency_requests where user_id = ?", user);
        jdbc.update("delete from quiz_generation_candidates where company_id = ?", company);
        jdbc.update("delete b from quiz_generation_batches b join quiz_generation_jobs j on j.task_id=b.task_id where j.company_id=?", company);
        jdbc.update("delete from quiz_generation_jobs where company_id=?", company);
        jdbc.update("delete r from ai_task_runs r join ai_tasks t on t.id=r.task_id where t.company_id=?", company);
        jdbc.update("delete from ai_tasks where company_id=?", company);
        jdbc.update("delete from admin_audit_events where actor_user_id=?", user);
        jdbc.update("delete e from quiz_evidence e join quiz_question_versions v on v.id=e.quiz_version_id where v.company_id=?", company);
        jdbc.update("delete o from quiz_options o join quiz_question_versions v on v.id=o.quiz_version_id where v.company_id=?", company);
        jdbc.update("update quiz_questions set published_version_no=null where company_id=?", company);
        jdbc.update("delete from quiz_question_versions where company_id=?", company);
        jdbc.update("delete from quiz_questions where company_id=?", company);
        jdbc.update("delete from disclosure_sections where company_id=?", company);
        jdbc.update("delete from disclosure_versions where company_id=?", company);
        jdbc.update("update disclosures set corrects_disclosure_id=null where company_id=?", company);
        jdbc.update("delete from disclosures where company_id=?", company);
        jdbc.update("delete from user_companies where company_id=?", company);
        jdbc.update("delete from companies where id=?", company);
        jdbc.update("delete from auth_sessions where user_id=?", user);
        jdbc.update("delete from users where id=?", user);
    }

    @Test
    void integratesThreeReportsAndSavesOnlyDraftsWithRecordedUsage() throws Exception {
        String task = start(3);
        var result = drain(task);
        assertEquals("SUCCEEDED", result.path("status").asText());
        assertEquals(3, result.path("createdCount").asInt());
        assertTrue(result.path("targetReached").asBoolean());
        assertEquals(3, result.path("sources").size());
        assertEquals(3, count("select count(*) from quiz_question_versions where company_id=? and status='DRAFT'", company));
        assertEquals(0, count("select count(*) from quiz_questions where company_id=? and published_version_no is not null", company));
        assertEquals(100, count("select input_tokens from ai_task_runs where task_id=?", task));
        assertEquals(60, count("select output_tokens from ai_task_runs where task_id=?", task));
        assertEquals(3, count("select count(*) from admin_audit_events where actor_user_id=? and action='QUIZ_AI_DRAFT_CREATED'", user));
    }

    @Test
    void acceptsUppercaseUuidAndReplaysEvenWhenProviderIsDisabled() throws Exception {
        String id = key();
        String first = json(post(body(3), id.toUpperCase(Locale.ROOT)).andExpect(status().isAccepted())).path("data").path("taskId").asText();
        when(client.ready()).thenReturn(false);
        var replay = json(post(body(3), id).andExpect(status().isAccepted()));
        assertEquals(first, replay.path("data").path("taskId").asText());
        assertEquals(1, count("select count(*) from ai_tasks where company_id=?", company));
        verify(client, never()).generate(any());
    }

    @Test
    void changedBodyAndExpiredIdempotencyKeysCannotCreateAnotherJob() throws Exception {
        String id = key();
        post(body(3), id).andExpect(status().isAccepted());
        post(body(2), id).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
        jdbc.update("update api_idempotency_requests set created_at='1970-01-01', expires_at='1970-01-02' where user_id=?", user);
        post(body(3), id).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_EXPIRED"));
        assertEquals(1, count("select count(*) from ai_tasks where company_id=?", company));
    }

    @Test
    void admitsOnlyOneActiveJobPerCompanyAndRequiresAdmin() throws Exception {
        start(3);
        post(body(3), key()).andExpect(status().isTooManyRequests());
        jdbc.update("update users set role='USER' where id=?", user);
        post(body(3), key()).andExpect(status().isForbidden());
        mvc.perform(get("/v1/admin/quiz-generation-tasks/" + key())).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsForeignSourcesDuplicateReportTypesAndEmptyBodies() throws Exception {
        var ids = List.of(versions.getFirst(), 900000000L);
        post(mapper.writeValueAsString(Map.of("disclosureVersionIds", ids)), key()).andExpect(status().isNotFound());
        long secondAnnual = source("ANNUAL");
        post(mapper.writeValueAsString(Map.of("disclosureVersionIds", List.of(versions.getFirst(), secondAnnual))), key())
                .andExpect(status().isBadRequest());
        post("{}", key()).andExpect(status().isBadRequest());
        assertEquals(0, count("select count(*) from ai_tasks where company_id=?", company));
    }

    @Test
    void insufficientGoodQuestionsAreReportedWithoutPadding() throws Exception {
        var result = drain(start(100));
        assertEquals(3, result.path("createdCount").asInt());
        assertFalse(result.path("targetReached").asBoolean());
        assertEquals("INSUFFICIENT_VALID_QUESTIONS", result.path("shortfallReason").asText());
    }

    @Test
    void invalidQuotesAreRejectedAndCannotReachTheBank() throws Exception {
        doAnswer(invocation -> {
            Lease lease = invocation.getArgument(0);
            var s = lease.input().primary().getFirst();
            return new Output(mapper.createArrayNode().add(mapper.valueToTree(
                    QuizGenerationModelTest.question(s.sectionId(), s.disclosureVersionId(), "원문에 없는 인용문"))), metadata());
        }).when(client).generate(any());
        var result = drain(start(3));
        assertEquals("FAILED", result.path("status").asText());
        assertEquals("NO_VALID_QUESTIONS", result.path("errorCode").asText());
        assertEquals(1, result.path("rejectedCount").asInt());
        assertEquals(0, count("select count(*) from quiz_questions where company_id=?", company));
    }

    @Test
    void oneQuestionCanRetainEvidenceFromAllThreeReports() throws Exception {
        doAnswer(invocation -> {
            Lease lease = invocation.getArgument(0);
            var sections = lease.input().primary();
            var q = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(QuizGenerationModelTest.question(
                    sections.getFirst().sectionId(), sections.getFirst().disclosureVersionId(), ORIGINAL));
            var evidence = mapper.createArrayNode();
            for (var s : sections) evidence.add(mapper.valueToTree(Map.of("sectionId", s.sectionId(),
                    "disclosureVersionId", s.disclosureVersionId(), "quote", ORIGINAL)));
            q.set("evidence", evidence);
            return new Output(mapper.createArrayNode().add(q), metadata());
        }).when(client).generate(any());
        var result = drain(start(1));
        assertEquals(1, result.path("createdCount").asInt());
        assertEquals(3, count("select count(*) from quiz_evidence where company_id=?", company));
    }

    @Test
    void changingSourceStatusDuringHttpPreventsSavingAnything() throws Exception {
        doAnswer(invocation -> {
            jdbc.update("update disclosure_versions set status='SUSPENDED' where id=?", versions.getFirst());
            return output(invocation.getArgument(0));
        }).when(client).generate(any());
        var result = drain(start(3));
        assertEquals("SOURCE_UNAVAILABLE", result.path("errorCode").asText());
        assertEquals(0, count("select count(*) from quiz_generation_candidates where company_id=?", company));
        assertEquals(0, count("select count(*) from quiz_questions where company_id=?", company));
    }

    @Test
    void revokedAdminCannotContinueAnAcceptedJob() throws Exception {
        String task = start(3);
        jdbc.update("update users set role='USER' where id=?", user);
        allowClaim(); worker.runOnce();
        assertEquals("AUTHORIZATION_CHANGED", jdbc.queryForObject("select last_error_code from ai_tasks where id=?", String.class, task));
        verify(client, never()).generate(any());
    }

    @Test
    void transientHttpFailureUsesPersistedRetryDelayAndThenCompletes() throws Exception {
        doThrow(new Failure("AI_HTTP_429", true, 120, metadata()))
                .doAnswer(invocation -> output(invocation.getArgument(0))).when(client).generate(any());
        String task = start(3);
        allowClaim(); worker.runOnce();
        assertEquals("PROCESSING", repository.result(task).status());
        var available = jdbc.queryForObject("select available_at from ai_tasks where id=?", LocalDateTime.class, task);
        assertTrue(Objects.requireNonNull(available).toInstant(ZoneOffset.UTC).isAfter(clock.instant().plusSeconds(115)));
        var global = jdbc.queryForObject("select next_call_at from quiz_generation_control where id=1", LocalDateTime.class);
        assertEquals(available, global);
        allowClaim(); worker.runOnce();
        verify(client, times(1)).generate(any());
        jdbc.update("update ai_tasks set available_at='1970-01-01' where id=?", task);
        assertEquals("SUCCEEDED", drain(task).path("status").asText());
        assertEquals(2, count("select count(*) from ai_task_runs where task_id=?", task));
    }

    @Test
    void retriesAreBoundedAndQuotaErrorsFailImmediately() throws Exception {
        doThrow(new Failure("AI_CONNECTION_ERROR", true)).when(client).generate(any());
        String task = start(3);
        for (int i = 0; i < 3; i++) {
            jdbc.update("update ai_tasks set available_at='1970-01-01' where id=?", task);
            allowClaim(); worker.runOnce();
        }
        assertEquals("FAILED", repository.result(task).status());
        verify(client, times(3)).generate(any());
        doThrow(new Failure("AI_QUOTA_EXCEEDED", false)).when(client).generate(any());
        String next = start(3);
        allowClaim(); worker.runOnce();
        assertEquals("FAILED", repository.result(next).status());
        assertEquals(1, count("select count(*) from ai_task_runs where task_id=?", next));
    }

    @Test
    void staleWorkerCannotWriteAfterItsLeaseWasReclaimed() throws Exception {
        String task = start(3);
        var tx = new TransactionTemplate(manager);
        Lease old = tx.execute(s -> repository.claim(clock.instant()));
        assertNotNull(old);
        jdbc.update("update ai_tasks set lease_until='1970-01-01' where id=?", task);
        allowClaim();
        Lease current = tx.execute(s -> repository.claim(clock.instant()));
        assertNotNull(current);
        var valid = QuizGeneration.validate(output(old).questions(), company, old.input(), old.questionsPerBatch());
        tx.executeWithoutResult(s -> repository.save(old, valid, metadata(), clock.instant()));
        assertEquals(0, count("select count(*) from quiz_questions where company_id=?", company));
        tx.executeWithoutResult(s -> repository.save(current, valid, metadata(), clock.instant()));
        assertEquals("SUCCEEDED", repository.result(task).status());
        assertEquals(1, count("select count(*) from ai_task_runs where task_id=? and error_code='LEASE_EXPIRED'", task));
    }

    @Test
    void finalBankFailureRollsBackQuestionsOptionsEvidenceAndProgressTogether() throws Exception {
        String task = start(3);
        var calls = new AtomicInteger();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (calls.incrementAndGet() == 2) throw new TransientDataAccessResourceException("rollback-test");
            return result;
        }).when(bank).createVersion(anyLong(), any(), any(), any());
        allowClaim();
        assertThrows(TransientDataAccessResourceException.class, worker::runOnce);
        assertEquals(0, count("select count(*) from quiz_questions where company_id=?", company));
        assertEquals(0, count("select count(*) from quiz_generation_candidates where company_id=?", company));
        assertEquals(0, repository.result(task).processedBatches());
        reset(bank);
        jdbc.update("update ai_tasks set lease_until='1970-01-01' where id=?", task);
        assertEquals(3, drain(task).path("createdCount").asInt());
    }

    @Test
    void rerunningTheSameSourcesCannotDuplicateExistingQuestions() throws Exception {
        assertEquals(3, drain(start(3)).path("createdCount").asInt());
        var result = drain(start(3));
        assertEquals("FAILED", result.path("status").asText());
        assertEquals("NO_VALID_QUESTIONS", result.path("errorCode").asText());
        assertEquals(3, count("select count(*) from quiz_questions where company_id=?", company));
    }

    @Test
    void reachesOneHundredAcrossAllBatchesAndNeverGeneratesDuringUserPlay() throws Exception {
        long version = versions.getFirst();
        jdbc.update("delete from disclosure_sections where disclosure_version_id=?", version);
        jdbc.batchUpdate("insert into disclosure_sections(disclosure_version_id, company_id, section_key, content_text) values (?, ?, ?, ?)",
                java.util.stream.IntStream.range(0, 1000).boxed().toList(), 100, (s, i) -> {
                    s.setLong(1, version); s.setLong(2, company); s.setString(3, "block-" + i);
                    s.setString(4, ORIGINAL + "공시 본문 ".repeat(90));
                });
        var result = drain(start(100));
        assertEquals(100, result.path("createdCount").asInt());
        assertEquals(result.path("totalBatches").asInt(), result.path("processedBatches").asInt());
        assertEquals(100, count("select count(*) from quiz_questions where company_id=?", company));
        clearInvocations(client);
        jdbc.update("update companies set is_service_company=true where id=?", company);
        jdbc.update("update disclosure_versions set status='PUBLISHED' where company_id=?", company);
        jdbc.update("insert into user_companies(user_id, company_id, active_slot, custom_job_name) values (?, ?, 1, '개발')", user, company);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/v1/companies/" + company + "/quiz-sets/today").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("QUIZ_BANK_INSUFFICIENT"));
        verify(client, never()).generate(any());
    }

    @Test
    void concurrentRequestsWithTheSameKeyRecoverOneTask() throws Exception {
        String id = key(), body = body(3);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            Callable<String> action = () -> {
                ready.countDown(); assertTrue(go.await(5, TimeUnit.SECONDS));
                return json(post(body, id).andExpect(status().isAccepted())).path("data").path("taskId").asText();
            };
            var first = pool.submit(action); var second = pool.submit(action);
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
            assertEquals(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        }
        assertEquals(1, count("select count(*) from ai_tasks where company_id=?", company));
    }

    @Test
    void twoWorkersCannotIssueConcurrentCallsForOneJob() throws Exception {
        String task = start(3);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return output(invocation.getArgument(0));
        }).when(client).generate(any());
        try (var pool = Executors.newSingleThreadExecutor()) {
            var first = pool.submit(worker::runOnce);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                allowClaim();
                worker.runOnce();
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
        }
        verify(client, times(1)).generate(any());
        assertEquals("SUCCEEDED", repository.result(task).status());
    }

    @Test
    void invalidatedSourceBeforeExecutionDoesNotSpendAnAiCall() throws Exception {
        String task = start(3);
        jdbc.update("update disclosure_versions set status='SUSPENDED' where id=?", versions.getFirst());
        allowClaim(); worker.runOnce();
        assertEquals("SOURCE_UNAVAILABLE", repository.result(task).errorCode());
        verify(client, never()).generate(any());
    }

    @Test
    void disabledGenerationRejectsNewTasksWithoutPersistingAnIdempotencyKey() throws Exception {
        when(client.ready()).thenReturn(false);
        post(body(3), key()).andExpect(status().isServiceUnavailable());
        assertEquals(0, count("select count(*) from ai_tasks where company_id=?", company));
        assertEquals(0, count("select count(*) from api_idempotency_requests where user_id=?", user));
    }

    private JsonNode drain(String task) throws Exception {
        for (int i = 0; i < 1000 && "PROCESSING".equals(repository.result(task).status()); i++) {
            allowClaim(); worker.runOnce();
        }
        return json(mvc.perform(get("/v1/admin/quiz-generation-tasks/" + task)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk())).path("data");
    }
    private String start(int count) throws Exception {
        return json(post(body(count), key()).andExpect(status().isAccepted())).path("data").path("taskId").asText();
    }
    private String body(int count) { return mapper.writeValueAsString(Map.of("disclosureVersionIds", versions, "questionCount", count)); }
    private ResultActions post(String body, String key) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/v1/admin/companies/" + company + "/quiz-generation-tasks")
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                .contentType("application/json").content(body));
    }
    private JsonNode json(ResultActions result) throws Exception { return mapper.readTree(result.andReturn().getResponse().getContentAsString()); }
    private void allowClaim() { jdbc.update("update quiz_generation_control set next_call_at='1970-01-01' where id=1"); }
    private Output output(Lease lease) {
        assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                "AI 호출 중에는 DB 트랜잭션을 유지하지 않는다.");
        var rows = mapper.createArrayNode();
        for (var s : lease.input().primary().stream().limit(lease.questionsPerBatch()).toList()) {
            var q = (tools.jackson.databind.node.ObjectNode) mapper.valueToTree(QuizGenerationModelTest.question(
                    s.sectionId(), s.disclosureVersionId(), ORIGINAL));
            q.put("question", "저장된 공시 " + s.sectionId() + "번 본문에서 설명하는 연구개발 방향은 무엇인가요?");
            rows.add(q);
        }
        return new Output(rows, metadata());
    }
    private static Metadata metadata() { return new Metadata("req-quiz44", "gpt-6-luna", 100, 60); }
    private long source(String type) {
        long filing = insert("""
                insert into disclosures(company_id, receipt_no, report_name, report_type, disclosed_on, source_url)
                values (?, ?, ?, ?, '2026-03-01', 'https://dart.fss.or.kr/')
                """, company, key().replace("-", "").substring(0, 20), type + " 보고서", type);
        long version = insert("""
                insert into disclosure_versions(disclosure_id, company_id, version_no, status, content_hash, extractor_version)
                values (?, ?, 1, 'DRAFT', ?, 'quiz44-test')
                """, filing, company, key().replace("-", "").repeat(2));
        insert("""
                insert into disclosure_sections(disclosure_version_id, company_id, section_key, heading, content_text)
                values (?, ?, 's1', '연구개발활동', ?)
                """, version, company, ORIGINAL);
        return version;
    }
    private long count(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(c -> {
            var s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            return s;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    private static String key() { return UUID.randomUUID().toString(); }
}