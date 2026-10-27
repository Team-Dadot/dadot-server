package com.dadot.server.domain.quiz;

import com.dadot.server.domain.company.support.CompanyLogoUrlResolver;
import com.dadot.server.domain.quiz.repository.QuizReviewRepository;
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
class QuizReviewIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired AccessTokenIssuer tokens;
    @Autowired Clock clock;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean QuizReviewRepository repository;
    @MockitoSpyBean CompanyLogoUrlResolver logos;

    private final List<Long> users = new ArrayList<>();
    private final List<Long> companies = new ArrayList<>();
    private long user, company, disclosure, source, section;
    private String session, token;
    private Instant now;
    private boolean safe;

    @BeforeEach
    void setup() {
        safe = false;
        assertEquals("dadot_test_db", jdbc.queryForObject("select database()", String.class));
        safe = true;
        reset(repository, logos);
        users.clear();
        companies.clear();
        now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        user = account();
        login(user);
        company = company("복습기업");
        preparation(user, company, 1);
        disclosure = filing(null);
        source = insert("""
                insert into disclosure_versions(disclosure_id, company_id, version_no, status,
                    content_hash, extractor_version)
                values (?, ?, 1, 'PUBLISHED', ?, 'review36-test')
                """, disclosure, company, "b".repeat(64));
        section = insert("""
                insert into disclosure_sections(disclosure_version_id, company_id, section_key, content_text)
                values (?, ?, 'business', '연구개발에 투자합니다.')
                """, source, company);
    }

    @AfterEach
    void cleanup() {
        if (!safe) return;
        reset(repository, logos);
        for (long id : users) {
            jdbc.update("delete from api_idempotency_requests where user_id = ?", id);
        }
        for (long id : companies) {
            jdbc.update("delete from quiz_answers where company_id = ?", id);
            jdbc.update("delete from quiz_set_items where company_id = ?", id);
            jdbc.update("delete from quiz_sets where company_id = ?", id);
            jdbc.update("delete from user_companies where company_id = ?", id);
            jdbc.update("update quiz_questions set published_version_no = null where company_id = ?", id);
            jdbc.update("delete from quiz_evidence where company_id = ?", id);
            jdbc.update("delete o from quiz_options o join quiz_question_versions v on v.id = o.quiz_version_id where v.company_id = ?", id);
            jdbc.update("delete from quiz_question_versions where company_id = ?", id);
            jdbc.update("delete from quiz_questions where company_id = ?", id);
            jdbc.update("delete from disclosure_sections where company_id = ?", id);
            jdbc.update("delete from disclosure_versions where company_id = ?", id);
            jdbc.update("update disclosures set corrects_disclosure_id = null where company_id = ?", id);
            jdbc.update("delete from disclosures where company_id = ?", id);
            jdbc.update("delete from companies where id = ?", id);
        }
        for (long id : users) {
            jdbc.update("delete from auth_sessions where user_id = ?", id);
            jdbc.update("delete from users where id = ?", id);
        }
    }

    @Test
    void activeCompanyAppearsWithoutAnswersAndHasEmptyQuizList() throws Exception {
        companies("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].company.companyId").value(company))
                .andExpect(jsonPath("$.data.content[0].isActive").value(true))
                .andExpect(jsonPath("$.data.content[0].quizQuestionCount").value(0))
                .andExpect(jsonPath("$.data.content[0].interviewQuestionCount").value(0))
                .andExpect(jsonPath("$.data.content[0].company.industry").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.content[0].company.logoUrl").value(org.hamcrest.Matchers.nullValue()));
        questions("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0))
                .andExpect(jsonPath("$.data.allCount").value(0))
                .andExpect(jsonPath("$.data.incorrectCount").value(0));
    }

    @Test
    void companiesIncludeAnsweredExcludedButOmitUnansweredExcludedAndForeign() throws Exception {
        long q = question();
        answer(user, set(user, -1), q, true, now.minusSeconds(20));
        exclude(user, company);
        long active = company("가나다");
        preparation(user, active, 1);
        long empty = company("제외빈기업");
        preparation(user, empty, null);
        long other = account();
        preparation(other, company, 1);
        answer(other, set(other, -1), q, false, now);
        companies("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].company.companyId").value(active))
                .andExpect(jsonPath("$.data.content[1].company.companyId").value(company))
                .andExpect(jsonPath("$.data.content[1].isActive").value(false))
                .andExpect(jsonPath("$.data.content[1].quizQuestionCount").value(1));
        questions("").andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        detail(q).andExpect(status().isOk()).andExpect(jsonPath("$.data.isCorrect").value(true));
        request("/v1/reviews/quizzes?companyId=" + empty).andExpect(status().isNotFound());
    }

    @Test
    void companyPagingSortsActiveThenNameThenIdAndKeepsRealTotals() throws Exception {
        jdbc.update("update companies set name = '같은이름' where id = ?", company);
        long second = company("같은이름");
        preparation(user, second, 2);
        companies("?size=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].company.companyId").value(company))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        companies("?page=1&size=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].company.companyId").value(second))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        companies("?page=2147483647&size=100").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    void reviewCanReadSavedAnswerFromIncompleteSetButNeverUnansweredQuestion() throws Exception {
        long q = question();
        long pending = question();
        long set = set(user, 0);
        long saved = answer(user, set, q, false, now);
        item(user, set, pending, 2);
        assertEquals("IN_PROGRESS", jdbc.queryForObject("select status from quiz_sets where id = ?", String.class, set));
        detail(q).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answerId").value(saved))
                .andExpect(jsonPath("$.data.question.options.length()").value(4))
                .andExpect(jsonPath("$.data.isCorrect").value(false))
                .andExpect(jsonPath("$.data.explanation").value("연구개발에 투자하기 때문입니다."))
                .andExpect(jsonPath("$.data.evidence[0].quote").value("연구개발에 투자합니다."))
                .andExpect(jsonPath("$.data.evidence[0].sectionTitle").value(org.hamcrest.Matchers.nullValue()));
        detail(pending).andExpect(status().isNotFound());
        questions("").andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void latestAnswerChangesWrongTabWithoutChangingFirstAccuracyOrDeletingHistory() throws Exception {
        long q = question();
        answer(user, set(user, -1), q, false, now.minusSeconds(30));
        long latest = answer(user, set(user, 0), q, true, now);
        questions("&filter=ALL").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].latestAnswerId").value(latest))
                .andExpect(jsonPath("$.data.content[0].isCorrect").value(true))
                .andExpect(jsonPath("$.data.allCount").value(1))
                .andExpect(jsonPath("$.data.incorrectCount").value(0));
        questions("&filter=INCORRECT").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.allCount").value(1));
        detail(q).andExpect(status().isOk()).andExpect(jsonPath("$.data.answerId").value(latest))
                .andExpect(jsonPath("$.data.question.isReview").value(true));
        assertEquals(2, scalar("select count(*) from quiz_answers where user_id = ?", user));
        assertEquals(0, scalar("select is_correct from quiz_answers where user_id = ? order by submitted_at,id limit 1", user));
        companies("").andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].quizQuestionCount").value(1));
    }

    @Test
    void timestampThenAnswerIdSelectsLatestAndQuestionIdBreaksListTies() throws Exception {
        long first = question();
        long second = question();
        long old = set(user, -1);
        answer(user, old, first, false, now);
        long current = set(user, 0);
        long latest = answer(user, current, first, true, now);
        answer(user, current, second, false, now);
        questions("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].questionId").value(second))
                .andExpect(jsonPath("$.data.content[1].questionId").value(first))
                .andExpect(jsonPath("$.data.content[1].latestAnswerId").value(latest));
        questions("&filter=INCORRECT").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].questionId").value(second));
        detail(first).andExpect(status().isOk()).andExpect(jsonPath("$.data.answerId").value(latest));
    }

    @Test
    void laterInsertedOlderTimestampDoesNotReplaceLatestAnswer() throws Exception {
        long q = question();
        long latest = answer(user, set(user, 0), q, true, now);
        answer(user, set(user, -1), q, false, now.minusSeconds(60));
        questions("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].latestAnswerId").value(latest))
                .andExpect(jsonPath("$.data.incorrectCount").value(0));
        detail(q).andExpect(status().isOk()).andExpect(jsonPath("$.data.answerId").value(latest));
    }

    @Test
    void filteredPagingKeepsUnfilteredTabCountsAndBounds() throws Exception {
        long set = set(user, 0);
        for (int i = 0; i < 5; i++) answer(user, set, question(), i < 2, now.minusSeconds(i));
        questions("&filter=INCORRECT&size=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.allCount").value(5))
                .andExpect(jsonPath("$.data.incorrectCount").value(3));
        questions("&filter=INCORRECT&page=1&size=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        questions("&filter=INCORRECT&page=2147483647&size=100").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.allCount").value(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"question", "source", "correction", "pointer"})
    void stoppedOrCorrectedContentKeepsHistoricalDetailWithNotice(String kind) throws Exception {
        long q = question();
        answer(user, set(user, 0), q, true, now);
        switch (kind) {
            case "question" -> jdbc.update("update quiz_question_versions set status = 'SUSPENDED' where question_id = ?", q);
            case "source" -> jdbc.update("update disclosure_versions set status = 'SUPERSEDED' where id = ?", source);
            case "pointer" -> jdbc.update("update quiz_questions set published_version_no = null where id = ?", q);
            case "correction" -> filing(disclosure);
            default -> throw new IllegalArgumentException();
        }
        detail(q).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revisionNotice").isString())
                .andExpect(jsonPath("$.data.evidence[0].quote").value("연구개발에 투자합니다."));
        if (kind.equals("source") || kind.equals("correction")) {
            detail(q).andExpect(jsonPath("$.data.evidence[0].isSuperseded").value(true));
        }
        questions("").andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void newPublicationNeverRewritesPreviouslySubmittedContent() throws Exception {
        long q = question();
        long oldVersion = version(q);
        long oldAnswer = answer(user, set(user, -1), q, true, now.minusSeconds(60));
        long next = contentVersion(q, 2, "개정 문제", "개정 해설");
        jdbc.update("update quiz_question_versions set status = 'SUPERSEDED' where id = ?", oldVersion);
        jdbc.update("update quiz_questions set published_version_no = 2 where id = ?", q);
        detail(q).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answerId").value(oldAnswer))
                .andExpect(jsonPath("$.data.question.questionVersionId").value(oldVersion))
                .andExpect(jsonPath("$.data.explanation").value("연구개발에 투자하기 때문입니다."));
        long latest = answer(user, set(user, 0), q, false, now);
        detail(q).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.answerId").value(latest))
                .andExpect(jsonPath("$.data.question.questionVersionId").value(next))
                .andExpect(jsonPath("$.data.explanation").value("개정 해설"))
                .andExpect(jsonPath("$.data.revisionNotice").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void otherUsersAnswersNeverExposeDetailOrAffectCounts() throws Exception {
        long q = question();
        long other = account();
        preparation(other, company, 1);
        answer(other, set(other, 0), q, false, now);
        detail(q).andExpect(status().isNotFound());
        questions("").andExpect(status().isOk()).andExpect(jsonPath("$.data.allCount").value(0));
        long own = answer(user, set(user, 0), q, true, now.minusSeconds(1));
        detail(q).andExpect(status().isOk()).andExpect(jsonPath("$.data.answerId").value(own));
        questions("").andExpect(status().isOk()).andExpect(jsonPath("$.data.incorrectCount").value(0));
    }

    @Test
    void foreignOrMissingCompanyReturnsNotFound() throws Exception {
        long foreign = company("남의기업");
        preparation(account(), foreign, 1);
        request("/v1/reviews/quizzes?companyId=" + foreign).andExpect(status().isNotFound());
        request("/v1/reviews/quizzes?companyId=9007199254740991").andExpect(status().isNotFound());
        detail(9007199254740991L).andExpect(status().isNotFound());
    }

    @Test
    void removedServiceCompanyRemainsReviewableWithoutChangingRecords() throws Exception {
        long q = question();
        answer(user, set(user, 0), q, true, now);
        exclude(user, company);
        jdbc.update("update companies set is_service_company = false where id = ?", company);
        detail(q).andExpect(status().isOk());
        questions("").andExpect(status().isOk());
        companies("").andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].company.companyId").value(company));
        assertEquals(1, scalar("select count(*) from quiz_answers where user_id = ?", user));
    }

    @Test
    void malformedLogoIsNullButMissingBaseUrlRemainsUnavailable() throws Exception {
        jdbc.update("update companies set logo_key = '../logo' where id = ?", company);
        doThrow(new IllegalStateException("test-only")).when(logos).resolve("../logo");
        companies("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].company.logoUrl").value(org.hamcrest.Matchers.nullValue()));
        reset(logos);
        jdbc.update("update companies set logo_key = 'logo.png' where id = ?", company);
        companies("").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void storageFailureIsNotReportedAsEmptySuccess() throws Exception {
        doThrow(new TransientDataAccessResourceException("test-only"))
                .when(repository).counts(eq(user), eq(company));
        questions("").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void listCountAndRowsShareSnapshotDuringConcurrentAnswerCommit() throws Exception {
        long q = question();
        long existing = question();
        long set = set(user, 0);
        answer(user, set, existing, false, now.minusSeconds(60));
        var counted = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        doAnswer(invocation -> {
            var result = invocation.callRealMethod();
            counted.countDown();
            assertTrue(committed.await(5, TimeUnit.SECONDS));
            return result;
        }).when(repository).counts(user, company);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var response = pool.submit(() -> data(questions("").andExpect(status().isOk())));
            assertTrue(counted.await(5, TimeUnit.SECONDS));
            new TransactionTemplate(transactions).executeWithoutResult(tx -> answer(user, set, q, true, now));
            committed.countDown();
            var page = response.get(10, TimeUnit.SECONDS);
            assertEquals(1, page.get("allCount").longValue());
            assertEquals(1, page.get("content").size());
            assertEquals(existing, page.get("content").get(0).get("questionId").longValue());
        } finally {
            committed.countDown();
        }
        reset(repository);
        questions("").andExpect(status().isOk()).andExpect(jsonPath("$.data.allCount").value(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "page=1.5", "page=2147483648", "page=", "size=0", "size=101",
            "size=", "size=1&size=2", "other=1", "companyId=1"})
    void rejectsInvalidCompanyQueries(String query) throws Exception {
        companies("?" + query).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"filter=wrong", "filter=", "filter=ALL&filter=INCORRECT", "page=-1",
            "size=101", "size=0", "other=1", "page=2147483648"})
    void rejectsInvalidQuestionQueries(String query) throws Exception {
        questions("&" + query).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "companyId=0", "companyId=-1", "companyId=1.0", "companyId=9007199254740992",
            "companyId=abc", "companyId=1&companyId=2"})
    void requiresOneValidCompanyId(String query) throws Exception {
        request("/v1/reviews/quizzes?" + query).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownDetailQueriesBodiesAndUnsafeIds() throws Exception {
        long q = question();
        request("/v1/reviews/quizzes/" + q + "?answerId=1").andExpect(status().isBadRequest());
        for (String path : List.of("/v1/reviews/companies", "/v1/reviews/quizzes?companyId=" + company,
                "/v1/reviews/quizzes/" + q)) {
            mvc.perform(get(path).header("Authorization", "Bearer " + token)
                    .contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        }
        detail(0).andExpect(status().isBadRequest());
        detail(9007199254740992L).andExpect(status().isBadRequest());
    }

    @Test
    void allEndpointsRequireCurrentAuthenticationAndDisableCaching() throws Exception {
        long q = question();
        answer(user, set(user, 0), q, true, now);
        for (String path : List.of("/v1/reviews/companies", "/v1/reviews/quizzes?companyId=" + company,
                "/v1/reviews/quizzes/" + q)) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            request(path).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        }
        jdbc.update("update auth_sessions set revoked_at = ? where id = ?", utc(now), session);
        companies("").andExpect(status().isUnauthorized());
        questions("").andExpect(status().isUnauthorized());
        detail(q).andExpect(status().isUnauthorized());
    }

    @Test
    void realQuizSubmissionIsImmediatelyReviewableWithoutFinishingFive() throws Exception {
        for (int i = 0; i < 5; i++) question();
        long set = data(mvc.perform(post("/v1/companies/" + company + "/quiz-sets/today")
                        .header("Authorization", "Bearer " + token).header("Idempotency-Key", key()))
                .andExpect(status().isCreated())).get("quizSetId").longValue();
        var current = data(request("/v1/quiz-sets/" + set).andExpect(status().isOk())).get("currentItem");
        long q = current.get("questionId").longValue();
        detail(q).andExpect(status().isNotFound());
        mvc.perform(post("/v1/quiz-sets/" + set + "/answers").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key()).contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("itemId", current.get("itemId").longValue(),
                                "selectedOptionId", current.get("options").get(0).get("optionId").longValue()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("IN_PROGRESS"));
        detail(q).andExpect(status().isOk()).andExpect(jsonPath("$.data.isCorrect").value(true));
        questions("").andExpect(status().isOk()).andExpect(jsonPath("$.data.allCount").value(1));
    }

    private long account() {
        long id = insert("insert into users(nickname) values ('복습사용자')");
        users.add(id);
        return id;
    }

    private void login(long owner) {
        session = key();
        jdbc.update("insert into auth_sessions(id,user_id,created_at,expires_at) values (?,?,?,?)",
                session, owner, utc(now), utc(now.plusSeconds(3600)));
        token = tokens.issue(owner, session, 0, now, now.plusSeconds(3600));
    }

    private long company(String name) {
        long id = insert("insert into companies(dart_corp_code,name,is_service_company) values (?,?,true)",
                String.format("%08d", 99003600 + companies.size()), name);
        companies.add(id);
        return id;
    }

    private void preparation(long owner, long companyId, Integer slot) {
        jdbc.update("""
                insert into user_companies(user_id,company_id,active_slot,custom_job_name,excluded_at)
                values (?,?,?,'개발',?)
                """, owner, companyId, slot, slot == null ? utc(now) : null);
    }

    private void exclude(long owner, long companyId) {
        jdbc.update("update user_companies set active_slot = null, excluded_at = ? where user_id = ? and company_id = ?",
                utc(now), owner, companyId);
    }

    private long filing(Long corrected) {
        return insert("""
                insert into disclosures(company_id,receipt_no,report_name,report_type,disclosed_on,source_url,corrects_disclosure_id)
                values (?,?,'사업보고서','ANNUAL','2026-01-01','https://dart.fss.or.kr/',?)
                """, company, key().replace("-", "").substring(0, 20), corrected);
    }

    private long question() {
        long id = insert("insert into quiz_questions(company_id) values (?)", company);
        contentVersion(id, 1, "기업 문제 " + id, "연구개발에 투자하기 때문입니다.");
        jdbc.update("update quiz_questions set published_version_no = 1 where id = ?", id);
        return id;
    }

    private long contentVersion(long question, int no, String text, String explanation) {
        long id = insert("""
                insert into quiz_question_versions(question_id,company_id,version_no,status,question_text,explanation)
                values (?,?,?,'PUBLISHED',?,?)
                """, question, company, no, text, explanation);
        for (int i = 1; i <= 4; i++) {
            insert("insert into quiz_options(quiz_version_id,position,option_text,is_correct) values (?,?,?,?)",
                    id, i, "보기 " + i, i == 1);
        }
        jdbc.update("insert into quiz_evidence(quiz_version_id,section_id,company_id,quotation) values (?,?,?,?)",
                id, section, company, "연구개발에 투자합니다.");
        return id;
    }

    private long version(long question) {
        return scalar("""
                select v.id from quiz_question_versions v join quiz_questions q on q.id = v.question_id
                where q.id = ? and v.version_no = q.published_version_no
                """, question);
    }

    private long set(long owner, int days) {
        var date = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().plusDays(days);
        return insert("""
                insert into quiz_sets(user_id,company_id,business_date,mode,created_at)
                values (?,?,?,'NORMAL',?)
                """, owner, company, date, utc(now.plus(Duration.ofDays(days)).minusSeconds(1)));
    }

    private long item(long owner, long set, long question, int position) {
        return insert("""
                insert into quiz_set_items(set_id,user_id,company_id,question_id,quiz_version_id,position)
                values (?,?,?,?,?,?)
                """, set, owner, company, question, version(question), position);
    }

    private long answer(long owner, long set, long question, boolean correct, Instant submitted) {
        int position = (int) scalar("select count(*) + 1 from quiz_set_items where set_id = ?", set);
        long item = item(owner, set, question, position);
        long version = version(question);
        long option = scalar("select id from quiz_options where quiz_version_id = ? and position = ?", version, correct ? 1 : 2);
        return insert("""
                insert into quiz_answers(set_item_id,user_id,company_id,question_id,quiz_version_id,
                    selected_option_id,is_correct,request_key_hash,request_body_hmac,submitted_at)
                values (?,?,?,?,?,?,?,?,?,?)
                """, item, owner, company, question, version, option, correct,
                key().replace("-", "").repeat(2), "c".repeat(64), utc(submitted));
    }

    private ResultActions request(String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + token));
    }

    private ResultActions companies(String suffix) throws Exception { return request("/v1/reviews/companies" + suffix); }
    private ResultActions questions(String suffix) throws Exception { return request("/v1/reviews/quizzes?companyId=" + company + suffix); }
    private ResultActions detail(long question) throws Exception { return request("/v1/reviews/quizzes/" + question); }

    private JsonNode data(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private long scalar(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(c -> {
            var statement = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private static String key() { return UUID.randomUUID().toString(); }
}