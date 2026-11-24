package com.dadot.server.domain.interview;

import com.dadot.server.domain.interview.model.InterviewGeneration;
import com.dadot.server.domain.interview.model.InterviewGeneration.*;
import com.dadot.server.domain.interview.repository.InterviewGenerationRepository;
import com.dadot.server.domain.interview.repository.InterviewReadRepository;
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
    @Autowired org.springframework.context.ApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired Clock clock;
    @Autowired AccessTokenIssuer tokens;
    @Autowired InterviewGenerationWorker worker;
    @Autowired InterviewReadRepository reads;
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
    void questionEvidenceIncludesStoredDatesWithoutChangingOriginalFields() throws Exception {
        long question = generatedQuestionForEvidence();
        long disclosure = evidenceDisclosure(question);
        jdbc.update("""
                update disclosures set disclosed_on = '2026-03-12',
                    period_start = '2025-01-01', period_end = '2025-12-31'
                where id = ?
                """, disclosure);

        var expected = jdbc.queryForMap("""
                select d.report_name, d.source_url, s.heading, e.quotation
                from interview_question_evidence e
                join disclosure_sections s on s.id = e.section_id
                join disclosure_versions v on v.id = s.disclosure_version_id
                join disclosures d on d.id = v.disclosure_id
                where e.question_id = ?
                """, question);
        detail(question, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence[0].disclosureId").value(disclosure))
                .andExpect(jsonPath("$.data.evidence[0].reportTitle").value(expected.get("report_name")))
                .andExpect(jsonPath("$.data.evidence[0].sourceUrl").value(expected.get("source_url")))
                .andExpect(jsonPath("$.data.evidence[0].sectionTitle").value(expected.get("heading")))
                .andExpect(jsonPath("$.data.evidence[0].quote").value(expected.get("quotation")))
                .andExpect(jsonPath("$.data.evidence[0].isSuperseded").value(false))
                .andExpect(jsonPath("$.data.evidence[0].publishedAt").value("2026-03-12"))
                .andExpect(jsonPath("$.data.evidence[0].periodStart").value("2025-01-01"))
                .andExpect(jsonPath("$.data.evidence[0].periodEnd").value("2025-12-31"));
        assertEquals(0, count("select count(*) from interview_submissions where user_id = ?", user));
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "START_ONLY", "END_ONLY"})
    void unknownPeriodDatesRemainNullInsteadOfBeingInvented(String mode) throws Exception {
        long question = generatedQuestionForEvidence();
        jdbc.update("update disclosures set period_start = ?, period_end = ? where id = ?",
                mode.equals("START_ONLY") ? LocalDate.parse("2025-01-01") : null,
                mode.equals("END_ONLY") ? LocalDate.parse("2025-12-31") : null,
                evidenceDisclosure(question));
        var evidence = data(detail(question, token).andExpect(status().isOk())).get("evidence").get(0);
        assertEquals("2026-10-20", evidence.get("publishedAt").asString());
        if (mode.equals("START_ONLY")) {
            assertEquals("2025-01-01", evidence.get("periodStart").asString());
        } else {
            assertTrue(evidence.get("periodStart").isNull());
        }
        if (mode.equals("END_ONLY")) {
            assertEquals("2025-12-31", evidence.get("periodEnd").asString());
        } else {
            assertTrue(evidence.get("periodEnd").isNull());
        }
    }

    @Test
    void eachEvidenceKeepsItsOwnDisclosureDatesAndOrder() throws Exception {
        long question = generatedQuestionForEvidence();
        jdbc.update("""
                insert into interview_question_evidence(question_id, user_id, company_id, section_id, quotation)
                select ?, ?, ?, s.id, s.content_text
                from disclosure_sections s
                where s.company_id = ? and not exists (
                    select 1 from interview_question_evidence e
                    where e.question_id = ? and e.section_id = s.id)
                order by s.id limit 1
                """, question, user, company, company, question);
        var rows = jdbc.queryForList("""
                select d.id as disclosure_id, s.id as section_id
                from interview_question_evidence e
                join disclosure_sections s on s.id = e.section_id
                join disclosure_versions v on v.id = s.disclosure_version_id
                join disclosures d on d.id = v.disclosure_id
                where e.question_id = ? order by e.section_id
                """, question);
        assertEquals(2, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            jdbc.update("update disclosures set disclosed_on = ?, period_start = ?, period_end = ? where id = ?",
                    LocalDate.of(2026, i + 1, 15), LocalDate.of(2025, i + 1, 1),
                    LocalDate.of(2025, i + 1, 28), rows.get(i).get("disclosure_id"));
        }
        var evidence = data(detail(question, token).andExpect(status().isOk())).get("evidence");
        assertEquals(2, evidence.size());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(((Number) rows.get(i).get("disclosure_id")).longValue(),
                    evidence.get(i).get("disclosureId").longValue());
            assertEquals(LocalDate.of(2026, i + 1, 15).toString(), evidence.get(i).get("publishedAt").asString());
            assertEquals(LocalDate.of(2025, i + 1, 1).toString(), evidence.get(i).get("periodStart").asString());
            assertEquals(LocalDate.of(2025, i + 1, 28).toString(), evidence.get(i).get("periodEnd").asString());
        }
    }

    @Test
    void supersededEvidenceRetainsOriginalPublicationDate() throws Exception {
        long question = generatedQuestionForEvidence();
        jdbc.update("""
                update disclosure_versions set status = 'SUPERSEDED'
                where disclosure_id = ?
                """, evidenceDisclosure(question));
        detail(question, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence[0].isSuperseded").value(true))
                .andExpect(jsonPath("$.data.evidence[0].publishedAt").value("2026-10-20"))
                .andExpect(jsonPath("$.data.usage.canSubmit").value(false));
    }

    @Test
    void storesAndReturnsInterpretationWithoutChangingItsOriginalQuote() throws Exception {
        String interpretation = "이 회사는 해외 판매망을 넓힐 계획이에요. 해외 고객에게 제품을 판매하는 내용이에요.";
        doAnswer(invocation -> {
            Lease lease = invocation.getArgument(0);
            Output base = output(lease);
            Quote quote = base.generated().evidence().getFirst();
            return new Output(new Generated(base.generated().question(), List.of(new Quote(
                    quote.disclosureVersionId(), quote.sectionId(), quote.quote(), interpretation))), base.metadata());
        }).when(client).generate(any());
        String task = accepted();
        worker.runOnce();
        long question = question(task);
        var stored = jdbc.queryForMap("select quotation, interpretation from interview_question_evidence where question_id = ?", question);
        assertEquals(interpretation, stored.get("interpretation"));
        detail(question, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence[0].quote").value(stored.get("quotation")))
                .andExpect(jsonPath("$.data.evidence[0].interpretation").value(interpretation));
        detail(question, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence[0].interpretation").value(interpretation));
    }

    @Test
    void existingQuestionWithoutInterpretationKeepsItsOriginalEvidence() throws Exception {
        long question = generatedQuestionForEvidence();
        assertNull(jdbc.queryForObject("select interpretation from interview_question_evidence where question_id = ?", String.class, question));
        String quote = jdbc.queryForObject("select quotation from interview_question_evidence where question_id = ?", String.class, question);
        detail(question, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence[0].quote").value(quote))
                .andExpect(jsonPath("$.data.evidence[0].interpretation").isEmpty());
    }

    @Test
    void interpretationsOfDifferentReportsStayWithTheirOwnQuotesAndSources() throws Exception {
        long question = generatedQuestionForEvidence();
        jdbc.update("update interview_question_evidence set interpretation = ? where question_id = ?",
                "첫 번째 공시 내용을 정리했어요.", question);
        jdbc.update("""
                insert into interview_question_evidence(question_id, user_id, company_id, section_id, quotation, interpretation)
                select ?, ?, ?, s.id, s.content_text, ? from disclosure_sections s
                where s.company_id = ? and not exists(
                    select 1 from interview_question_evidence e where e.question_id = ? and e.section_id = s.id)
                order by s.id limit 1
                """, question, user, company, "두 번째 공시 내용을 정리했어요.", company, question);
        var expected = jdbc.queryForList("""
                select e.quotation, e.interpretation, d.report_name, d.source_url
                from interview_question_evidence e
                join disclosure_sections s on s.id = e.section_id
                join disclosure_versions v on v.id = s.disclosure_version_id
                join disclosures d on d.id = v.disclosure_id
                where e.question_id = ? order by e.section_id
                """, question);
        var evidence = data(detail(question, token).andExpect(status().isOk())).get("evidence");
        assertEquals(2, evidence.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).get("quotation"), evidence.get(i).get("quote").asString());
            assertEquals(expected.get(i).get("interpretation"), evidence.get(i).get("interpretation").asString());
            assertEquals(expected.get(i).get("report_name"), evidence.get(i).get("reportTitle").asString());
            assertEquals(expected.get(i).get("source_url"), evidence.get(i).get("sourceUrl").asString());
        }
    }

    @Test
    void invalidInterpretationDoesNotCreateAQuestionOrEvidenceRow() throws Exception {
        doAnswer(invocation -> {
            Output base = output(invocation.getArgument(0));
            Quote quote = base.generated().evidence().getFirst();
            return new Output(new Generated(base.generated().question(), List.of(new Quote(
                    quote.disclosureVersionId(), quote.sectionId(), quote.quote(), "이 회사는 해외에서"))), base.metadata());
        }).when(client).generate(any());
        String task = accepted();
        worker.runOnce();
        assertEquals(0, count("select count(*) from interview_questions where generation_task_id = ?", task));
        assertEquals(0, count("select count(*) from interview_question_evidence where company_id = ?", company));
        assertEquals("QUESTION_GENERATION_FAILED", jdbc.queryForObject(
                "select last_error_code from ai_tasks where id = ?", String.class, task));
    }

    @Test
    void accountingBoilerplateOnlyIsRejectedBeforeCreatingATaskOrCallingAi() throws Exception {
        jdbc.update("update disclosure_sections set heading=?, content_text=? where company_id=?",
                "연결재무제표 작성기준", "보고기간말 경영진의 판단에 따른 추정치와 가정은 실제 결과와 다를 수 있습니다.", company);
        create(key(), token).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONTENT_UNAVAILABLE"));
        assertEquals(0, count("select count(*) from ai_tasks where company_id=?", company));
        verify(client, never()).generate(any());
    }

    @Test
    void canUseOneBusinessReportWithoutAddingBoilerplateFromTheOtherTwo() throws Exception {
        long annual = count("""
                select v.id from disclosure_versions v join disclosures d on d.id=v.disclosure_id
                where d.company_id=? and d.report_type='ANNUAL'
                """, company);
        jdbc.update("update disclosure_sections set heading=?, content_text=? where company_id=? and disclosure_version_id<>?",
                "연결재무제표 작성기준", "보고기간말 추정치와 가정은 실제 결과와 다를 수 있습니다.", company, annual);
        String task = accepted();
        worker.runOnce();
        assertEquals(3, captured.get().input().sources().size());
        assertEquals(1, captured.get().input().sections().size());
        assertEquals(annual, captured.get().input().sections().getFirst().disclosureVersionId());
        detail(question(task), token).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.evidence.length()").value(1));
    }

    private long generatedQuestionForEvidence() throws Exception {
        String task = accepted();
        worker.runOnce();
        return question(task);
    }

    private long evidenceDisclosure(long question) {
        return count("""
                select v.disclosure_id from interview_question_evidence e
                join disclosure_sections s on s.id = e.section_id
                join disclosure_versions v on v.id = s.disclosure_version_id
                where e.question_id = ? order by e.section_id limit 1
                """, question);
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
    void sharedCompanyReadersDoNotBlockWorkerLoadOrSave() throws Exception {
        String task = accepted();
        var tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
        var claim = tx.execute(status -> repository.claim(clock.instant()));
        assertNotNull(claim);

        long other = account();
        jdbc.update("""
                insert into user_companies(user_id, company_id, active_slot, custom_job_name)
                values (?, ?, 1, '다른 사용자 직무')
                """, other, company);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var reader = executor.submit(() -> tx.executeWithoutResult(status -> {
                reads.preparation(other, company, true);
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("reader timed out");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                tx.executeWithoutResult(status -> {
                    int previous = Objects.requireNonNull(jdbc.queryForObject(
                            "select @@session.innodb_lock_wait_timeout", Integer.class));
                    jdbc.execute("set session innodb_lock_wait_timeout = 1");
                    try {
                        Lease lease = repository.load(claim, clock.instant());
                        assertNotNull(lease);
                        repository.save(claim, output(lease), clock.instant());
                    } finally {
                        jdbc.execute("set session innodb_lock_wait_timeout = " + previous);
                    }
                });
                assertEquals("SUCCEEDED", state(task));
                assertTrue(question(task) > 0);
            } finally {
                release.countDown();
                reader.get(5, TimeUnit.SECONDS);
            }
        }
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

        entry(null)
                .andExpect(jsonPath("$.data.nextAction").value("REVIEW"))
                .andExpect(jsonPath("$.data.questionId").isEmpty());

        error(create(key(), token), 409, "DAILY_LIMIT_EXCEEDED");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void nextQuestionDoesNotReuseAnsweredQuestionWhileRetryRemainsAvailable(int attempts)
            throws Exception {
        String task = accepted();
        worker.runOnce();
        long previous = question(task);
        LocalDate day = clock.instant().atZone(InterviewGeneration.KST).toLocalDate();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            consume(previous, day, 1, attempt, attempt);
        }

        entry(null)
                .andExpect(jsonPath("$.data.nextAction").value("GENERATE"))
                .andExpect(jsonPath("$.data.questionId").isEmpty());
        detail(previous, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usage.canSubmit").value(true))
                .andExpect(jsonPath("$.data.usage.questionRemaining").value(3 - attempts));

        String next = accepted();
        entry(next)
                .andExpect(jsonPath("$.data.nextAction").value("WAIT_GENERATION"))
                .andExpect(jsonPath("$.data.questionId").isEmpty())
                .andExpect(jsonPath("$.data.generationTask.taskId").value(next));
        entry(null)
                .andExpect(jsonPath("$.data.nextAction").value("WAIT_GENERATION"))
                .andExpect(jsonPath("$.data.questionId").isEmpty());

        doAnswer(invocation -> {
            Lease lease = invocation.getArgument(0);
            Output base = output(lease);
            return new Output(new Generated("다음 공시 근거로 해외영업 실행 계획을 설명해 주세요.",
                    base.generated().evidence()), base.metadata());
        }).when(client).generate(any());
        gate();
        worker.runOnce();
        long following = question(next);
        assertNotEquals(previous, following);
        entry(next)
                .andExpect(jsonPath("$.data.nextAction").value("ANSWER_QUESTION"))
                .andExpect(jsonPath("$.data.questionId").value(following));
    }

    @Test
    void nextQuestionAdvancesAcrossThreeDistinctQuestionsAndThenStops() throws Exception {
        LocalDate day = clock.instant().atZone(InterviewGeneration.KST).toLocalDate();
        var questions = new java.util.HashSet<Long>();
        for (int slot = 1; slot <= 3; slot++) {
            String task = accepted();
            doAnswer(invocation -> {
                Lease lease = invocation.getArgument(0);
                Output base = output(lease);
                return new Output(new Generated("공시 근거에 따른 질문 " + lease.taskId(),
                        base.generated().evidence()), base.metadata());
            }).when(client).generate(any());
            gate();
            worker.runOnce();
            long current = question(task);
            assertTrue(questions.add(current));
            entry(task)
                    .andExpect(jsonPath("$.data.nextAction").value("ANSWER_QUESTION"))
                    .andExpect(jsonPath("$.data.questionId").value(current));
            consume(current, day, slot, slot, 1);
            entry(null)
                    .andExpect(jsonPath("$.data.nextAction").value(slot < 3 ? "GENERATE" : "REVIEW"))
                    .andExpect(jsonPath("$.data.questionId").isEmpty());
        }
        assertEquals(3, questions.size());
        error(create(key(), token), 409, "DAILY_LIMIT_EXCEEDED");
        for (long question : questions) {
            detail(question, token)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.usage.canSubmit").value(true))
                    .andExpect(jsonPath("$.data.usage.questionRemaining").value(2));
        }
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


    @Test
    void threeUsersCanHoldLeasesButFourthWaitsUntilAResultIsSaved() throws Exception {
        String a = accepted();
        String b = acceptedForAnotherUser();
        String c = acceptedForAnotherUser();
        String d = acceptedForAnotherUser();
        assertEquals(3, context.getBean(com.dadot.server.domain.interview.support.InterviewGenerationCapacity.class).maxConcurrent());
        var tx = new TransactionTemplate(manager);
        var first = tx.execute(t -> repository.claim(clock.instant()));
        gate();
        var second = tx.execute(t -> repository.claim(clock.instant()));
        assertNotNull(first);
        assertNotNull(second, "다른 사용자의 질문은 첫 AI 응답 전에 실행할 수 있어야 한다");
        assertNotEquals(first.taskId(), second.taskId());
        gate();
        var third = tx.execute(t -> repository.claim(clock.instant()));
        assertNotNull(third);
        assertEquals(c, third.taskId());
        gate();
        assertNull(tx.execute(t -> repository.claim(clock.instant())), "전역 동시 실행 3건 제한");
        assertEquals(3, liveGenerationLeases());
        Lease input = tx.execute(t -> repository.load(first, clock.instant()));
        tx.executeWithoutResult(t -> repository.save(first, output(input), clock.instant()));
        gate();
        var fourth = tx.execute(t -> repository.claim(clock.instant()));
        assertNotNull(fourth);
        assertEquals(d, fourth.taskId());
        assertEquals("SUCCEEDED", state(a));
        assertEquals("PROCESSING", state(b));
        assertEquals(3, liveGenerationLeases());
    }

    @Test
    void concurrentClaimersAcrossInstancesCannotExceedGlobalLimit() throws Exception {
        for (int i = 0; i < 8; i++) {
            if (i == 0) accepted(); else acceptedForAnotherUser();
        }
        try (var pool = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var calls = new ArrayList<java.util.concurrent.Future<InterviewGenerationRepository.Claim>>();
            for (int i = 0; i < 8; i++) calls.add(pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                var tx = new TransactionTemplate(manager);
                return tx.execute(t -> {
                    // 시작 간격만 테스트에서 해제. 같은 control 행 잠금 아래 선점 경쟁을 검증한다.
                    gate();
                    return repository.claim(clock.instant());
                });
            }));
            start.countDown();
            var claimed = new java.util.HashSet<String>();
            for (var call : calls) {
                var claim = call.get(10, TimeUnit.SECONDS);
                if (claim != null) assertTrue(claimed.add(claim.taskId()));
            }
            assertEquals(3, claimed.size());
            assertEquals(3, liveGenerationLeases());
            assertEquals(3, count("select count(*) from ai_task_runs r join ai_tasks t on t.id=r.task_id where t.company_id=?", company));
        }
    }

    @Test
    void concurrentClaimersCannotRunTheSameTaskTwice() throws Exception {
        accepted();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var calls = new ArrayList<java.util.concurrent.Future<InterviewGenerationRepository.Claim>>();
            for (int i = 0; i < 4; i++) calls.add(pool.submit(() ->
                    new TransactionTemplate(manager).execute(t -> { gate(); return repository.claim(clock.instant()); })));
            int claimed = 0;
            for (var call : calls) if (call.get(10, TimeUnit.SECONDS) != null) claimed++;
            assertEquals(1, claimed);
            assertEquals(1, liveGenerationLeases());
        }
    }

    @Test
    void sharedCallStartIntervalStillAppliesWithMultipleAvailableSlots() throws Exception {
        accepted(); acceptedForAnotherUser();
        var tx = new TransactionTemplate(manager);
        var now = clock.instant();
        assertNotNull(tx.execute(t -> repository.claim(now)));
        assertNull(tx.execute(t -> repository.claim(now)), "새 슬롯이 있어도 기존 초당 접수 간격 유지");
        assertNotNull(tx.execute(t -> repository.claim(now.plusSeconds(2))));
    }

    @Test
    void outstandingAdmissionCountsQueuedAndRunningTasksTogether() throws Exception {
        String first = accepted();
        var tx = new TransactionTemplate(manager);
        assertNotNull(tx.execute(t -> repository.claim(clock.instant())));
        for (int i = 0; i < 19; i++) jdbc.update("""
                insert into ai_tasks(id,user_id,company_id,type,status,context_id,policy_version_id,
                    request_key_hash,request_body_hmac)
                select ?,user_id,company_id,type,'PROCESSING',context_id,policy_version_id,?,request_body_hmac
                from ai_tasks where id=?
                """, key(), InterviewGeneration.sha256(key()), first);
        long other = account();
        jdbc.update("insert into user_companies(user_id,company_id,active_slot,custom_job_name) values (?,?,1,'해외영업')", other, company);
        error(create(key(), login(other)), 503, "SERVICE_UNAVAILABLE");
        assertEquals(20, count("select count(*) from ai_tasks where company_id=?", company));
        assertEquals(1, liveGenerationLeases());
    }

    @Test
    void threeRealWorkersOverlapAiCallsWithoutKeepingTransactionsOpen() throws Exception {
        String a = accepted();
        String b = acceptedForAnotherUser();
        String c = acceptedForAnotherUser();
        var firstEntered = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var allEntered = new CountDownLatch(3);
        var release = new CountDownLatch(1);
        var active = new java.util.concurrent.atomic.AtomicInteger();
        var maximum = new java.util.concurrent.atomic.AtomicInteger();
        var txOpen = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> {
            Lease lease = invocation.getArgument(0);
            if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) txOpen.set(true);
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            firstEntered.countDown();
            if (current == 2) secondEntered.countDown();
            allEntered.countDown();
            try { assertTrue(release.await(15, TimeUnit.SECONDS)); return output(lease); }
            finally { active.decrementAndGet(); }
        }).when(client).generate(any());
        try (var pool = Executors.newFixedThreadPool(4)) {
            var calls = new ArrayList<java.util.concurrent.Future<?>>();
            try {
                calls.add(pool.submit(worker::runOnce));
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
                gate();
                calls.add(pool.submit(worker::runOnce));
                assertTrue(secondEntered.await(5, TimeUnit.SECONDS));
                gate();
                calls.add(pool.submit(worker::runOnce));
                assertTrue(allEntered.await(5, TimeUnit.SECONDS));
                assertEquals(3, maximum.get());
                assertFalse(txOpen.get());
                assertEquals(3, liveGenerationLeases());
                gate();
                pool.submit(worker::runOnce).get(3, TimeUnit.SECONDS);
                verify(client, times(3)).generate(any());
            } finally {
                release.countDown();
                for (var call : calls) call.get(5, TimeUnit.SECONDS);
            }
        }
        assertEquals("SUCCEEDED", state(a));
        assertEquals("SUCCEEDED", state(b));
        assertEquals("SUCCEEDED", state(c));
        assertEquals(3, count("select count(*) from interview_questions where company_id=?", company));
    }

    @Test
    void configuredSingleGlobalSlotStillBlocksOtherInstances() throws Exception {
        accepted(); acceptedForAnotherUser();
        var single = capacityRepository(1, 20);
        var tx = new TransactionTemplate(manager);
        assertNotNull(tx.execute(t -> single.claim(clock.instant())));
        gate();
        assertNull(tx.execute(t -> single.claim(clock.instant())));
        assertEquals(1, liveGenerationLeases());
    }

    @Test
    void configuredOutstandingLimitRejectsAtThreeWithoutChangingHourlyLimit() throws Exception {
        accepted(); acceptedForAnotherUser(); acceptedForAnotherUser();
        var limited = capacityRepository(2, 3);
        var tx = new TransactionTemplate(manager);
        var error = assertThrows(com.dadot.server.global.exception.BusinessException.class,
                () -> tx.executeWithoutResult(t -> limited.admission(user, clock.instant())));
        assertEquals(com.dadot.server.global.exception.ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCode());
        assertEquals(3, count("select count(*) from ai_tasks where company_id=?", company));
    }

    private InterviewGenerationRepository capacityRepository(int concurrent, int outstanding) {
        return new InterviewGenerationRepository(jdbc, mapper,
                context.getBean(com.dadot.server.domain.auth.support.VerificationCrypto.class),
                context.getBean(com.dadot.server.domain.interview.repository.InterviewSourceRepository.class),
                reads, new com.dadot.server.domain.interview.support.InterviewGenerationCapacity(concurrent, outstanding));
    }

    private String acceptedForAnotherUser() throws Exception {
        long another = account();
        jdbc.update("insert into user_companies(user_id,company_id,active_slot,custom_job_name) values (?,?,1,'해외영업')", another, company);
        return data(create(key(), login(another)).andExpect(status().isAccepted())).get("taskId").asText();
    }

    private long liveGenerationLeases() {
        return count("""
                select count(*) from ai_tasks where company_id=? and type='INTERVIEW_GENERATION'
                and status='PROCESSING' and lease_until>utc_timestamp(6)
                """, company);
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