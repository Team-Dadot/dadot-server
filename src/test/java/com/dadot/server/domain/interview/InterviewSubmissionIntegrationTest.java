package com.dadot.server.domain.interview;

import com.dadot.server.domain.interview.model.InterviewGeneration;
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
class InterviewSubmissionIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired AccessTokenIssuer tokens;
    @MockitoSpyBean Clock clock;
    @MockitoSpyBean InterviewSubmissionRequestStore requests;
    @MockitoSpyBean OpenAiInterviewClient client;

    private final List<Long> users = new ArrayList<>();
    private long user, company, question, section, evaluationPolicy;
    private String token;
    private boolean safe;

    @BeforeEach
    void setup() {
        safe = false;
        assertEquals("dadot_test_db", jdbc.queryForObject("select database()", String.class));
        safe = true;
        reset(clock, requests, client);
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
    }

    @AfterEach
    void cleanup() {
        if (!safe) return;
        reset(clock, requests, client);
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
    void draftSaveUpdateAndEmptySaveDoNotConsumeQuota() throws Exception {
        draft(question, "초안", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft.version").value(1));
        draft(question, "수정", 1L).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft.version").value(2));
        draft(question, "", 2L).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft.text").value(""))
                .andExpect(jsonPath("$.data.draft.version").value(3));
        assertEquals(0, count("select count(*) from interview_submissions where user_id = ?", user));
        assertEquals(0, count("select count(*) from ai_tasks where user_id = ? and type = 'INTERVIEW_EVALUATION'", user));
        verifyNoInteractions(client);
    }

    @Test
    void draftCounterSurvivesSubmissionAndPreventsAba() throws Exception {
        draft(question, "초안", null).andExpect(status().isOk());
        submit(question, key(), "{\"text\":\"제출\",\"draftVersion\":1}").andExpect(status().isAccepted());
        assertEquals(0, count("select count(*) from interview_drafts where question_id = ?", question));
        draft(question, "새 초안", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft.version").value(2));
        error(draft(question, "오래된 요청", 1L), 409, "VERSION_CONFLICT");
    }

    @Test
    void omittedOrNullDraftVersionPreservesDraft() throws Exception {
        draft(question, "보관할 초안", null).andExpect(status().isOk());
        submit(question, key(), "{\"text\":\"직접 제출\"}").andExpect(status().isAccepted());
        finish();
        submit(question, key(), "{\"text\":\"다시 제출\",\"draftVersion\":null}").andExpect(status().isAccepted());
        assertEquals("보관할 초안", jdbc.queryForObject("select draft_text from interview_drafts where question_id = ?", String.class, question));
    }

    @Test
    void staleOrMissingDraftRollsBackAllSubmissionState() throws Exception {
        error(submit(question, key(), "{\"text\":\"답변\",\"draftVersion\":1}"), 409, "VERSION_CONFLICT");
        draft(question, "초안", null).andExpect(status().isOk());
        error(submit(question, key(), "{\"text\":\"답변\",\"draftVersion\":2}"), 409, "VERSION_CONFLICT");
        assertNoSubmissions();
    }

    @Test
    void submissionWithoutExperiencesQueuesOnlyAndReturnsOriginalAnswer() throws Exception {
        material("COVER_LETTER", "자소서", "사용 금지");
        JsonNode result = data(submit(question, key(), "{\"text\":\"  최종 답변😀\\n\"}").andExpect(status().isAccepted()));
        assertEquals(1, result.get("attemptNumber").asInt());
        assertEquals("TASK_PROCESSING", result.get("usage").get("blockedReason").asText());
        assertEquals(1, result.get("usage").get("daily").get("used").asInt());
        assertEquals(0, count("select count(*) from ai_context_materials where user_id = ?", user));
        detail(result.get("attemptId").asLong(), token).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.text").value("  최종 답변😀\n"))
                .andExpect(jsonPath("$.data.evaluationStatus").value("PROCESSING"));
        assertEquals(0, count("select count(*) from ai_task_runs where task_id = ?", result.get("taskId").asText()));
        verifyNoInteractions(client);
    }

    @Test
    void pinsAllLatestExperienceVersionsAndOriginalQuestionJob() throws Exception {
        long first = material("EXPERIENCE", "경험1", "초기");
        material("EXPERIENCE", "경험2", "전체 포함");
        material("COVER_LETTER", "자소서", "제외");
        long latest = insert("""
                insert into material_versions(material_id, user_id, material_type, version_no, title, content_text)
                values (?, ?, 'EXPERIENCE', 2, '경험1 수정', '제출 전 수정')
                """, first, user);
        jdbc.update("update personal_materials set current_version_no = 2 where id = ?", first);
        jdbc.update("update user_companies set custom_job_name = '프론트엔드' where user_id = ?", user);
        JsonNode result = accepted(question);
        String context = jdbc.queryForObject("select context_id from interview_attempts where id = ?", String.class, result.get("attemptId").asLong());
        assertEquals(2, count("select count(*) from ai_context_materials where context_id = ?", context));
        assertEquals(1, count("select count(*) from ai_context_materials where context_id = ? and material_version_id = ?", context, latest));
        assertEquals("백엔드", jdbc.queryForObject("select job_name_snapshot from ai_contexts where id = ?", String.class, context));
        jdbc.update("update personal_materials set current_version_no = 1 where id = ?", first);
        assertEquals(1, count("select count(*) from ai_context_materials where context_id = ? and material_version_id = ?", context, latest));
    }

    @Test
    void missingOrInvalidEvaluationPolicyDoesNotConsumeQuota() throws Exception {
        jdbc.update("update interview_policies set active_version_no = null where type = 'EVALUATION' and company_id = ?", company);
        error(submit(question, key(), "{\"text\":\"답변\"}"), 409, "POLICY_NOT_CONFIGURED");
        jdbc.update("update interview_policies set active_version_no = 1 where type = 'EVALUATION' and company_id = ?", company);
        jdbc.update("update interview_policy_versions set rule_config = '{\"materialTypes\":[\"COVER_LETTER\"],\"aggregationRule\":\"SUM_4_X_25_THRESHOLD_80\"}' where id = ?", evaluationPolicy);
        error(submit(question, key(), "{\"text\":\"답변\"}"), 409, "POLICY_NOT_CONFIGURED");
        assertNoSubmissions();
    }

    @Test
    void missingLatestExperienceVersionFailsWithoutPartialSnapshot() throws Exception {
        insert("insert into personal_materials(user_id, type) values (?, 'EXPERIENCE')", user);
        error(submit(question, key(), "{\"text\":\"답변\"}"), 503, "SERVICE_UNAVAILABLE");
        assertNoSubmissions();
        assertEquals(1, count("select count(*) from ai_contexts where user_id = ?", user));
    }

    @Test
    void replaysSameKeyAfterMidnightAndCompanyExclusion() throws Exception {
        String key = key();
        String body = "{\"text\":\"답변\",\"draftVersion\":null}";
        JsonNode first = data(submit(question, key, body).andExpect(status().isAccepted()));
        finish();
        doReturn(Instant.parse("2026-10-31T15:01:00Z")).when(clock).instant();
        jdbc.update("update user_companies set active_slot = null, excluded_at = current_timestamp(6) where user_id = ?", user);
        jdbc.update("update interview_policies set active_version_no = null where type = 'EVALUATION' and company_id = ?", company);
        assertEquals(first, data(submit(question, key, "{\"draftVersion\":null,\"text\":\"답변\"}").andExpect(status().isAccepted())));
        assertEquals(1, count("select count(*) from interview_submissions where user_id = ?", user));
        assertEquals("2026-10-31", first.get("usage").get("daily").get("businessDate").asText());
    }

    @Test
    void sameKeyDifferentTextOrOmittedNullConflicts() throws Exception {
        String key = key();
        submit(question, key, "{\"text\":\"답변\"}").andExpect(status().isAccepted());
        error(submit(question, key, "{\"text\":\"다른 답변\"}"), 409, "IDEMPOTENCY_CONFLICT");
        error(submit(question, key, "{\"text\":\"답변\",\"draftVersion\":null}"), 409, "IDEMPOTENCY_CONFLICT");
    }

    @Test
    void expiredKeyNeverCreatesAnotherAttempt() throws Exception {
        String key = key();
        submit(question, key, "{\"text\":\"답변\"}").andExpect(status().isAccepted());
        jdbc.update("update api_idempotency_requests set created_at = '2026-10-29', expires_at = '2026-10-30' where user_id = ?", user);
        error(submit(question, key, "{\"text\":\"답변\"}"), 409, "IDEMPOTENCY_KEY_EXPIRED");
        assertEquals(1, count("select count(*) from interview_submissions where user_id = ?", user));
    }

    @Test
    void deletedAttemptIsNotRecreatedByReplay() throws Exception {
        String key = key();
        JsonNode result = data(submit(question, key, "{\"text\":\"답변\"}").andExpect(status().isAccepted()));
        jdbc.update("delete from interview_attempts where id = ?", result.get("attemptId").asLong());
        error(submit(question, key, "{\"text\":\"답변\"}"), 404, "RESOURCE_NOT_FOUND");
        error(detail(result.get("attemptId").asLong(), token), 404, "RESOURCE_NOT_FOUND");
        assertEquals(1, count("select count(*) from interview_submissions where user_id = ?", user));
    }

    @Test
    void deletedQuestionAndOtherOwnersAreHidden() throws Exception {
        long other = account();
        String otherToken = login(other);
        JsonNode result = accepted(question);
        error(detail(result.get("attemptId").asLong(), otherToken), 404, "RESOURCE_NOT_FOUND");
        mvc.perform(put(draftPath(question)).header("Authorization", "Bearer " + otherToken)
                        .contentType("application/json").content("{\"text\":\"초안\",\"version\":null}"))
                .andExpect(status().isNotFound());
        jdbc.update("update interview_question_keys set deleted_at = current_timestamp(6) where id = ?", question);
        error(draft(question, "초안", null), 404, "RESOURCE_NOT_FOUND");
        error(detail(result.get("attemptId").asLong(), token), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void excludedCompanyBlocksWritesButAllowsPastAnswerRead() throws Exception {
        JsonNode result = accepted(question);
        finish();
        jdbc.update("update user_companies set active_slot = null, excluded_at = current_timestamp(6) where user_id = ?", user);
        error(draft(question, "초안", null), 409, "COMPANY_INACTIVE");
        error(submit(question, key(), "{\"text\":\"답변\"}"), 409, "COMPANY_INACTIVE");
        detail(result.get("attemptId").asLong(), token).andExpect(status().isOk());
    }

    @Test
    void correctedOrSuspendedEvidenceBlocksNewSubmission() throws Exception {
        jdbc.update("update disclosure_versions set status = 'SUSPENDED' where company_id = ?", company);
        error(submit(question, key(), "{\"text\":\"답변\"}"), 409, "CONTENT_UNAVAILABLE");
        assertNoSubmissions();
    }

    @Test
    void processingEvaluationBlocksAnotherSubmissionWithoutCharging() throws Exception {
        accepted(question);
        error(submit(question, key(), "{\"text\":\"다음 답변\"}"), 409, "TASK_PROCESSING");
        assertEquals(1, count("select count(*) from interview_submissions where user_id = ?", user));
    }

    @Test
    void lifetimeQuotaSurvivesNewDayAndBodyDeletion() throws Exception {
        for (int i = 1; i <= 3; i++) {
            JsonNode result = accepted(question);
            assertEquals(i, result.get("attemptNumber").asInt());
            finish();
        }
        jdbc.update("delete from interview_attempts where user_id = ?", user);
        doReturn(Instant.parse("2026-10-31T15:01:00Z")).when(clock).instant();
        error(submit(question, key(), "{\"text\":\"4번째\"}"), 409, "QUESTION_LIMIT_EXCEEDED");
        error(draft(question, "초안", null), 409, "QUESTION_LIMIT_EXCEEDED");
        assertEquals(3, count("select count(*) from interview_submissions where user_id = ?", user));
    }

    @Test
    void todaySlotsAllowRetriesOnIncludedQuestionButRejectFourthQuestion() throws Exception {
        long generation = count("select generation_policy_version_id from interview_questions where id = ?", question);
        long second = question(generation), third = question(generation), fourth = question(generation);
        accepted(question); finish();
        accepted(second); finish();
        accepted(third); finish();
        error(submit(fourth, key(), "{\"text\":\"4번째 질문\"}"), 409, "DAILY_LIMIT_EXCEEDED");
        JsonNode retry = accepted(question);
        assertEquals(2, retry.get("attemptNumber").asInt());
        assertEquals(3, retry.get("usage").get("daily").get("questionUsed").asInt());
        assertEquals(4, retry.get("usage").get("daily").get("used").asInt());
    }

    @Test
    void dailyNineSubmissionsAndNextDayQuestionAdmissionAreIndependent() throws Exception {
        long generation = count("select generation_policy_version_id from interview_questions where id = ?", question);
        long second = question(generation), third = question(generation);
        for (long q : new long[]{question, second, third}) for (int i = 0; i < 3; i++) { accepted(q); finish(); }
        long fourth = question(generation);
        error(submit(fourth, key(), "{\"text\":\"한도\"}"), 409, "DAILY_LIMIT_EXCEEDED");
        doReturn(Instant.parse("2026-10-31T15:01:00Z")).when(clock).instant();
        JsonNode result = accepted(fourth);
        assertEquals("2026-11-01", result.get("usage").get("daily").get("businessDate").asText());
        assertEquals(1, result.get("usage").get("daily").get("used").asInt());
        assertEquals(1, result.get("usage").get("daily").get("questionUsed").asInt());
    }

    @Test
    void concurrentSameKeyConsumesOnlyOnce() throws Exception {
        String key = key();
        List<JsonNode> results = concurrent(
                () -> data(submit(question, key, "{\"text\":\"답변\"}").andExpect(status().isAccepted())),
                () -> data(submit(question, key, "{\"text\":\"답변\"}").andExpect(status().isAccepted())));
        assertEquals(results.getFirst(), results.getLast());
        assertEquals(1, count("select count(*) from interview_submissions where user_id = ?", user));
    }

    @Test
    void concurrentDifferentKeysOnlyAdmitOneProcessingAnswer() throws Exception {
        List<Integer> codes = concurrent(
                () -> submit(question, key(), "{\"text\":\"하나\"}").andReturn().getResponse().getStatus(),
                () -> submit(question, key(), "{\"text\":\"둘\"}").andReturn().getResponse().getStatus());
        assertEquals(List.of(202, 409), codes.stream().sorted().toList());
        assertEquals(1, count("select count(*) from interview_submissions where user_id = ?", user));
    }

    @Test
    void concurrentDraftSavesWithSameVersionOnlyOneSucceeds() throws Exception {
        draft(question, "초안", null).andExpect(status().isOk());
        List<Integer> codes = concurrent(
                () -> draft(question, "하나", 1L).andReturn().getResponse().getStatus(),
                () -> draft(question, "둘", 1L).andReturn().getResponse().getStatus());
        assertEquals(List.of(200, 409), codes.stream().sorted().toList());
    }

    @Test
    void failureAfterIdempotencyInsertRollsBackEverythingIncludingDraftClear() throws Exception {
        draft(question, "보존 초안", null).andExpect(status().isOk());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new TransientDataAccessResourceException("simulated commit-path failure");
        }).when(requests).complete(anyLong(), any(), any(), any());
        error(submit(question, key(), "{\"text\":\"답변\",\"draftVersion\":1}"), 503, "SERVICE_UNAVAILABLE");
        assertNoSubmissions();
        assertEquals(1, count("select count(*) from ai_contexts where user_id = ?", user));
        assertEquals(1, count("select version from interview_drafts where question_id = ?", question));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"text\":\"\"}", "{\"text\":\"  \"}", "{\"text\":\"답변\",\"materialIds\":[]}",
            "{\"text\":\"답변\",\"draftVersion\":0}", "{\"text\":\"답변\",\"draftVersion\":\"1\"}"})
    void rejectsInvalidSubmissionBodies(String body) throws Exception {
        error(submit(question, key(), body), 400, "INVALID_INPUT");
        assertNoSubmissions();
    }

    @Test
    void rejectsUnexpectedQueryBodyAndUnauthenticatedAccess() throws Exception {
        mvc.perform(get("/v1/interview-attempts/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/interview-attempts/1").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{}" )).andExpect(status().isBadRequest());
        mvc.perform(put(draftPath(question)).header("Authorization", "Bearer " + token).param("extra", "1")
                        .contentType("application/json").content("{\"text\":\"초안\",\"version\":null}"))
                .andExpect(status().isBadRequest());
        error(submit(question, "bad-key", "{\"text\":\"답변\"}"), 400, "INVALID_INPUT");
    }

    @Test
    void concurrentDifferentQuestionsCannotTakeTheSameFinalDailySlot() throws Exception {
        long generation = count("select generation_policy_version_id from interview_questions where id = ?", question);
        long second = question(generation), third = question(generation), fourth = question(generation);
        accepted(question); finish();
        accepted(second); finish();
        List<Integer> codes = concurrent(
                () -> submit(third, key(), "{\"text\":\"세 번째\"}").andReturn().getResponse().getStatus(),
                () -> submit(fourth, key(), "{\"text\":\"경쟁 요청\"}").andReturn().getResponse().getStatus());
        assertEquals(List.of(202, 409), codes.stream().sorted().toList());
        assertEquals(3, count("select count(*) from interview_daily_questions where user_id = ?", user));
        assertEquals(3, count("select count(*) from interview_submissions where user_id = ?", user));
    }

    @Test
    void unicodeLimitIsEnforcedBeforeWritingAndAcceptedAtExactlyTwoThousand() throws Exception {
        String text = "😀".repeat(2000);
        error(draft(question, text + "a", null), 400, "INVALID_INPUT");
        draft(question, text, null).andExpect(status().isOk());
        String body = mapper.writeValueAsString(java.util.Map.of("text", text, "draftVersion", 1));
        JsonNode result = data(submit(question, key(), body).andExpect(status().isAccepted()));
        detail(result.get("attemptId").asLong(), token).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.text").value(text));
    }

    @Test
    void revokedSessionCannotReplayAnAcceptedRequest() throws Exception {
        String key = key();
        submit(question, key, "{\"text\":\"답변\"}").andExpect(status().isAccepted());
        jdbc.update("update auth_sessions set revoked_at = current_timestamp(6) where user_id = ?", user);
        error(submit(question, key, "{\"text\":\"답변\"}"), 401, "UNAUTHENTICATED");
        assertEquals(1, count("select count(*) from interview_submissions where user_id = ?", user));
    }

    @Test
    void evaluationPolicyIsPinnedAndNotChangedByLaterPublication() throws Exception {
        JsonNode result = accepted(question);
        jdbc.update("update interview_policies set active_version_no = null where company_id = ? and type = 'EVALUATION'", company);
        assertEquals(evaluationPolicy, count("select evaluation_policy_version_id from interview_attempts where id = ?", result.get("attemptId").asLong()));
        assertEquals(evaluationPolicy, count("select policy_version_id from ai_tasks where id = ?", result.get("taskId").asText()));
        detail(result.get("attemptId").asLong(), token).andExpect(status().isOk());
    }

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