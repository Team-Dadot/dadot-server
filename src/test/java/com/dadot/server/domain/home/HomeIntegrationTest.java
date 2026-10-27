package com.dadot.server.domain.home;

import com.dadot.server.domain.company.repository.PreparationCompanyRepository;
import com.dadot.server.domain.company.support.CompanyLogoUrlResolver;
import com.dadot.server.domain.home.repository.HomeRepository;
import com.dadot.server.domain.home.support.ProfileImageUrlResolver;
import com.dadot.server.domain.quiz.repository.QuizPlayRepository;
import com.dadot.server.global.security.AccessTokenIssuer;
import com.dadot.server.global.security.UserAccess;
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
import java.util.*;
import java.util.concurrent.*;

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.dart.worker-enabled=false",
        "app.dart.originals.enabled=false",
        "app.company-logo-base-url=https://images.example.invalid/companies",
        "app.profile-image-base-url=https://images.example.invalid/profiles"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HomeIntegrationTest {

    private static final String HOME = "/v1/users/me/dashboard";
    private static final String PREF = "/v1/users/me/preferences";
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired AccessTokenIssuer tokens;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean Clock clock;
    @MockitoSpyBean HomeRepository repository;
    @MockitoSpyBean PreparationCompanyRepository companiesRepository;
    @MockitoSpyBean QuizPlayRepository quizzes;
    @MockitoSpyBean UserAccess access;
    @MockitoSpyBean ProfileImageUrlResolver images;
    @MockitoSpyBean CompanyLogoUrlResolver logos;

    private final List<Long> companies = new ArrayList<>();
    private final List<Long> users = new ArrayList<>();
    private final List<Long> questions = new ArrayList<>();
    private final List<Long> versions = new ArrayList<>();
    private Instant now;
    private long user, first, second;
    private String session, token;
    private boolean safe;

    @BeforeEach
    void setup() {
        safe = false;
        assertEquals("dadot_test_db", jdbc.queryForObject("select database()", String.class));
        safe = true;
        reset(clock, repository, companiesRepository, quizzes, access, images, logos);
        companies.clear();
        users.clear();
        questions.clear();
        versions.clear();
        now = Instant.parse("2026-10-27T14:59:00Z");
        doReturn(now).when(clock).instant();
        user = account(true);
        first = company("첫기업");
        second = company("둘째기업");
        activate(first, 1);
        activate(second, 2);
        jdbc.update("insert into user_preferences(user_id,selected_company_id) values (?,?)",
                user, first);
        session = key();
        Instant issued = now.minusSeconds(1);
        jdbc.update("insert into auth_sessions(id,user_id,created_at,expires_at) values (?,?,?,?)",
                session, user, utc(issued), utc(issued.plusSeconds(3600)));
        token = tokens.issue(user, session, 0, issued, issued.plusSeconds(3600));
    }

    @AfterEach
    void cleanup() {
        if (!safe) return;
        reset(clock, repository, companiesRepository, quizzes, access, images, logos);
        for (long id : users) {
            for (String table : List.of("api_idempotency_requests", "user_preferences",
                    "quiz_answers", "quiz_set_items", "quiz_sets", "user_companies",
                    "user_onboarding", "auth_sessions")) {
                jdbc.update("delete from " + table + " where user_id = ?", id);
            }
        }
        for (long id : companies) {
            jdbc.update("update quiz_questions set published_version_no = null where company_id = ?", id);
            jdbc.update("delete from quiz_evidence where company_id = ?", id);
            jdbc.update("delete o from quiz_options o join quiz_question_versions v "
                    + "on v.id = o.quiz_version_id where v.company_id = ?", id);
            for (String table : List.of("quiz_question_versions", "quiz_questions",
                    "disclosure_sections", "disclosure_versions", "disclosures")) {
                jdbc.update("delete from " + table + " where company_id = ?", id);
            }
            jdbc.update("delete from companies where id = ?", id);
        }
        for (long id : users) jdbc.update("delete from users where id = ?", id);
    }

    @Test
    void returnsPersistedSelectionAndEmptyStatisticsWithoutCreatingAnything() throws Exception {
        dashboard("").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.profile.userId").value(user))
                .andExpect(jsonPath("$.data.profile.nickname").value("홈사용자"))
                .andExpect(jsonPath("$.data.profile.profileImageUrl").value(nullValue()))
                .andExpect(jsonPath("$.data.selectedCompany.company.companyId").value(first))
                .andExpect(jsonPath("$.data.selectedCompany.company.industry").value(nullValue()))
                .andExpect(jsonPath("$.data.selectedCompany.selected").value(true))
                .andExpect(jsonPath("$.data.selectedCompany.quizToday.submittedCount").value(0))
                .andExpect(jsonPath("$.data.quizAccuracy.firstAnsweredCount").value(0))
                .andExpect(jsonPath("$.data.quizAccuracy.percent").value(nullValue()))
                .andExpect(jsonPath("$.data.interviewEvaluationSummary.policyStatus").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.data.interviewEvaluationSummary.averageScorePercent").value(nullValue()))
                .andExpect(jsonPath("$.data.selectedCompany.interviewToday.limit").value(9))
                .andExpect(jsonPath("$.data.selectedCompany.interviewToday.questionRemaining").value(3))
                .andExpect(jsonPath("$.data.businessDate").value("2026-10-27"))
                .andExpect(jsonPath("$.data.nextResetAt").value("2026-10-27T15:00:00Z"))
                .andExpect(jsonPath("$.data.nextLearningAction").value("START_QUIZ"));
        assertEquals(0, scalar("select count(*) from quiz_sets where user_id = ?", user));
        verify(repository, never()).select(anyLong(), anyLong(), any());
    }

    @Test
    void previewDoesNotPersistSelectionAndSwitchDoesNotChangeCompanyVersions() throws Exception {
        dashboard("?companyId=" + second).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedCompany.company.companyId").value(second))
                .andExpect(jsonPath("$.data.selectedCompany.selected").value(false));
        assertEquals(first, selected());
        select(second).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.selectedCompanyId").value(second));
        select(second).andExpect(status().isOk());
        verify(repository, times(1)).select(eq(user), eq(second), any());
        assertEquals(2, scalar("select sum(version) from user_companies where user_id = ?", user));
        dashboard("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedCompany.company.companyId").value(second))
                .andExpect(jsonPath("$.data.selectedCompany.selected").value(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNSET", "PAST", "TODAY", "D3", "D4"})
    void scheduleUsesKstAndFocusIncludesTodayThroughDayThree(String scenario) throws Exception {
        LocalDate today = now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        LocalDate date = switch (scenario) {
            case "PAST" -> today.minusDays(1);
            case "TODAY" -> today;
            case "D3" -> today.plusDays(3);
            case "D4" -> today.plusDays(4);
            default -> null;
        };
        jdbc.update("update user_companies set interview_date = ? where user_id = ? and company_id = ?",
                date, user, first);
        dashboard("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedCompany.scheduleStatus").value(
                        scenario.equals("D3") || scenario.equals("D4") ? "UPCOMING" : scenario))
                .andExpect(jsonPath("$.data.selectedCompany.learningMode").value(
                        scenario.equals("TODAY") || scenario.equals("D3") ? "FOCUS" : "NORMAL"));
    }

    @Test
    void realQuizSubmissionAppearsImmediatelyAndSwitchingPreservesIt() throws Exception {
        seedBank();
        long set = data(mvc.perform(post("/v1/companies/" + first + "/quiz-sets/today")
                        .header("Authorization", bearer()).header("Idempotency-Key", key()))
                .andExpect(status().isCreated())).get("quizSetId").longValue();
        for (int i = 0; i < 5; i++) {
            JsonNode item = data(mvc.perform(get("/v1/quiz-sets/" + set)
                    .header("Authorization", bearer())).andExpect(status().isOk())).get("currentItem");
            long version = scalar("select quiz_version_id from quiz_set_items where id = ?",
                    item.get("itemId").longValue());
            long option = scalar("select id from quiz_options where quiz_version_id = ? and is_correct = true", version);
            mvc.perform(post("/v1/quiz-sets/" + set + "/answers")
                            .header("Authorization", bearer()).header("Idempotency-Key", key())
                            .contentType("application/json").content(mapper.writeValueAsString(Map.of(
                                    "itemId", item.get("itemId").longValue(), "selectedOptionId", option))))
                    .andExpect(status().isOk());
            if (i == 0) {
                select(second).andExpect(status().isOk());
                select(first).andExpect(status().isOk());
                dashboard("").andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.selectedCompany.quizToday.submittedCount").value(1))
                        .andExpect(jsonPath("$.data.nextLearningAction").value("CONTINUE_QUIZ"));
            }
        }
        dashboard("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedCompany.quizToday.completed").value(true))
                .andExpect(jsonPath("$.data.quizAccuracy.firstAnsweredCount").value(5))
                .andExpect(jsonPath("$.data.quizAccuracy.percent").value(100))
                .andExpect(jsonPath("$.data.nextLearningAction").value("START_INTERVIEW"));
        now = now.plusSeconds(120);
        doReturn(now).when(clock).instant();
        dashboard("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.businessDate").value("2026-10-28"))
                .andExpect(jsonPath("$.data.selectedCompany.quizToday.quizSetId").value(nullValue()))
                .andExpect(jsonPath("$.data.quizAccuracy.firstAnsweredCount").value(5))
                .andExpect(jsonPath("$.data.nextLearningAction").value("START_QUIZ"));
    }

    @Test
    void accuracyUsesFirstAnswerWithTimestampThenIdTieBreak() throws Exception {
        seedBank();
        long oldSet = quizSet(LocalDate.of(2026, 10, 25));
        long newSet = quizSet(LocalDate.of(2026, 10, 26));
        for (int i = 0; i < 5; i++) answer(oldSet, i, i < 3);
        for (int i = 0; i < 5; i++) answer(newSet, i, i >= 3);
        dashboard("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quizAccuracy.firstAnsweredCount").value(5))
                .andExpect(jsonPath("$.data.quizAccuracy.firstCorrectCount").value(3))
                .andExpect(jsonPath("$.data.quizAccuracy.percent").value(60));
        dashboard("?companyId=" + second).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quizAccuracy.firstAnsweredCount").value(0));
    }

    @Test
    void accuracyRoundsHalfUpRatherThanTruncating() throws Exception {
        seedBank();
        long a = quizSet(LocalDate.of(2026, 10, 25));
        long b = quizSet(LocalDate.of(2026, 10, 26));
        for (int i = 0; i < 8; i++) answer(i < 5 ? a : b, i, i == 0);
        dashboard("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quizAccuracy.firstAnsweredCount").value(8))
                .andExpect(jsonPath("$.data.quizAccuracy.firstCorrectCount").value(1))
                .andExpect(jsonPath("$.data.quizAccuracy.percent").value(13));
    }

    @Test
    void dateAndResetStayConsistentWhenClockCrossesMidnightDuringRead() throws Exception {
        doAnswer(invocation -> {
            doReturn(now.plusSeconds(120)).when(clock).instant();
            return invocation.callRealMethod();
        }).when(companiesRepository).quizzes(eq(user), any(), anyList());
        dashboard("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.businessDate").value("2026-10-27"))
                .andExpect(jsonPath("$.data.nextResetAt").value("2026-10-27T15:00:00Z"))
                .andExpect(jsonPath("$.data.selectedCompany.interviewToday.businessDate").value("2026-10-27"))
                .andExpect(jsonPath("$.data.selectedCompany.interviewToday.resetAt").value("2026-10-27T15:00:00Z"));
    }

    @Test
    void excludedReaddedCompanyKeepsQuizHistoryAndReturnsAtEnd() throws Exception {
        seedBank();
        answer(quizSet(LocalDate.of(2026, 10, 26)), 0, true);
        mvc.perform(delete("/v1/users/me/companies/" + first).header("Authorization", bearer()))
                .andExpect(status().isNoContent());
        assertEquals(second, selected());
        dashboard("?companyId=" + first).andExpect(status().isNotFound());
        select(first).andExpect(status().isNotFound());
        mvc.perform(post("/v1/users/me/companies").header("Authorization", bearer())
                        .header("Idempotency-Key", key()).contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("companyId", first,
                                "job", Map.of("customName", "다시개발")))))
                .andExpect(status().isCreated());
        select(first).andExpect(status().isOk());
        dashboard("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quizAccuracy.firstAnsweredCount").value(1));
    }

    @Test
    void unknownForeignAndExcludedCompaniesAreAllNotFound() throws Exception {
        long foreign = account(true);
        long hidden = company("비공개소유");
        jdbc.update("insert into user_companies(user_id,company_id,active_slot,custom_job_name) values (?,?,1,'타인직무')",
                foreign, hidden);
        long excluded = company("제외기업");
        activate(excluded, null);
        for (long id : List.of(hidden, excluded, 9007199254740991L)) {
            dashboard("?companyId=" + id).andExpect(status().isNotFound());
            select(id).andExpect(status().isNotFound());
        }
        assertEquals(first, selected());
    }

    @Test
    void requiresCompletedOnboardingEvenWhenItsRowIsMissing() throws Exception {
        jdbc.update("update user_onboarding set completed_at = null where user_id = ?", user);
        dashboard("").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"));
        select(second).andExpect(status().isConflict());
        jdbc.update("delete from user_onboarding where user_id = ?", user);
        dashboard("").andExpect(status().isConflict());
    }

    @Test
    void authenticationChecksRevocationAccountStateAndExpiry() throws Exception {
        mvc.perform(get(HOME)).andExpect(status().isUnauthorized());
        mvc.perform(patch(PREF).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        jdbc.update("update auth_sessions set revoked_at = ? where id = ?", utc(now), session);
        dashboard("").andExpect(status().isUnauthorized());
        select(second).andExpect(status().isUnauthorized());
        jdbc.update("update auth_sessions set revoked_at = null where id = ?", session);
        jdbc.update("update users set auth_version = auth_version + 1 where id = ?", user);
        dashboard("").andExpect(status().isUnauthorized());
        jdbc.update("update users set auth_version = 0 where id = ?", user);
        jdbc.update("update users set status = 'DELETING' where id = ?", user);
        dashboard("").andExpect(status().isUnauthorized());
        jdbc.update("update users set status = 'ACTIVE' where id = ?", user);
        doReturn(now.plusSeconds(3600)).when(clock).instant();
        select(second).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"?companyId=", "?companyId=0", "?companyId=-1", "?companyId=1.0",
            "?companyId=1e2", "?companyId=9007199254740992", "?companyId=99999999999999999",
            "?companyId=1&companyId=2", "?page=0", "?companyId=null"})
    void rejectsInvalidOrDuplicateQueries(String query) throws Exception {
        dashboard(query).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"selectedCompanyId\":null}",
            "{\"selectedCompanyId\":0}", "{\"selectedCompanyId\":-1}",
            "{\"selectedCompanyId\":1.5}", "{\"selectedCompanyId\":\"1\"}",
            "{\"selectedCompanyId\":9007199254740992}",
            "{\"selectedCompanyId\":9223372036854775808}",
            "{\"selectedCompanyId\":1,\"extra\":1}", "{\"companyId\":1}"})
    void rejectsInvalidPreferenceBodies(String body) throws Exception {
        mvc.perform(patch(PREF).header("Authorization", bearer())
                .contentType("application/json").content(body)).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsGetBodyWriteQueryAndMissingBody() throws Exception {
        mvc.perform(get(HOME).header("Authorization", bearer()).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(PREF + "?extra=1").header("Authorization", bearer())
                        .contentType("application/json").content("{\"selectedCompanyId\":" + second + "}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch(PREF).header("Authorization", bearer()).contentType("application/json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resolvesEncodedImageKeysAndFallsBackOnlyForMalformedKeys() throws Exception {
        jdbc.update("update users set profile_image_key = '회원/사진.png' where id = ?", user);
        jdbc.update("update companies set logo_key = 'logo.png' where id = ?", first);
        dashboard("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.profileImageUrl").value(
                        "https://images.example.invalid/profiles/%ED%9A%8C%EC%9B%90/%EC%82%AC%EC%A7%84.png"))
                .andExpect(jsonPath("$.data.selectedCompany.company.logoUrl").value(
                        "https://images.example.invalid/companies/logo.png"));
        jdbc.update("update users set profile_image_key = '../bad' where id = ?", user);
        jdbc.update("update companies set logo_key = '../bad' where id = ?", first);
        dashboard("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.profileImageUrl").value(nullValue()))
                .andExpect(jsonPath("$.data.selectedCompany.company.logoUrl").value(nullValue()));
        assertNull(new ProfileImageUrlResolver("").resolve(null));
        assertThrows(com.dadot.server.global.exception.BusinessException.class,
                () -> new ProfileImageUrlResolver("").resolve("valid.png"));
        assertThrows(IllegalStateException.class, () -> new ProfileImageUrlResolver("http://unsafe.invalid"));
        doThrow(new com.dadot.server.global.exception.BusinessException(
                com.dadot.server.global.exception.ErrorCode.SERVICE_UNAVAILABLE))
                .when(images).resolve("../bad");
        dashboard("").andExpect(status().isServiceUnavailable());
    }

    @Test
    void databaseFailuresReturn503AndSelectionIsRolledBack() throws Exception {
        doThrow(new TransientDataAccessResourceException("do not expose"))
                .when(quizzes).accuracy(user, first);
        dashboard("").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new TransientDataAccessResourceException("after update");
        }).when(repository).select(eq(user), eq(second), any());
        select(second).andExpect(status().isServiceUnavailable());
        assertEquals(first, selected());
    }

    @Test
    void sessionExpiryBeforeCommitRollsBackSelection() throws Exception {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            doReturn(now.plusSeconds(3600)).when(clock).instant();
            return null;
        }).when(repository).select(eq(user), eq(second), any());
        select(second).andExpect(status().isUnauthorized());
        assertEquals(first, selected());
    }

    @Test
    void readKeepsOneSnapshotWhileOtherTransactionChangesSelectionProfileAndQuiz() throws Exception {
        doAnswer(invocation -> {
            try (var pool = Executors.newSingleThreadExecutor()) {
                pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    jdbc.update("update user_preferences set selected_company_id = ? where user_id = ?", second, user);
                    jdbc.update("update users set nickname = '변경이름' where id = ?", user);
                    jdbc.update("update user_companies set custom_job_name = '변경직무',version = 2 where user_id = ? and company_id = ?", user, first);
                    quizSet(LocalDate.of(2026, 10, 27));
                })).get(5, TimeUnit.SECONDS);
            }
            return invocation.callRealMethod();
        }).when(companiesRepository).quizzes(eq(user), any(), anyList());
        dashboard("").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profile.nickname").value("홈사용자"))
                .andExpect(jsonPath("$.data.selectedCompany.company.companyId").value(first))
                .andExpect(jsonPath("$.data.selectedCompany.job.jobName").value("개발"))
                .andExpect(jsonPath("$.data.selectedCompany.version").value(1))
                .andExpect(jsonPath("$.data.selectedCompany.quizToday.quizSetId").value(nullValue()));
        assertEquals(second, selected());
    }

    @Test
    void selectionWaitingBehindExclusionRechecksActiveMembership() throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        doAnswer(invocation -> {
            reached.countDown();
            return invocation.callRealMethod();
        }).when(access).require(any(), eq(true));
        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<Integer> pending = new TransactionTemplate(transactions).execute(tx -> {
                jdbc.queryForObject("select id from users where id = ? for update", Long.class, user);
                Future<Integer> result = pool.submit(() -> select(second).andReturn().getResponse().getStatus());
                try {
                    assertTrue(reached.await(5, TimeUnit.SECONDS));
                    mvc.perform(delete("/v1/users/me/companies/" + second).header("Authorization", bearer()))
                            .andExpect(status().isNoContent());
                } catch (Exception ex) {
                    throw new AssertionError(ex);
                }
                return result;
            });
            assertEquals(404, Objects.requireNonNull(pending).get(10, TimeUnit.SECONDS));
        }
        assertEquals(first, selected());
    }

    @Test
    void concurrentSelectionAndExclusionAlwaysLeaveAnActiveSelection() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Future<Integer> a = pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return select(second).andReturn().getResponse().getStatus();
            });
            Future<Integer> b = pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return mvc.perform(delete("/v1/users/me/companies/" + second)
                        .header("Authorization", bearer())).andReturn().getResponse().getStatus();
            });
            start.countDown();
            assertTrue(Set.of(200, 404).contains(a.get(10, TimeUnit.SECONDS)));
            assertEquals(204, b.get(10, TimeUnit.SECONDS));
        }
        assertEquals(first, selected());
    }

    private long account(boolean completed) {
        long id = insert("insert into users(nickname) values ('홈사용자')");
        users.add(id);
        jdbc.update("insert into user_onboarding(user_id,completed_at) values (?,?)", id,
                completed ? utc(now) : null);
        return id;
    }

    private long company(String name) {
        long id = insert("insert into companies(dart_corp_code,name,is_service_company) values (?,?,true)",
                String.format(Locale.ROOT, "9840%04d", companies.size()), name);
        companies.add(id);
        return id;
    }

    private void activate(long company, Integer slot) {
        jdbc.update("insert into user_companies(user_id,company_id,active_slot,custom_job_name,excluded_at) values (?,?,?,'개발',?)",
                user, company, slot, slot == null ? utc(now) : null);
    }

    private long selected() {
        return scalar("select selected_company_id from user_preferences where user_id = ?", user);
    }

    private long quizSet(LocalDate date) {
        return insert("insert into quiz_sets(user_id,company_id,business_date,mode) values (?,?,?,'NORMAL')",
                user, first, date);
    }

    private void answer(long set, int index, boolean correct) {
        long version = versions.get(index);
        long item = insert("insert into quiz_set_items(set_id,user_id,company_id,question_id,quiz_version_id,position) values (?,?,?,?,?,?)",
                set, user, first, questions.get(index), version,
                scalar("select count(*) from quiz_set_items where set_id = ?", set) + 1);
        long option = scalar("select id from quiz_options where quiz_version_id = ? and is_correct = ? limit 1", version, correct);
        jdbc.update("insert into quiz_answers(set_item_id,user_id,company_id,question_id,quiz_version_id,selected_option_id,is_correct,request_key_hash,request_body_hmac,submitted_at) values (?,?,?,?,?,?,?,?,?,?)",
                item, user, first, questions.get(index), version, option, correct,
                key().replace("-", "") + key().replace("-", ""), "a".repeat(64), utc(now));
    }

    private void seedBank() {
        long disclosure = insert("""
                insert into disclosures(
                    company_id,receipt_no,report_name,report_type,
                    disclosed_on,source_url)
                values (?,?,'사업보고서','ANNUAL',
                    '2026-01-01','https://dart.fss.or.kr/')
                """,
                first,
                key().replace("-", "").substring(0, 20));

        long source = insert("""
                insert into disclosure_versions(
                    disclosure_id,company_id,version_no,status,
                    content_hash,extractor_version)
                values (?,?,1,'PUBLISHED',?,'home40-test')
                """,
                disclosure, first, "c".repeat(64));

        long section = insert("""
                insert into disclosure_sections(
                    disclosure_version_id,company_id,section_key,content_text)
                values (?,?,'business','사업 근거')
                """, source, first);

        for (int i = 0; i < 8; i++) {
            long q = insert(
                    "insert into quiz_questions(company_id) values (?)",
                    first);

            long v = insert("""
                    insert into quiz_question_versions(
                        question_id,company_id,version_no,status,
                        question_text,explanation)
                    values (?,?,1,'PUBLISHED',?,'사업 해설')
                    """, q, first, "문제 " + i);

            questions.add(q);
            versions.add(v);

            for (int n = 1; n <= 4; n++) {
                jdbc.update("""
                        insert into quiz_options(
                            quiz_version_id,position,option_text,is_correct)
                        values (?,?,?,?)
                        """,
                        v, n, "보기" + n, n == 1);
            }

            jdbc.update("""
                    insert into quiz_evidence(
                        quiz_version_id,section_id,company_id,quotation)
                    values (?,?,?,'사업 근거')
                    """, v, section, first);

            jdbc.update("""
                    update quiz_questions set published_version_no = 1
                    where id = ?
                    """, q);
        }
    }


    private ResultActions dashboard(String query) throws Exception {
        return mvc.perform(get(HOME + query).header("Authorization", bearer()));
    }

    private ResultActions select(long company) throws Exception {
        return mvc.perform(patch(PREF).header("Authorization", bearer()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("selectedCompanyId", company))));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data");
    }

    private long scalar(String sql, Object... args) {
        return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args));
    }

    private long insert(String sql, Object... args) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }

    private String bearer() { return "Bearer " + token; }
    private static String key() { return UUID.randomUUID().toString(); }
    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}