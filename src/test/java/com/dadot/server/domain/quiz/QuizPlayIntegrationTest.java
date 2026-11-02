package com.dadot.server.domain.quiz;

import com.dadot.server.domain.quiz.repository.QuizPlayRepository;
import com.dadot.server.domain.quiz.repository.QuizPlayRequestStore;
import com.dadot.server.global.security.AccessTokenIssuer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessResourceException;
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
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;

import static com.dadot.server.domain.quiz.repository.QuizPlayRepository.utc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.dart.worker-enabled=false", "app.dart.originals.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuizPlayIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired AccessTokenIssuer tokens;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean Clock clock;
    @MockitoSpyBean QuizPlayRepository repository;
    @MockitoSpyBean QuizPlayRequestStore requests;

    private long user, company, disclosure, source, section;
    private String session, token;
    private Instant now;
    private final List<Long> users = new ArrayList<>();
    private boolean safe;

    @BeforeEach
    void setup() {
        safe = false;
        assertEquals("dadot_test_db", jdbc.queryForObject("select database()", String.class));
        safe = true;
        reset(clock, repository, requests);
        users.clear();
        session = null;
        now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        at(now);
        user = account();
        company = insert("insert into companies(dart_corp_code, name, is_service_company) values ('99003400', '학습기업', true)");
        jdbc.update("""
                insert into user_companies(user_id, company_id, active_slot, custom_job_name)
                values (?, ?, 1, '개발')
                """, user, company);
        login();
        disclosure = insert("""
                insert into disclosures(company_id, receipt_no, report_name, report_type, disclosed_on, source_url)
                values (?, ?, '사업보고서', 'ANNUAL', '2026-01-01', 'https://dart.fss.or.kr/')
                """, company, key().replace("-", "").substring(0, 20));
        source = insert("""
                insert into disclosure_versions(disclosure_id, company_id, version_no, status, content_hash, extractor_version)
                values (?, ?, 1, 'PUBLISHED', ?, 'quiz34-test')
                """, disclosure, company, "a".repeat(64));
        section = insert("""
                insert into disclosure_sections(disclosure_version_id, company_id, section_key, content_text)
                values (?, ?, 'business', '연구개발에 투자합니다.')
                """, source, company);
        for (int i = 0; i < 6; i++) {
            question(i);
        }
    }

    @AfterEach
    void cleanup() {
        if (!safe) return;
        reset(clock, repository, requests);
        for (long id : users) {
            jdbc.update("delete from api_idempotency_requests where user_id = ?", id);
        }
        jdbc.update("delete from quiz_answers where company_id = ?", company);
        jdbc.update("delete from quiz_set_items where company_id = ?", company);
        jdbc.update("delete from quiz_sets where company_id = ?", company);
        jdbc.update("delete from user_companies where company_id = ?", company);
        jdbc.update("update quiz_questions set published_version_no = null where company_id = ?", company);
        jdbc.update("delete from quiz_evidence where company_id = ?", company);
        jdbc.update("delete o from quiz_options o join quiz_question_versions v on v.id = o.quiz_version_id where v.company_id = ?", company);
        jdbc.update("delete from quiz_question_versions where company_id = ?", company);
        jdbc.update("delete from quiz_questions where company_id = ?", company);
        jdbc.update("delete from disclosure_sections where company_id = ?", company);
        jdbc.update("delete from disclosure_versions where company_id = ?", company);
        jdbc.update("update disclosures set corrects_disclosure_id = null where company_id = ?", company);
        jdbc.update("delete from disclosures where company_id = ?", company);
        jdbc.update("delete from companies where id = ?", company);
        for (long id : users) {
            jdbc.update("delete from auth_sessions where user_id = ?", id);
            jdbc.update("delete from users where id = ?", id);
        }
    }

    @Test
    void completesFiveQuestionsWithoutLeakingAnswersAndStoresSnapshots() throws Exception {
        long set = prepare();
        detail(set).andExpect(jsonPath("$.data.currentItem.options.length()").value(4))
                .andExpect(jsonPath("$.data.currentItem.correctOptionId").doesNotExist())
                .andExpect(jsonPath("$.data.currentItem.explanation").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"));
        result(set).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("QUIZ_SET_NOT_COMPLETED"));
        for (int i = 1; i <= 5; i++) {
            var current = current(set);
            answer(set, current, i != 5, key()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.submittedCount").value(i))
                    .andExpect(jsonPath("$.data.isCorrect").doesNotExist())
                    .andExpect(jsonPath("$.data.correctOptionId").doesNotExist())
                    .andExpect(jsonPath("$.data.status").value(i == 5 ? "COMPLETED" : "IN_PROGRESS"));
        }
        result(set).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.correctCount").value(4))
                .andExpect(jsonPath("$.data.items.length()").value(5))
                .andExpect(jsonPath("$.data.accuracyBefore.percent").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.accuracyAfter.percent").value(80))
                .andExpect(jsonPath("$.data.items[0].evidence[0].quote").value("연구개발에 투자합니다."))
                .andExpect(jsonPath("$.data.items[0].evidence[0].sectionTitle").value(org.hamcrest.Matchers.nullValue()));
        detail(set).andExpect(jsonPath("$.data.currentItem").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.savedAnswers.length()").value(5))
                .andExpect(jsonPath("$.data.savedAnswers[0].isCorrect").doesNotExist());
        prepare(key()).andExpect(status().isOk()).andExpect(jsonPath("$.data.quizSetId").value(set));
        assertEquals(5, count("quiz_answers"));
    }

    @Test
    void resumesSameSetAfterJobAndDateChange() throws Exception {
        long set = prepare();
        answer(set, current(set), true, key()).andExpect(status().isOk());
        jdbc.update("update user_companies set custom_job_name = '영업', interview_date = ? where user_id = ?",
                date().plusDays(1), user);
        prepare(key()).andExpect(status().isOk()).andExpect(jsonPath("$.data.quizSetId").value(set))
                .andExpect(jsonPath("$.data.learningMode").value("NORMAL"));
        detail(set).andExpect(jsonPath("$.data.currentItem.position").value(2))
                .andExpect(jsonPath("$.data.savedAnswers.length()").value(1));
    }

    @Test
    void insufficientBankDoesNotSavePartialSetOrRequest() throws Exception {
        jdbc.update("update quiz_questions set published_version_no = null where company_id = ? limit 2", company);
        prepare(key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("QUIZ_BANK_INSUFFICIENT"));
        assertEquals(0, count("quiz_sets"));
        assertEquals(0, count("api_idempotency_requests"));
    }

    @Test
    void rejectsWrongOrderForeignOptionAndOverwritingSubmittedAnswer() throws Exception {
        long set = prepare();
        var first = current(set);
        long second = scalar("select id from quiz_set_items where set_id = ? and position = 2", set);
        rawAnswer(set, key(), "{\"itemId\":" + second + ",\"selectedOptionId\":1}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("INVALID_ITEM_ORDER"));
        long foreignOption = scalar("""
                select min(o.id) from quiz_options o join quiz_set_items i on i.quiz_version_id = o.quiz_version_id
                where i.set_id = ? and i.position = 2
                """, set);
        rawAnswer(set, key(), "{\"itemId\":" + first.get("itemId") + ",\"selectedOptionId\":" + foreignOption + "}")
                .andExpect(status().isBadRequest());
        answer(set, first, true, key()).andExpect(status().isOk());
        answer(set, first, false, key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ANSWER_ALREADY_SUBMITTED"));
        assertEquals(1, count("quiz_answers"));
    }

    @Test
    void replayPreservesOriginalAnswerResponseAndDetectsChangedBody() throws Exception {
        long set = prepare();
        var first = current(set);
        String key = key();
        var original = data(answer(set, first, true, key).andExpect(status().isOk()));
        answer(set, current(set), true, key()).andExpect(status().isOk());
        assertEquals(original, data(answer(set, first, true, key).andExpect(status().isOk())));
        answer(set, first, false, key).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void crossesKstMidnightWithoutLosingReplayOrAcceptingOldNewAnswers() throws Exception {
        at(date().atTime(23, 59, 59).atZone(ZoneId.of("Asia/Seoul")).toInstant());
        String createKey = key();
        var created = data(prepare(createKey).andExpect(status().isCreated()));
        long set = created.get("quizSetId").longValue();
        var first = current(set);
        String submitKey = key();
        var saved = data(answer(set, first, true, submitKey).andExpect(status().isOk()));
        var second = current(set);
        at(now.plusSeconds(2));
        assertEquals(created, data(prepare(createKey).andExpect(status().isCreated())));
        assertEquals(saved, data(answer(set, first, true, submitKey).andExpect(status().isOk())));
        answer(set, second, true, key()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("QUIZ_SET_EXPIRED"));
        detail(set).andExpect(jsonPath("$.data.status").value("EXPIRED"))
                .andExpect(jsonPath("$.data.currentItem").value(org.hamcrest.Matchers.nullValue()));
        long next = prepare();
        assertNotEquals(set, next);
        assertEquals("EXPIRED", jdbc.queryForObject("select status from quiz_sets where id = ?", String.class, set));
        assertEquals(1, count("quiz_answers"));
    }

    @Test
    void expiredRequestKeyNeverExecutesAgain() throws Exception {
        String key = key();
        prepare(key).andExpect(status().isCreated());
        at(now.plus(Duration.ofDays(1)));
        prepare(key).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_EXPIRED"));
        assertEquals(1, count("quiz_sets"));
    }

    @ParameterizedTest
    @ValueSource(ints = {-4, -3, 0, 1})
    void focusWindowUsesKstInterviewDate(int distance) throws Exception {
        jdbc.update("update user_companies set interview_date = ? where user_id = ?", date().minusDays(distance), user);
        prepare(key()).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.learningMode").value(distance >= -3 && distance <= 0 ? "FOCUS" : "NORMAL"));
    }

    @Test
    void focusSelectsFourLatestWrongAndOneUnseenWithoutChangingFirstAccuracy() throws Exception {
        long first = prepare();
        for (int i = 0; i < 5; i++) answer(first, current(first), i == 4, key()).andExpect(status().isOk());
        var snapshot = data(result(first).andExpect(status().isOk()));
        at(now.plus(Duration.ofDays(1)));
        jdbc.update("update user_companies set interview_date = ? where user_id = ?", date(), user);
        long next = prepare();
        assertEquals(4, scalar("select sum(selected_from_incorrect) from quiz_set_items where set_id = ?", next));
        assertTrue(current(next).get("isReview").booleanValue());
        for (int i = 0; i < 5; i++) answer(next, current(next), true, key()).andExpect(status().isOk());
        result(next).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accuracyBefore.firstAnsweredCount").value(5))
                .andExpect(jsonPath("$.data.accuracyAfter.firstAnsweredCount").value(6))
                .andExpect(jsonPath("$.data.accuracyAfter.firstCorrectCount").value(2));
        assertEquals(0, scalar("""
                select count(*) from quiz_answers a where a.user_id = ? and not a.is_correct
                  and not exists(select 1 from quiz_answers n where n.user_id = a.user_id
                    and n.question_id = a.question_id and (n.submitted_at > a.submitted_at
                      or (n.submitted_at = a.submitted_at and n.id > a.id)))
                """, user));
        assertEquals(snapshot, data(result(first).andExpect(status().isOk())));
    }

    @Test
    void normalExhaustionSelectsUnseenThenOldestAndPreservesAllHistory() throws Exception {
        long first = prepare();
        for (int i = 0; i < 5; i++) answer(first, current(first), true, key()).andExpect(status().isOk());
        long firstQuestion = scalar("select question_id from quiz_set_items where set_id = ? and position = 1", first);
        at(now.plus(Duration.ofDays(1)));
        long second = prepare();
        var unseen = current(second);
        assertFalse(unseen.get("isReview").booleanValue());
        answer(second, unseen, true, key()).andExpect(status().isOk());
        assertEquals(firstQuestion, current(second).get("questionId").longValue());
        for (int i = 1; i < 5; i++) answer(second, current(second), false, key()).andExpect(status().isOk());
        assertEquals(10, count("quiz_answers"));
        result(second).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accuracyAfter.firstAnsweredCount").value(6))
                .andExpect(jsonPath("$.data.accuracyAfter.firstCorrectCount").value(6));
    }

    @ParameterizedTest
    @ValueSource(strings = {"question", "source", "correction", "pointer"})
    void stoppedPendingContentBlocksDetailAndAnswer(String kind) throws Exception {
        long set = prepare();
        var first = current(set);
        stop(kind, first);
        detail(set).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("CONTENT_UNAVAILABLE"));
        answer(set, first, true, key()).andExpect(status().isConflict());
        assertEquals(0, count("quiz_answers"));
    }

    @Test
    void submittedStoppedItemDoesNotBlockRemainingAndHistoryShowsNotice() throws Exception {
        long set = prepare();
        var first = current(set);
        String key = key();
        answer(set, first, true, key).andExpect(status().isOk());
        stop("question", first);
        answer(set, first, true, key).andExpect(status().isOk());
        for (int i = 1; i < 5; i++) answer(set, current(set), true, key()).andExpect(status().isOk());
        stop("source", first);
        result(set).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].revisionNotice").isString());
        jdbc.update("update user_companies set active_slot = null, excluded_at = ? where user_id = ?", utc(now), user);
        result(set).andExpect(status().isOk());
    }

    @Test
    void failedAnswerResponseStorageRollsBackAnswerCompletionAndStatistics() throws Exception {
        long set = prepare();
        for (int i = 0; i < 4; i++) answer(set, current(set), true, key()).andExpect(status().isOk());
        var current = current(set);
        String key = key();
        doThrow(new TransientDataAccessResourceException("test-only"))
                .when(requests).complete(eq(user), any(), eq(200), any(), any());
        answer(set, current, true, key).andExpect(status().isServiceUnavailable());
        assertEquals(4, count("quiz_answers"));
        assertEquals("IN_PROGRESS", jdbc.queryForObject("select status from quiz_sets where id = ?", String.class, set));
        assertNull(jdbc.queryForObject("select accuracy_after_total from quiz_sets where id = ?", Integer.class, set));
        reset(requests);
        answer(set, current, true, key).andExpect(status().isOk());
    }

    @Test
    void failedPrepareResponseStorageRollsBackAllFiveItems() throws Exception {
        doThrow(new TransientDataAccessResourceException("test-only"))
                .when(requests).complete(eq(user), any(), eq(201), any(), any());
        prepare(key()).andExpect(status().isServiceUnavailable());
        assertEquals(0, count("quiz_sets"));
        assertEquals(0, count("quiz_set_items"));
    }

    @Test
    void concurrentDifferentKeysPrepareOneSet() throws Exception {
        var values = race(() -> data(prepare(key())), () -> data(prepare(key())));
        assertEquals(values.getFirst().get("quizSetId"), values.getLast().get("quizSetId"));
        assertEquals(1, count("quiz_sets"));
        assertEquals(5, count("quiz_set_items"));
    }

    @Test
    void concurrentSameAnswerKeySavesOnceAndReplays() throws Exception {
        long set = prepare();
        var current = current(set);
        String key = key();
        var values = race(() -> data(answer(set, current, true, key).andExpect(status().isOk())),
                () -> data(answer(set, current, true, key).andExpect(status().isOk())));
        assertEquals(values.getFirst(), values.getLast());
        assertEquals(1, count("quiz_answers"));
    }

    @Test
    void concurrentDifferentAnswerKeysCannotOverwrite() throws Exception {
        long set = prepare();
        var current = current(set);
        var values = race(() -> answer(set, current, true, key()).andReturn().getResponse().getStatus(),
                () -> answer(set, current, false, key()).andReturn().getResponse().getStatus());
        assertEquals(Set.of(200, 409), new HashSet<>(values));
        assertEquals(1, count("quiz_answers"));
    }

    @Test
    void answerWaitingForAdminCompanyLockRechecksCommittedSourceState() throws Exception {
        long set = prepare();
        var item = current(set);
        var reachedLock = new CountDownLatch(1);
        doAnswer(invocation -> {
            reachedLock.countDown();
            return invocation.callRealMethod();
        }).when(repository).lockCompany(company);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var tx = new TransactionTemplate(transactions);
            var future = tx.execute(status -> {
                jdbc.queryForObject("select id from companies where id = ? for update", Long.class, company);
                var waiting = pool.submit(() -> answer(set, item, true, key())
                        .andReturn().getResponse().getStatus());
                try {
                    assertTrue(reachedLock.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
                jdbc.update("update disclosure_versions set status = 'SUSPENDED' where id = ?", source);
                return waiting;
            });
            assertEquals(409, Objects.requireNonNull(future).get(15, TimeUnit.SECONDS));
        }
        assertEquals(0, count("quiz_answers"));
    }

    @Test
    void createReplayRetainsOriginalProgressAndEachPathHasSeparateKeyScope() throws Exception {
        String sharedKey = key();
        var created = data(prepare(sharedKey).andExpect(status().isCreated()));
        long set = created.get("quizSetId").longValue();
        answer(set, current(set), true, sharedKey).andExpect(status().isOk());
        assertEquals(created, data(prepare(sharedKey).andExpect(status().isCreated())));
        prepare(key()).andExpect(status().isOk()).andExpect(jsonPath("$.data.submittedCount").value(1));
    }

    @Test
    void onlyPublishedUsableVersionsAreSelected() throws Exception {
        jdbc.update("""
                update quiz_question_versions set status = 'DRAFT'
                where company_id = ? order by id limit 1
                """, company);
        long set = prepare();
        assertEquals(0, scalar("""
                select count(*) from quiz_set_items i join quiz_question_versions v on v.id = i.quiz_version_id
                where i.set_id = ? and v.status <> 'PUBLISHED'
                """, set));
    }

    @Test
    void ownershipInactivePreparationAndRevokedSessionAreChecked() throws Exception {
        long set = prepare();
        long other = account();
        String old = token;
        String sid = key();
        var issued = now.truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("insert into auth_sessions(id,user_id,created_at,expires_at) values (?,?,?,?)",
                sid, other, utc(issued), utc(issued.plusSeconds(3600)));
        token = tokens.issue(other, sid, 0, issued, issued.plusSeconds(3600));
        detail(set).andExpect(status().isNotFound());
        result(set).andExpect(status().isNotFound());
        token = old;
        jdbc.update("update user_companies set active_slot = null, excluded_at = ? where user_id = ?", utc(now), user);
        prepare(key()).andExpect(status().isNotFound());
        jdbc.update("update auth_sessions set revoked_at = ? where id = ?", utc(now), session);
        detail(set).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"itemId\":0,\"selectedOptionId\":1}",
            "{\"itemId\":1.5,\"selectedOptionId\":1}", "{\"itemId\":\"1\",\"selectedOptionId\":1}",
            "{\"itemId\":1,\"selectedOptionId\":null}", "{\"itemId\":1,\"selectedOptionId\":9007199254740992}",
            "{\"itemId\":1,\"selectedOptionId\":1,\"isCorrect\":true}"})
    void rejectsInvalidAnswerJson(String body) throws Exception {
        rawAnswer(prepare(), key(), body).andExpect(status().isBadRequest());
        assertEquals(0, count("quiz_answers"));
    }

    @Test
    void validatesBodyQueryKeyAndAuthentication() throws Exception {
        mvc.perform(post(preparePath())).andExpect(status().isUnauthorized());
        prepare("bad-key").andExpect(status().isBadRequest());
        mvc.perform(post(preparePath()).header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(preparePath() + "?other=1").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key())).andExpect(status().isBadRequest());
    }

    @Test
    void databaseRejectsForeignOptionVersionAndDuplicateQuestion() throws Exception {
        long set = prepare();
        var first = current(set);
        answer(set, first, true, key()).andExpect(status().isOk());
        long option = scalar("select min(id) from quiz_options where quiz_version_id <> ?", first.get("questionVersionId").longValue());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update quiz_answers set selected_option_id = ? where user_id = ?", option, user));
        long q = first.get("questionId").longValue();
        long v = first.get("questionVersionId").longValue();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "update quiz_set_items set question_id = ?, quiz_version_id = ? where set_id = ? and position = 2", q, v, set));
    }

    private long account() {
        long id = insert("insert into users(nickname) values ('퀴즈사용자')");
        users.add(id);
        return id;
    }

    private void question(int number) {
        long q = insert("insert into quiz_questions(company_id) values (?)", company);
        long v = insert("""
                insert into quiz_question_versions(question_id, company_id, version_no, status, question_text, explanation)
                values (?, ?, 1, 'PUBLISHED', ?, '연구개발에 투자하기 때문입니다.')
                """, q, company, "기업 문제 " + number);
        for (int i = 1; i <= 4; i++) {
            insert("insert into quiz_options(quiz_version_id, position, option_text, is_correct) values (?, ?, ?, ?)",
                    v, i, "보기 " + i, i == 1);
        }
        jdbc.update("insert into quiz_evidence(quiz_version_id, section_id, company_id, quotation) values (?, ?, ?, ?)",
                v, section, company, "연구개발에 투자합니다.");
        jdbc.update("update quiz_questions set published_version_no = 1 where id = ?", q);
    }

    private void stop(String kind, JsonNode item) {
        switch (kind) {
            case "question" -> jdbc.update("update quiz_question_versions set status = 'SUSPENDED' where id = ?", item.get("questionVersionId").longValue());
            case "source" -> jdbc.update("update disclosure_versions set status = 'SUSPENDED' where id = ?", source);
            case "pointer" -> jdbc.update("update quiz_questions set published_version_no = null where id = ?", item.get("questionId").longValue());
            case "correction" -> jdbc.update("""
                    insert into disclosures(company_id, receipt_no, report_name, report_type,
                        disclosed_on, source_url, corrects_disclosure_id)
                    values (?, ?, '정정', 'ANNUAL', '2026-01-01', 'https://dart.fss.or.kr/', ?)
                    """, company, key().replace("-", "").substring(0, 20), disclosure);
            default -> throw new IllegalArgumentException();
        }
    }

    private long prepare() throws Exception {
        return data(prepare(key()).andExpect(status().isCreated())).get("quizSetId").longValue();
    }

    private ResultActions prepare(String key) throws Exception {
        return mvc.perform(post(preparePath()).header("Authorization", "Bearer " + token).header("Idempotency-Key", key));
    }

    private String preparePath() { return "/v1/companies/" + company + "/quiz-sets/today"; }

    private ResultActions detail(long set) throws Exception {
        return mvc.perform(get("/v1/quiz-sets/" + set).header("Authorization", "Bearer " + token));
    }

    private JsonNode current(long set) throws Exception {
        return data(detail(set).andExpect(status().isOk())).get("currentItem");
    }

    private ResultActions result(long set) throws Exception {
        return mvc.perform(get("/v1/quiz-sets/" + set + "/result").header("Authorization", "Bearer " + token));
    }

    private ResultActions answer(long set, JsonNode item, boolean correct, String key) throws Exception {
        long selected = item.get("options").get(correct ? 0 : 1).get("optionId").longValue();
        return rawAnswer(set, key, mapper.writeValueAsString(Map.of("itemId", item.get("itemId").longValue(), "selectedOptionId", selected)));
    }

    private ResultActions rawAnswer(long set, String key, String json) throws Exception {
        return mvc.perform(post("/v1/quiz-sets/" + set + "/answers").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key).contentType("application/json").content(json));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private long count(String table) {
        return scalar("select count(*) from " + table + " where "
                        + (table.equals("api_idempotency_requests") ? "user_id" : "company_id") + " = ?",
                table.equals("api_idempotency_requests") ? user : company);
    }

    private long scalar(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(c -> {
            var s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            return s;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private void at(Instant instant) {
        now = instant;
        doReturn(instant).when(clock).instant();
        if (session != null) login();
    }

    private void login() {
        session = key();
        var issued = now.truncatedTo(ChronoUnit.SECONDS);
        jdbc.update("insert into auth_sessions(id,user_id,created_at,expires_at) values (?,?,?,?)",
                session, user, utc(issued), utc(issued.plusSeconds(3600)));
        token = tokens.issue(user, session, 0, issued, issued.plusSeconds(3600));
    }

    private LocalDate date() { return now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate(); }
    private static String key() { return UUID.randomUUID().toString(); }

    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(action -> pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return action.call();
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return List.of(futures.getFirst().get(15, TimeUnit.SECONDS), futures.getLast().get(15, TimeUnit.SECONDS));
        }
    }
}