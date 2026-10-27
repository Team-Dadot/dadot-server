package com.dadot.server.domain.company;

import com.dadot.server.domain.auth.service.VerificationMaintenance;
import com.dadot.server.domain.company.repository.PreparationCompanyRepository;
import com.dadot.server.domain.company.repository.PreparationCompanyRequestStore;
import com.dadot.server.domain.company.support.CompanyLogoUrlResolver;
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
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "app.dart.worker-enabled=false",
        "app.dart.originals.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PreparationCompanyIntegrationTest {

    private static final String PATH = "/v1/users/me/companies";

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired AccessTokenIssuer tokens;
    @Autowired PlatformTransactionManager transactions;

    @MockitoSpyBean Clock clock;
    @MockitoSpyBean PreparationCompanyRepository repository;
    @MockitoSpyBean PreparationCompanyRequestStore requests;
    @MockitoSpyBean CompanyLogoUrlResolver logos;

    private final List<Long> companies = new ArrayList<>();
    private final List<Long> users = new ArrayList<>();

    private Instant now;
    private long user, first, job, category;
    private String session, token;
    private boolean safe;

    @BeforeEach
    void setup() {
        safe = false;
        assertEquals("dadot_test_db",
                jdbc.queryForObject("select database()", String.class));
        safe = true;

        reset(clock, repository, requests, logos);
        companies.clear();
        users.clear();

        now = Instant.parse("2026-10-27T14:59:00Z");
        doReturn(now).when(clock).instant();

        user = account(true);
        first = company("첫기업");
        activate(first, 1, "개발");

        jdbc.update("""
                insert into user_preferences(user_id,selected_company_id)
                values (?,?)
                """, user, first);

        category = insert(
                "insert into job_categories(name) values (?)",
                "38-" + key());

        job = insert("""
                insert into jobs(category_id,name,is_active)
                values (?, '백엔드', true)
                """, category);

        login(user);
    }

    @AfterEach
    void cleanup() {
        if (!safe) return;

        reset(clock, repository, requests, logos);

        for (long id : users) {
            jdbc.update("delete from api_idempotency_requests where user_id = ?", id);
            jdbc.update("delete from user_preferences where user_id = ?", id);
            jdbc.update("delete from quiz_answers where user_id = ?", id);
            jdbc.update("delete from quiz_set_items where user_id = ?", id);
            jdbc.update("delete from quiz_sets where user_id = ?", id);
            jdbc.update("delete from user_companies where user_id = ?", id);
            jdbc.update("delete from user_onboarding where user_id = ?", id);
            jdbc.update("delete from auth_sessions where user_id = ?", id);
        }

        for (long id : companies) {
            jdbc.update("update quiz_questions set published_version_no = null where company_id = ?", id);
            jdbc.update("delete from quiz_evidence where company_id = ?", id);
            jdbc.update("""
                    delete o from quiz_options o
                    join quiz_question_versions v on v.id = o.quiz_version_id
                    where v.company_id = ?
                    """, id);
            jdbc.update("delete from quiz_question_versions where company_id = ?", id);
            jdbc.update("delete from quiz_questions where company_id = ?", id);
            jdbc.update("delete from disclosure_sections where company_id = ?", id);
            jdbc.update("delete from disclosure_versions where company_id = ?", id);
            jdbc.update("delete from disclosures where company_id = ?", id);
            jdbc.update("delete from companies where id = ?", id);
        }

        jdbc.update("delete from jobs where category_id = ?", category);
        jdbc.update("delete from job_categories where id = ?", category);

        for (long id : users) {
            jdbc.update("delete from users where id = ?", id);
        }
    }

    @Test
    void listsOnlyOwnActiveCompaniesInSlotOrderWithRealPaging() throws Exception {
        long second = company("가나다");
        activate(second, 2, "기획");

        long excluded = company("제외");
        activate(excluded, null, "기획");

        long foreign = account(true);
        jdbc.update("""
                insert into user_companies(
                    user_id,company_id,active_slot,custom_job_name)
                values (?,?,1,'다른회원')
                """, foreign, second);

        list("?size=1")
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.content[0].company.companyId").value(first))
                .andExpect(jsonPath("$.data.content[0].selected").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true));

        list("?page=1&size=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].company.companyId").value(second))
                .andExpect(jsonPath("$.data.content[0].selected").value(false));

        list("?page=2147483647&size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNSET", "PAST", "TODAY", "D3", "D4"})
    void computesScheduleAndModeAtKstDateBoundary(String kind) throws Exception {
        LocalDate date = switch (kind) {
            case "UNSET" -> null;
            case "PAST" -> today().minusDays(1);
            case "TODAY" -> today();
            case "D3" -> today().plusDays(3);
            default -> today().plusDays(4);
        };

        jdbc.update("""
                update user_companies set interview_date = ?
                where user_id = ?
                """, date, user);

        var result = list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].learningMode")
                        .value(Set.of("TODAY", "D3").contains(kind)
                                ? "FOCUS" : "NORMAL"))
                .andExpect(jsonPath("$.data.content[0].scheduleStatus")
                        .value(switch (kind) {
                            case "UNSET" -> "UNSET";
                            case "PAST" -> "PAST";
                            case "TODAY" -> "TODAY";
                            default -> "UPCOMING";
                        }));

        var item = data(result).get("content").get(0);

        assertTrue(item.get("company").get("industry").isNull());
        assertTrue(item.get("company").get("logoUrl").isNull());
        assertTrue(item.get("quizToday").get("quizSetId").isNull());

        assertEquals(9, item.get("interviewToday").get("limit").intValue());
        assertEquals(0, item.get("interviewToday").get("used").intValue());
        assertEquals(3,
                item.get("interviewToday").get("questionRemaining").intValue());

        assertEquals("2026-10-27T15:00:00Z",
                item.get("interviewToday").get("resetAt").stringValue());

        if (date == null) {
            assertTrue(item.get("daysUntilInterview").isNull());
        }
    }

    @Test
    void addsDictionaryJobAndNormalizesCustomJobAtLastSlot() throws Exception {
        long second = company("추가");

        add(key(), addBody(second, Map.of("jobId", job), today().toString()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.job.jobName").value("백엔드"))
                .andExpect(jsonPath("$.data.job.isCustom").value(false))
                .andExpect(jsonPath("$.data.selected").value(false));

        assertEquals(2, slot(second));

        long third = company("직접입력");

        add(key(), addBody(third, Map.of("customName", "  마케팅  "), null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.job.jobName").value("마케팅"));

        assertEquals(3, slot(third));

        assertEquals(first, scalar("""
                select selected_company_id from user_preferences
                where user_id = ?
                """, user));
    }

    @Test
    void preventsMutationBeforeOnboardingButAllowsEmptyList() throws Exception {
        long incomplete = account(false);
        login(incomplete);

        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty());

        add(key(), addBody(company("신규"), Map.of("jobId", job), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ONBOARDING_INCOMPLETE"));
    }

    @Test
    void rejectsDuplicateLimitUnavailableCompanyAndInactiveJob() throws Exception {
        add(key(), addBody(first, Map.of("jobId", job), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMPANY_ALREADY_ACTIVE"));

        long hidden = company("비공개");
        jdbc.update("""
                update companies set is_service_company = false
                where id = ?
                """, hidden);

        add(key(), addBody(hidden, Map.of("jobId", job), null))
                .andExpect(status().isNotFound());

        long second = company("직무검증");
        jdbc.update("update jobs set is_active = false where id = ?", job);

        add(key(), addBody(second, Map.of("jobId", job), null))
                .andExpect(status().isNotFound());

        for (int i = 2; i <= 5; i++) {
            activate(company("준비" + i), i, "개발");
        }

        add(key(), addBody(second, Map.of("customName", "개발"), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMPANY_LIMIT_EXCEEDED"));
    }

    @Test
    void replaysOriginalResponseAcrossSessionDateExclusionAndPublicationChanges()
            throws Exception {
        long second = company("재추가");
        String key = key();
        String body = addBody(second, Map.of("jobId", job), today().toString());

        String original = add(key, body)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        exclude(second).andExpect(status().isNoContent());

        jdbc.update("""
                update companies set is_service_company = false
                where id = ?
                """, second);
        jdbc.update("update jobs set is_active = false where id = ?", job);

        now = now.plusSeconds(120);
        doReturn(now).when(clock).instant();
        login(user);

        assertEquals(original,
                add(key.toUpperCase(Locale.ROOT), body)
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString());

        assertNull(slot(second));
        assertEquals(2, version(second));

        add(key, addBody(second, Map.of("customName", "다른직무"), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void expiredKeyKeepsTombstoneAfterPayloadCleanup() throws Exception {
        long second = company("만료");
        String key = key();
        String body = addBody(second, Map.of("jobId", job), null);

        add(key, body).andExpect(status().isCreated());

        jdbc.update("""
                update api_idempotency_requests
                set created_at = ?, expires_at = ?
                where user_id = ? and operation = ?
                """,
                utc(now.minusSeconds(172800)),
                utc(now.minusSeconds(86400)),
                user,
                PreparationCompanyRequestStore.OPERATION);

        new VerificationMaintenance(jdbc, clock).clean();

        assertEquals(0, scalar("""
                select count(response_payload)
                from api_idempotency_requests where user_id = ?
                """, user));

        add(key, body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_EXPIRED"));

        assertEquals(1, version(second));
    }

    @Test
    void patchesOneCompanyAndDistinguishesOmittedDateFromNull() throws Exception {
        jdbc.update("""
                update user_companies set interview_date = ?
                where user_id = ?
                """, today().plusDays(2), user);

        patch(first,
                "{\"version\":1,\"job\":{\"jobId\":" + job + "}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companies[0].interviewDate")
                        .value(today().plusDays(2).toString()));

        patch(first, "{\"version\":2,\"interviewDate\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companies[0].interviewDate")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.companies[0].job.jobId").value(job));

        assertEquals(3, version(first));

        patch(first, "{\"version\":1,\"interviewDate\":null}")
                .andExpect(status().isConflict());
    }

    @Test
    void allActiveUpdatesJobsAtomicallyButOnlyPathCompanyDate() throws Exception {
        long second = company("둘째");
        activate(second, 2, "기획");

        LocalDate secondDate = today().plusDays(5);

        jdbc.update("""
                update user_companies set interview_date = ?
                where company_id = ?
                """, secondDate, second);

        patch(first, allBody(
                Map.of(first, 1L, second, 1L),
                today().plusDays(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companies.length()").value(2))
                .andExpect(jsonPath("$.data.companies[0].job.jobName").value("공통직무"))
                .andExpect(jsonPath("$.data.companies[1].job.jobName").value("공통직무"))
                .andExpect(jsonPath("$.data.companies[1].interviewDate")
                        .value(secondDate.toString()));

        assertEquals(2, version(first));
        assertEquals(2, version(second));
    }

    @Test
    void allActiveRejectsStaleMissingAndForeignSetsWithoutPartialChanges()
            throws Exception {
        long second = company("둘째");
        activate(second, 2, "기획");

        for (var expected : List.of(
                Map.of(first, 1L),
                Map.of(first, 1L, second, 2L),
                Map.of(first, 1L, 9000000L, 1L))) {
            patch(first, allBody(expected, null))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));

            assertEquals(1, version(first));
            assertEquals(1, version(second));
        }
    }

    @Test
    void preservesExistingPastDateButRejectsNewPastDateAndPastReactivation()
            throws Exception {
        LocalDate past = today().minusDays(2);

        jdbc.update("""
                update user_companies set interview_date = ?
                where user_id = ?
                """, past, user);

        patch(first,
                "{\"version\":1,\"interviewDate\":\"" + past + "\"}")
                .andExpect(status().isOk());

        patch(first,
                "{\"version\":2,\"interviewDate\":\""
                        + past.minusDays(1) + "\"}")
                .andExpect(status().isBadRequest());

        long second = company("과거재추가");
        activate(second, null, "개발");

        add(key(), addBody(second, Map.of("jobId", job), past.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exclusionCompactsSlotsIncrementsAffectedVersionsAndChangesSelection()
            throws Exception {
        long second = company("둘째");
        activate(second, 2, "개발");

        long third = company("셋째");
        activate(third, 3, "개발");

        jdbc.update("""
                update user_preferences set selected_company_id = ?
                where user_id = ?
                """, second, user);

        exclude(second)
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"));

        assertNull(slot(second));
        assertEquals(2, version(second));
        assertEquals(1, slot(first));
        assertEquals(1, version(first));
        assertEquals(2, slot(third));
        assertEquals(2, version(third));

        assertEquals(first, scalar("""
                select selected_company_id from user_preferences
                where user_id = ?
                """, user));

        add(key(), addBody(second, Map.of("customName", "재등록"), null))
                .andExpect(status().isCreated());

        assertEquals(3, slot(second));
        assertEquals(3, version(second));

        exclude(second).andExpect(status().isNoContent());

        assertEquals(first, scalar("""
                select selected_company_id from user_preferences
                where user_id = ?
                """, user));
    }

    @Test
    void minimumAndForeignOrExcludedCompaniesCannotBeRemovedOrPatched()
            throws Exception {
        exclude(first)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MIN_COMPANY_REQUIRED"));

        long second = company("제외");
        activate(second, null, "개발");

        exclude(second).andExpect(status().isNotFound());

        patch(second, "{\"version\":1,\"interviewDate\":null}")
                .andExpect(status().isNotFound());

        long foreign = account(true);

        jdbc.update("""
                insert into user_companies(
                    user_id,company_id,active_slot,custom_job_name)
                values (?,?,1,'개발')
                """, foreign, second);

        jdbc.update("""
                insert into user_preferences(user_id,selected_company_id)
                values (?,?)
                """, foreign, second);

        login(foreign);

        exclude(first).andExpect(status().isNotFound());

        patch(first, "{\"version\":1,\"interviewDate\":null}")
                .andExpect(status().isNotFound());
    }

    @Test
    void keepsRealQuizProgressAndReviewHistoryAfterEditExcludeAndReAdd()
            throws Exception {
        long second = company("유지기업");
        activate(second, 2, "개발");

        LocalDateTime firstAdded = jdbc.queryForObject("""
                select first_added_at from user_companies
                where user_id = ? and company_id = ?
                """, LocalDateTime.class, user, first);

        long set = play(2);

        patch(first, "{\"version\":1,\"interviewDate\":null}")
                .andExpect(status().isOk());

        exclude(first).andExpect(status().isNoContent());

        mvc.perform(get("/v1/reviews/companies")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[1].quizQuestionCount").value(2));

        add(key(), addBody(first, Map.of("jobId", job), null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.quizToday.quizSetId").value(set))
                .andExpect(jsonPath("$.data.quizToday.submittedCount").value(2));

        assertEquals(1,
                scalar("select count(*) from quiz_sets where user_id = ?", user));
        assertEquals(2,
                scalar("select count(*) from quiz_answers where user_id = ?", user));

        assertEquals(firstAdded, jdbc.queryForObject("""
                select first_added_at from user_companies
                where user_id = ? and company_id = ?
                """, LocalDateTime.class, user, first));
    }

    @Test
    void concurrentSameKeyAddsOnceAndDifferentKeysCannotActivateTwice()
            throws Exception {
        long second = company("동시");
        String body = addBody(second, Map.of("jobId", job), null);
        String key = key();

        assertEquals(
                List.of(201, 201),
                race(
                        () -> code(add(key, body)),
                        () -> code(add(key, body)))
                        .stream().sorted().toList());

        assertEquals(1, version(second));

        long third = company("다른키");
        String thirdBody = addBody(third, Map.of("jobId", job), null);

        assertEquals(
                List.of(201, 409),
                race(
                        () -> code(add(key(), thirdBody)),
                        () -> code(add(key(), thirdBody)))
                        .stream().sorted().toList());
    }

    @Test
    void concurrentAddsCannotExceedFiveAndConcurrentExcludesKeepOne()
            throws Exception {
        for (int i = 2; i <= 4; i++) {
            activate(company("준비" + i), i, "개발");
        }

        String a = addBody(company("경합A"), Map.of("jobId", job), null);
        String b = addBody(company("경합B"), Map.of("jobId", job), null);

        assertEquals(
                List.of(201, 409),
                race(
                        () -> code(add(key(), a)),
                        () -> code(add(key(), b)))
                        .stream().sorted().toList());

        assertEquals(5, scalar("""
                select count(*) from user_companies
                where user_id = ? and active_slot is not null
                """, user));

        var ids = jdbc.query("""
                select company_id from user_companies
                where user_id = ? and active_slot > 2
                order by active_slot desc
                """, (rs, n) -> rs.getLong(1), user);

        for (long id : ids) {
            exclude(id).andExpect(status().isNoContent());
        }

        long second = jdbc.queryForObject("""
                select company_id from user_companies
                where user_id = ? and active_slot = 2
                """, Long.class, user);

        assertEquals(
                List.of(204, 409),
                race(
                        () -> code(exclude(first)),
                        () -> code(exclude(second)))
                        .stream().sorted().toList());
    }

    @Test
    void concurrentExpectedVersionOnlyCommitsOnePatch() throws Exception {
        String body = "{\"version\":1,\"job\":{\"customName\":\"수정\"}}";

        assertEquals(
                List.of(200, 409),
                race(
                        () -> code(patch(first, body)),
                        () -> code(patch(first, body)))
                        .stream().sorted().toList());

        assertEquals(2, version(first));
    }

    @Test
    void failedIdempotencyPersistenceRollsBackActivation() throws Exception {
        long second = company("롤백");

        doThrow(new TransientDataAccessResourceException("test failure"))
                .when(requests).complete(anyLong(), any(), any(), any());

        add(key(), addBody(second, Map.of("jobId", job), null))
                .andExpect(status().isServiceUnavailable());

        assertEquals(0, scalar("""
                select count(*) from user_companies
                where user_id = ? and company_id = ?
                """, user, second));

        assertEquals(0, scalar("""
                select count(*) from api_idempotency_requests
                where user_id = ?
                """, user));
    }

    @Test
    void failedAllActiveUpdateAndSelectionChangeRollBackEveryAffectedRow()
            throws Exception {
        long second = company("둘째");
        activate(second, 2, "기획");

        doAnswer(invocation -> {
            var state = invocation.getArgument(
                    1, PreparationCompanyRepository.State.class);

            if (state.companyId() == second) {
                throw new TransientDataAccessResourceException("test failure");
            }

            return invocation.callRealMethod();
        }).when(repository).update(anyLong(), any(), any(), any());

        patch(first, allBody(Map.of(first, 1L, second, 1L), null))
                .andExpect(status().isServiceUnavailable());

        assertEquals(1, version(first));
        assertEquals(1, version(second));

        reset(repository);

        doThrow(new TransientDataAccessResourceException("test failure"))
                .when(repository).select(anyLong(), anyLong(), any());

        exclude(first).andExpect(status().isServiceUnavailable());

        assertEquals(1, slot(first));
        assertEquals(2, slot(second));
        assertEquals(1, version(first));
        assertEquals(1, version(second));

        assertEquals(first, scalar("""
                select selected_company_id from user_preferences
                where user_id = ?
                """, user));
    }

    @Test
    void expiredSessionBeforeCommitRollsBackAddedCompanyAndRequest()
            throws Exception {
        long second = company("세션만료");

        doAnswer(invocation -> {
            invocation.callRealMethod();
            doReturn(now.plusSeconds(3601)).when(clock).instant();
            return null;
        }).when(requests).complete(anyLong(), any(), any(), any());

        add(key(), addBody(second, Map.of("jobId", job), null))
                .andExpect(status().isUnauthorized());

        assertEquals(0, scalar("""
                select count(*) from user_companies
                where user_id = ? and company_id = ?
                """, user, second));
    }

    @Test
    void readUsesOneSnapshotWhileAnotherTransactionCommitsQuizAndJob()
            throws Exception {
        doAnswer(invocation -> {
            try (var pool = Executors.newSingleThreadExecutor()) {
                pool.submit(() ->
                        new TransactionTemplate(transactions)
                                .executeWithoutResult(tx -> {
                                    jdbc.update("""
                                            update user_companies
                                            set custom_job_name = '변경', version = 2
                                            where user_id = ?
                                            """, user);

                                    jdbc.update("""
                                            insert into quiz_sets(
                                                user_id,company_id,business_date,mode)
                                            values (?,?,?,'NORMAL')
                                            """, user, first, today());
                                })).get(5, TimeUnit.SECONDS);
            }

            return invocation.callRealMethod();
        }).when(repository).quizzes(anyLong(), any(), anyList());

        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].version").value(1))
                .andExpect(jsonPath("$.data.content[0].job.jobName").value("개발"))
                .andExpect(jsonPath("$.data.content[0].quizToday.quizSetId")
                        .value(org.hamcrest.Matchers.nullValue()));

        assertEquals(2, version(first));
    }

    @Test
    void publicCompanyIsRecheckedAfterWaitingForAdminLock() throws Exception {
        long second = company("공개경합");
        String body = addBody(second, Map.of("jobId", job), null);
        var reached = new CountDownLatch(1);

        doAnswer(invocation -> {
            reached.countDown();
            return invocation.callRealMethod();
        }).when(repository).lockPublicCompany(second);

        try (var pool = Executors.newSingleThreadExecutor()) {
            Future<Integer> future =
                    new TransactionTemplate(transactions).execute(tx -> {
                        jdbc.update("""
                                update companies set is_service_company = false
                                where id = ?
                                """, second);

                        Future<Integer> result =
                                pool.submit(() -> code(add(key(), body)));

                        try {
                            assertTrue(reached.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException ex) {
                            throw new AssertionError(ex);
                        }

                        return result;
                    });

            assertEquals(404,
                    Objects.requireNonNull(future).get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void versionOverflowRollsBackExclusionAndAllActiveUpdate() throws Exception {
        long second = company("버전상한");
        activate(second, 2, "기획");

        jdbc.update("""
                update user_companies set version = ?
                where user_id = ? and company_id = ?
                """, Long.MAX_VALUE, user, second);

        exclude(first).andExpect(status().isServiceUnavailable());

        assertEquals(1, slot(first));
        assertEquals(2, slot(second));
        assertEquals(1, version(first));
        assertEquals(Long.MAX_VALUE, version(second));

        patch(first, allBody(
                Map.of(first, 1L, second, Long.MAX_VALUE), null))
                .andExpect(status().isServiceUnavailable());

        assertEquals(1, version(first));
    }

    @Test
    void customJobLengthUsesCodePointsAndUnknownJobsRemainNotFound()
            throws Exception {
        long second = company("문자수");

        add(key(), addBody(
                second, Map.of("customName", "😀".repeat(51)), null))
                .andExpect(status().isBadRequest());

        add(key(), addBody(
                second, Map.of("customName", "😀".repeat(50)), null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.job.jobName")
                        .value("😀".repeat(50)));

        long third = company("없는직무");

        add(key(), addBody(third, Map.of("jobId", 9000000L), null))
                .andExpect(status().isNotFound());

        jdbc.update("update jobs set is_active = false where id = ?", job);

        patch(first, "{\"version\":1,\"job\":{\"jobId\":" + job + "}}")
                .andExpect(status().isNotFound());
    }

    @Test
    void completedQuizIsReportedAndYesterdaySetIsNotTodaysProgress()
            throws Exception {
        long set = play(5);

        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].quizToday.completed").value(true))
                .andExpect(jsonPath("$.data.content[0].quizToday.submittedCount").value(5))
                .andExpect(jsonPath("$.data.content[0].quizToday.quizSetId").value(set));

        now = now.plusSeconds(120);
        doReturn(now).when(clock).instant();

        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].quizToday.quizSetId")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.content[0].quizToday.submittedCount").value(0));
    }

    @Test
    void repeatedExpectedIdsAndUnknownWriteQueriesAreRejected() throws Exception {
        String expected = "{\"companyId\":" + first + ",\"version\":1}";

        patch(first,
                "{\"version\":1,\"job\":{\"customName\":\"개발\"},"
                        + "\"jobScope\":\"ALL_ACTIVE\","
                        + "\"expectedCompanyVersions\":["
                        + expected + "," + expected + "]}")
                .andExpect(status().isBadRequest());

        mvc.perform(post(PATH + "?extra=1")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", key())
                        .contentType("application/json")
                        .content(addBody(first, Map.of("jobId", job), null)))
                .andExpect(status().isBadRequest());

        mvc.perform(delete(PATH + "/" + first + "?version=1")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void corruptLogoFallsBackButMissingBaseAndDatabaseFailuresStayUnavailable()
            throws Exception {
        jdbc.update("""
                update companies set logo_key = '../bad.png'
                where id = ?
                """, first);

        doThrow(new IllegalStateException("test invalid key"))
                .when(logos).resolve("../bad.png");

        list("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].company.logoUrl")
                        .value(org.hamcrest.Matchers.nullValue()));

        reset(logos);

        // base URL이 없으면 설정 장애를 계속 반환한다.
        list("").andExpect(status().isServiceUnavailable());

        jdbc.update("update companies set logo_key = null where id = ?", first);

        doThrow(new TransientDataAccessResourceException("test failure"))
                .when(repository).rows(user);

        list("")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data")
                        .value(org.hamcrest.Matchers.nullValue()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "null",
            "[]",
            "{\"companyId\":0,\"job\":{\"customName\":\"개발\"}}",
            "{\"companyId\":1.5,\"job\":{\"customName\":\"개발\"}}",
            "{\"companyId\":\"1\",\"job\":{\"customName\":\"개발\"}}",
            "{\"companyId\":9007199254740992,\"job\":{\"customName\":\"개발\"}}",
            "{\"companyId\":1,\"job\":{}}",
            "{\"companyId\":1,\"job\":{\"jobId\":1,\"customName\":\"개발\"}}",
            "{\"companyId\":1,\"job\":null}",
            "{\"companyId\":1,\"job\":{\"customName\":\"   \"}}",
            "{\"companyId\":1,\"job\":{\"customName\":\"개발\"},\"userId\":1}",
            "{\"companyId\":1,\"job\":{\"customName\":\"개발\"},\"interviewDate\":\"2026-02-30\"}",
            "{\"companyId\":1,\"job\":{\"customName\":\"개발\"},\"interviewDate\":\"2026-1-1\"}"
    })
    void rejectsMalformedAddRequests(String body) throws Exception {
        add(key(), body).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "null",
            "[]",
            "{\"version\":1}",
            "{\"version\":0,\"interviewDate\":null}",
            "{\"version\":1,\"job\":null}",
            "{\"version\":1,\"jobScope\":\"ALL_ACTIVE\",\"interviewDate\":null}",
            "{\"version\":1,\"job\":{\"customName\":\"개발\"},\"jobScope\":\"ALL_ACTIVE\"}",
            "{\"version\":1,\"interviewDate\":null,\"expectedCompanyVersions\":[]}",
            "{\"version\":1,\"interviewDate\":null,\"extra\":1}"
    })
    void rejectsMalformedPatchRequests(String body) throws Exception {
        patch(first, body).andExpect(status().isBadRequest());
    }

    @Test
    void validatesQueryBodiesKeysIdRangesAndCurrentAuthentication()
            throws Exception {
        for (String query : List.of(
                "?page=-1", "?size=0", "?size=101",
                "?page=2147483648", "?size=1&size=2", "?other=1")) {
            list(query).andExpect(status().isBadRequest());
        }

        mvc.perform(get(PATH)
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mvc.perform(delete(PATH + "/" + first)
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());

        add("bad", addBody(first, Map.of("jobId", job), null))
                .andExpect(status().isBadRequest());

        patch(9007199254740992L,
                "{\"version\":1,\"interviewDate\":null}")
                .andExpect(status().isBadRequest());

        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());

        mvc.perform(post(PATH)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch(PATH + "/" + first)
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(delete(PATH + "/" + first))
                .andExpect(status().isUnauthorized());

        jdbc.update("""
                update auth_sessions set revoked_at = ?
                where id = ?
                """, utc(now), session);

        list("").andExpect(status().isUnauthorized());
    }

    private long account(boolean complete) {
        long id = insert(
                "insert into users(nickname) values ('기업관리사용자')");
        users.add(id);

        jdbc.update("""
                insert into user_onboarding(user_id,completed_at)
                values (?,?)
                """, id, complete ? utc(now) : null);

        return id;
    }

    private long company(String name) {
        long id = insert("""
                insert into companies(
                    dart_corp_code,name,is_service_company)
                values (?,?,true)
                """,
                String.format(
                        Locale.ROOT, "9838%04d", companies.size()),
                name);

        companies.add(id);
        return id;
    }

    private void activate(
            long company,
            Integer slot,
            String custom
    ) {
        jdbc.update("""
                insert into user_companies(
                    user_id,company_id,active_slot,custom_job_name,excluded_at)
                values (?,?,?,?,?)
                """,
                user, company, slot, custom,
                slot == null ? utc(now) : null);
    }

    private void login(long owner) {
        session = key();
        var issued = now.minusSeconds(1);

        jdbc.update("""
                insert into auth_sessions(
                    id,user_id,created_at,expires_at)
                values (?,?,?,?)
                """,
                session, owner,
                utc(issued), utc(issued.plusSeconds(3600)));

        token = tokens.issue(
                owner, session, 0,
                issued, issued.plusSeconds(3600));
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
                values (?,?,1,'PUBLISHED',?,'company38-test')
                """,
                disclosure, first, "c".repeat(64));

        long section = insert("""
                insert into disclosure_sections(
                    disclosure_version_id,company_id,section_key,content_text)
                values (?,?,'business','사업 근거')
                """, source, first);

        for (int i = 0; i < 5; i++) {
            long q = insert(
                    "insert into quiz_questions(company_id) values (?)",
                    first);

            long v = insert("""
                    insert into quiz_question_versions(
                        question_id,company_id,version_no,status,
                        question_text,explanation)
                    values (?,?,1,'PUBLISHED',?,'사업 해설')
                    """, q, first, "문제 " + i);

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

    private String addBody(
            long company,
            Map<String, Object> job,
            String date
    ) {
        var body = new LinkedHashMap<String, Object>();
        body.put("companyId", company);
        body.put("job", job);
        body.put("interviewDate", date);
        return mapper.writeValueAsString(body);
    }

    private long play(int count) throws Exception {
        seedBank();

        long set = data(
                mvc.perform(post(
                                "/v1/companies/" + first + "/quiz-sets/today")
                                .header("Authorization", bearer())
                                .header("Idempotency-Key", key()))
                        .andExpect(status().isCreated()))
                .get("quizSetId").longValue();

        for (int i = 0; i < count; i++) {
            var item = data(
                    mvc.perform(get("/v1/quiz-sets/" + set)
                                    .header("Authorization", bearer()))
                            .andExpect(status().isOk()))
                    .get("currentItem");

            mvc.perform(post("/v1/quiz-sets/" + set + "/answers")
                            .header("Authorization", bearer())
                            .header("Idempotency-Key", key())
                            .contentType("application/json")
                            .content(mapper.writeValueAsString(Map.of(
                                    "itemId",
                                    item.get("itemId").longValue(),
                                    "selectedOptionId",
                                    item.get("options").get(0)
                                            .get("optionId").longValue()))))
                    .andExpect(status().isOk());
        }

        return set;
    }

    private String allBody(
            Map<Long, Long> expected,
            LocalDate date
    ) {
        var body = new LinkedHashMap<String, Object>();
        body.put("version", 1);
        body.put("job", Map.of("customName", "공통직무"));
        body.put("jobScope", "ALL_ACTIVE");
        body.put("expectedCompanyVersions",
                expected.entrySet().stream()
                        .map(e -> Map.of(
                                "companyId", e.getKey(),
                                "version", e.getValue()))
                        .toList());

        if (date != null) {
            body.put("interviewDate", date.toString());
        }

        return mapper.writeValueAsString(body);
    }

    private ResultActions list(String query) throws Exception {
        return mvc.perform(get(PATH + query)
                .header("Authorization", bearer()));
    }

    private ResultActions add(String key, String body) throws Exception {
        return mvc.perform(post(PATH)
                .header("Authorization", bearer())
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body));
    }

    private ResultActions patch(long company, String body) throws Exception {
        return mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch(PATH + "/" + company)
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content(body));
    }

    private ResultActions exclude(long company) throws Exception {
        return mvc.perform(delete(PATH + "/" + company)
                .header("Authorization", bearer()));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return mapper.readTree(
                        result.andReturn().getResponse().getContentAsString())
                .get("data");
    }

    private int code(ResultActions result) {
        return result.andReturn().getResponse().getStatus();
    }

    private String bearer() {
        return "Bearer " + token;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private LocalDate today() {
        return now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
    }

    private static LocalDateTime utc(Instant now) {
        return LocalDateTime.ofInstant(now, ZoneOffset.UTC);
    }

    private long version(long company) {
        return scalar("""
                select version from user_companies
                where user_id = ? and company_id = ?
                """, user, company);
    }

    private Integer slot(long company) {
        return jdbc.queryForObject("""
                select active_slot from user_companies
                where user_id = ? and company_id = ?
                """, Integer.class, user, company);
    }

    private long scalar(String sql, Object... args) {
        return Objects.requireNonNull(
                jdbc.queryForObject(sql, Long.class, args));
    }

    private long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();

        jdbc.update(c -> {
            var s = c.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS);

            for (int i = 0; i < args.length; i++) {
                s.setObject(i + 1, args[i]);
            }

            return s;
        }, key);

        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private <T> List<T> race(
            Callable<T> first,
            Callable<T> second
    ) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream()
                    .map(action -> pool.submit(() -> {
                        ready.countDown();
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        return action.call();
                    }))
                    .toList();

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            return List.of(
                    futures.get(0).get(15, TimeUnit.SECONDS),
                    futures.get(1).get(15, TimeUnit.SECONDS));
        }
    }
}