package com.dadot.server.domain.onboarding;

import com.dadot.server.domain.onboarding.dto.request.OnboardingPatchRequest;
import com.dadot.server.domain.onboarding.dto.response.OnboardingCompleteResponse;
import com.dadot.server.domain.onboarding.dto.response.OnboardingResponse;
import com.dadot.server.domain.onboarding.model.OnboardingStep;
import com.dadot.server.domain.onboarding.repository.OnboardingRepository;
import com.dadot.server.domain.onboarding.repository.OnboardingRequestStore;
import com.dadot.server.domain.onboarding.service.OnboardingService;
import com.dadot.server.domain.auth.service.VerificationMaintenance;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.global.security.AccessTokenIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OnboardingIntegrationTest.TestConfig.class)
class OnboardingIntegrationTest {

    private static final String PATH = "/v1/users/me/onboarding";
    private static final AtomicInteger CORP_CODES = new AtomicInteger(80000000);

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired AccessTokenIssuer tokens;
    @Autowired OnboardingService service;
    @Autowired MutableClock clock;

    @MockitoSpyBean OnboardingRepository repository;
    @MockitoSpyBean OnboardingRequestStore requests;
    @MockitoSpyBean PlatformTransactionManager transactionManager;

    private final List<Long> users = new ArrayList<>();
    private final List<Long> companies = new ArrayList<>();

    private long userId;
    private long firstCompany;
    private long secondCompany;
    private long categoryId;
    private long jobId;
    private String sessionId;
    private String token;
    private boolean testDatabase;

    @BeforeEach
    void setup() {
        testDatabase = false;

        assertEquals(
                "dadot_test_db",
                jdbc.queryForObject("select database()", String.class)
        );

        testDatabase = true;
        reset(repository, requests, transactionManager);
        clock.set(Instant.parse("2026-10-24T14:30:00Z"));

        userId = member();
        sessionId = session(userId);
        token = token(userId, sessionId);
        firstCompany = company(true);
        secondCompany = company(true);

        categoryId = insert(
                "insert into job_categories(name) values (?)",
                "온보딩-" + UUID.randomUUID()
        );

        jobId = insert(
                "insert into jobs(category_id, name) values (?, '해외영업')",
                categoryId
        );
    }

    @AfterEach
    void cleanup() {
        if (!testDatabase) {
            return;
        }

        reset(repository, requests, transactionManager);

        for (long id : users) {
            jdbc.update("delete from api_idempotency_requests where user_id = ?", id);
            jdbc.update("delete from user_preferences where user_id = ?", id);
            jdbc.update("delete from user_companies where user_id = ?", id);
            jdbc.update("delete from notification_settings where user_id = ?", id);
            jdbc.update("delete from user_devices where user_id = ?", id);

            jdbc.update(
                    "update personal_materials set current_version_no = null where user_id = ?",
                    id
            );

            jdbc.update("delete from material_versions where user_id = ?", id);
            jdbc.update("delete from personal_materials where user_id = ?", id);
            jdbc.update("delete from user_onboarding where user_id = ?", id);
            jdbc.update("delete from auth_sessions where user_id = ?", id);
            jdbc.update("delete from users where id = ?", id);
        }

        for (long id : companies) {
            jdbc.update("delete from companies where id = ?", id);
        }

        jdbc.update("delete from jobs where category_id = ?", categoryId);
        jdbc.update("delete from job_categories where id = ?", categoryId);
    }

    @Test
    void requiresAuthenticationOnAllThreeEndpoints() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isUnauthorized());

        mvc.perform(patch(PATH)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post(PATH + "/complete")
                        .header("Idempotency-Key", key()))
                .andExpect(status().isUnauthorized());

        mvc.perform(get(PATH)
                        .header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsAnInitialStateWithoutCreatingBusinessRows() throws Exception {
        mvc.perform(get(PATH).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.currentStep").value("COMPANIES"))
                .andExpect(jsonPath("$.data.completedSteps").isEmpty())
                .andExpect(jsonPath("$.data.companyIds").isEmpty())
                .andExpect(jsonPath("$.data.assignments").isEmpty())
                .andExpect(jsonPath("$.data.experienceCount").value(0))
                .andExpect(jsonPath("$.data.notificationSettings.enabled").value(false))
                .andExpect(jsonPath("$.data.notificationSettings.scheduledTime").isEmpty())
                .andExpect(jsonPath("$.data.notificationSettings.timeZone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.data.notificationSettings.currentDevice").isEmpty())
                .andExpect(jsonPath("$.data.completed").value(false));

        assertEquals(0, count("user_companies"));
        assertEquals(0, count("notification_settings"));
        assertEquals(0, count("user_onboarding"));
    }

    @Test
    void savesEveryStepAndCompletesAtomicallyInSelectionOrder() throws Exception {
        patchOk(
                "COMPANIES",
                Map.of("companyIds", List.of(secondCompany, firstCompany))
        ).get("assignments")
                .forEach(value -> assertTrue(value.get("job").isNull()));

        patchOk("JOBS", Map.of("assignments", List.of(
                assignment(
                        firstCompany,
                        Map.of("customName", "  AI Research  ")
                ),
                assignment(secondCompany, Map.of("jobId", jobId))
        )));

        var saved = patchOk("DATES", Map.of("dates", List.of(
                date(firstCompany, null),
                date(secondCompany, "2026-10-24")
        )));

        assertEquals(
                "AI Research",
                saved.get("assignments").get(1)
                        .get("job").get("jobName").stringValue()
        );

        assertTrue(
                saved.get("assignments").get(1)
                        .get("job").get("isCustom").booleanValue()
        );

        assertTrue(
                saved.get("assignments").get(1)
                        .get("job").get("jobId").isNull()
        );

        patchOk("EXPERIENCE", Map.of("experienceIds", List.of()));
        patchOk("NOTIFICATION_GUIDANCE", Map.of("guidanceCompleted", true));

        assertEquals(0, count("user_companies"));

        mvc.perform(post(PATH + "/complete")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", key()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completed").value(true))
                .andExpect(jsonPath("$.data.selectedCompanyId").value(secondCompany));

        assertEquals(2, count("user_companies"));

        assertEquals(secondCompany, jdbc.queryForObject(
                "select selected_company_id from user_preferences where user_id = ?",
                Long.class,
                userId
        ));

        assertEquals(secondCompany, jdbc.queryForObject(
                "select company_id from user_companies where user_id = ? and active_slot = 1",
                Long.class,
                userId
        ));

        assertEquals("AI Research", jdbc.queryForObject(
                "select custom_job_name from user_companies where user_id = ? and company_id = ?",
                String.class,
                userId,
                firstCompany
        ));

        assertEquals(1, jdbc.queryForObject(
                "select version from user_companies where user_id = ? and company_id = ?",
                Integer.class,
                userId,
                firstCompany
        ));

        var complete = snapshot();

        assertTrue(complete.completed());
        assertEquals(OnboardingStep.COMPLETE, complete.currentStep());
        assertEquals(7, complete.version());
        assertFalse(complete.notificationSettings().enabled());

        assertEquals(0, jdbc.queryForObject(
                "select count(*) from jobs where name = 'AI Research'",
                Integer.class
        ));
    }

    @Test
    void restoresTheDraftAndSupportsAFirstWriteForExistingSignupRows() {
        jdbc.update("insert into user_onboarding(user_id) values (?)", userId);

        chooseCompanies();

        var state = snapshot();

        assertEquals(2, state.version());
        assertEquals(
                List.of(firstCompany, secondCompany),
                state.companyIds()
        );
        assertEquals(OnboardingStep.JOBS, state.currentStep());
        assertEquals(0, count("user_companies"));
    }

    @Test
    void changingCompaniesKeepsRetainedDraftValuesAndInvalidatesLaterSteps() {
        ready();

        long replacement = company(true);

        save(
                "COMPANIES",
                Map.of("companyIds", List.of(secondCompany, replacement))
        );

        var state = snapshot();

        assertEquals(
                List.of(OnboardingStep.COMPANIES),
                state.completedSteps()
        );
        assertNotNull(state.assignments().get(0).job());
        assertNull(state.assignments().get(1).job());
        assertFalse(state.companyIds().contains(firstCompany));

        assertNull(jdbc.queryForObject(
                "select notification_guidance_completed_at from user_onboarding where user_id = ?",
                LocalDateTime.class,
                userId
        ));

        assertBusiness(
                ErrorCode.ONBOARDING_INCOMPLETE,
                () -> complete(key())
        );
    }

    @Test
    void resavingTheSameCompaniesDoesNotInvalidateConfirmedSteps() {
        ready();

        save(
                "COMPANIES",
                Map.of("companyIds", List.of(firstCompany, secondCompany))
        );

        assertEquals(5, snapshot().completedSteps().size());
        assertTrue(complete(key()).completed());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "null", "[]",
            "{\"step\":\"COMPLETE\",\"version\":1,\"payload\":{}}",
            "{\"step\":\"UNKNOWN\",\"version\":1,\"payload\":{}}",
            "{\"step\":\"COMPANIES\",\"version\":1.5,\"payload\":{\"companyIds\":[1]}}",
            "{\"step\":\"COMPANIES\",\"version\":\"1\",\"payload\":{\"companyIds\":[1]}}",
            "{\"step\":\"COMPANIES\",\"version\":0,\"payload\":{\"companyIds\":[1]}}",
            "{\"step\":\"COMPANIES\",\"version\":1,\"payload\":{\"companyIds\":[]}}",
            "{\"step\":\"COMPANIES\",\"version\":1,\"payload\":{\"companyIds\":[1,1]}}",
            "{\"step\":\"COMPANIES\",\"version\":1,\"payload\":{\"companyIds\":[1,2,3,4,5,6]}}",
            "{\"step\":\"COMPANIES\",\"version\":1,\"payload\":{\"companyIds\":[null]}}",
            "{\"step\":\"COMPANIES\",\"version\":1,\"payload\":{\"companyIds\":[1.1]}}",
            "{\"step\":\"COMPANIES\",\"version\":1,\"payload\":{\"companyIds\":[9007199254740992]}}",
            "{\"step\":\"COMPANIES\",\"version\":1,\"payload\":{\"companyIds\":[1],\"dates\":[]}}",
            "{\"step\":\"COMPANIES\",\"version\":1,\"payload\":{\"companyIds\":[1]},\"extra\":true}"
    })
    void rejectsMalformedRequestsWithoutPersistingDrafts(String json)
            throws Exception {
        mvc.perform(patch(PATH)
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        assertEquals(0, count("user_onboarding"));
    }

    @Test
    void acceptsFiveCompaniesAndRejectsPrivateOrUnknownCompanies() {
        var ids = List.of(
                firstCompany,
                secondCompany,
                company(true),
                company(true),
                company(true)
        );

        save("COMPANIES", Map.of("companyIds", ids));
        assertEquals(ids, snapshot().companyIds());

        assertBusiness(
                ErrorCode.RESOURCE_NOT_FOUND,
                () -> save(
                        "COMPANIES",
                        Map.of("companyIds", List.of(company(false)))
                )
        );

        assertBusiness(
                ErrorCode.RESOURCE_NOT_FOUND,
                () -> save(
                        "COMPANIES",
                        Map.of("companyIds", List.of(9007199254740991L))
                )
        );
    }

    @Test
    void rejectsSkippingMandatoryStepConfirmationsAndStaleVersions()
            throws Exception {
        assertBusiness(
                ErrorCode.ONBOARDING_INCOMPLETE,
                () -> save("JOBS", Map.of("assignments", List.of(
                        assignment(firstCompany, Map.of("jobId", jobId))
                )))
        );

        chooseCompanies();

        mvc.perform(patch(PATH)
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of(
                                "step", "COMPANIES",
                                "version", 1,
                                "payload", Map.of(
                                        "companyIds", List.of(firstCompany)
                                )
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"jobId\":1,\"customName\":\"기획\"}",
            "{\"customName\":\"   \"}",
            "{\"jobId\":0}",
            "{\"jobId\":\"1\"}",
            "{\"customName\":123}",
            "{\"customName\":\"기획\",\"extra\":true}"
    })
    void rejectsInvalidJobChoices(String job) throws Exception {
        chooseCompanies();

        String json = "{\"step\":\"JOBS\",\"version\":2,\"payload\":{\"assignments\":["
                + "{\"companyId\":" + firstCompany + ",\"job\":" + job + "},"
                + "{\"companyId\":" + secondCompany
                + ",\"job\":{\"customName\":\"기획\"}}]}}";

        mvc.perform(patch(PATH)
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content(json))
                .andExpect(status().isBadRequest());

        assertEquals(2, snapshot().version());
    }

    @Test
    void requiresExactlyTheSelectedCompaniesAndActiveJobs() {
        chooseCompanies();

        assertBusiness(
                ErrorCode.INVALID_INPUT,
                () -> save("JOBS", Map.of("assignments", List.of(
                        assignment(firstCompany, Map.of("jobId", jobId))
                )))
        );

        assertBusiness(
                ErrorCode.INVALID_INPUT,
                () -> save("JOBS", Map.of("assignments", List.of(
                        assignment(firstCompany, Map.of("jobId", jobId)),
                        assignment(firstCompany, Map.of("customName", "기획"))
                )))
        );

        jdbc.update("update jobs set is_active = false where id = ?", jobId);

        assertBusiness(ErrorCode.RESOURCE_NOT_FOUND, this::chooseJobs);
    }

    @Test
    void acceptsFiftyUnicodeCharactersAndRejectsFiftyOneForCustomJobs() {
        chooseCompanies();

        save("JOBS", Map.of("assignments", List.of(
                assignment(firstCompany, Map.of("customName", "😀".repeat(50))),
                assignment(secondCompany, Map.of("customName", "기획"))
        )));

        assertBusiness(
                ErrorCode.INVALID_INPUT,
                () -> save("JOBS", Map.of("assignments", List.of(
                        assignment(
                                firstCompany,
                                Map.of("customName", "😀".repeat(51))
                        ),
                        assignment(secondCompany, Map.of("customName", "기획"))
                )))
        );
    }

    @Test
    void validatesDatesInSeoulAndPreservesAnExistingDateAfterMidnight() {
        chooseCompanies();
        chooseJobs();

        save("DATES", Map.of("dates", List.of(
                date(firstCompany, "2026-10-24"),
                date(secondCompany, null)
        )));

        clock.set(Instant.parse("2026-10-24T15:00:01Z"));

        save("DATES", Map.of("dates", List.of(
                date(firstCompany, "2026-10-24"),
                date(secondCompany, null)
        )));

        assertBusiness(
                ErrorCode.INVALID_INPUT,
                () -> save("DATES", Map.of("dates", List.of(
                        date(firstCompany, "2026-10-24"),
                        date(secondCompany, "2026-10-24")
                )))
        );

        save("EXPERIENCE", Map.of("experienceIds", List.of()));
        save("NOTIFICATION_GUIDANCE", Map.of("guidanceCompleted", true));

        assertTrue(complete(key()).completed());

        assertEquals(LocalDate.parse("2026-10-24"), jdbc.queryForObject(
                "select interview_date from user_companies where user_id = ? and company_id = ?",
                LocalDate.class,
                userId,
                firstCompany
        ));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-02-30", "2026-1-02", "tomorrow", "2026-10-23"
    })
    void rejectsInvalidOrNewPastDates(String date) {
        chooseCompanies();
        chooseJobs();

        assertBusiness(
                ErrorCode.INVALID_INPUT,
                () -> save("DATES", Map.of("dates", List.of(
                        date(firstCompany, date),
                        date(secondCompany, null)
                )))
        );
    }

    @Test
    void requiresAnExplicitNullableDateForEverySelectedCompany() {
        chooseCompanies();
        chooseJobs();

        assertBusiness(
                ErrorCode.INVALID_INPUT,
                () -> save("DATES", Map.of("dates", List.of(
                        Map.of("companyId", firstCompany),
                        date(secondCompany, null)
                )))
        );
    }

    @Test
    void countsOnlyOwnedPublishedExperienceVersionsAndChecksOwnership() {
        long own = material(userId, "EXPERIENCE", true);
        material(userId, "COVER_LETTER", true);
        material(userId, "EXPERIENCE", false);

        long otherUser = member();
        long other = material(otherUser, "EXPERIENCE", true);

        chooseCompanies();
        chooseJobs();
        chooseDates();

        assertEquals(1, snapshot().experienceCount());

        assertBusiness(
                ErrorCode.RESOURCE_NOT_FOUND,
                () -> save(
                        "EXPERIENCE",
                        Map.of("experienceIds", List.of(other))
                )
        );

        save("EXPERIENCE", Map.of("experienceIds", List.of(own)));

        // 원문이 삭제돼도 이미 확인한 선택 단계를 재실행하거나 경험을 복원하지 않는다.
        jdbc.update(
                "update personal_materials set current_version_no = null where id = ?",
                own
        );
        jdbc.update("delete from material_versions where material_id = ?", own);
        jdbc.update("delete from personal_materials where id = ?", own);

        assertEquals(0, snapshot().experienceCount());

        save("NOTIFICATION_GUIDANCE", Map.of("guidanceCompleted", true));

        assertTrue(complete(key()).completed());
    }

    @Test
    void notificationGuidanceDoesNotEnableConsentAndDevicesAreSessionScoped() {
        ready();

        String otherSession = session(userId);
        device(otherSession, "GRANTED", true);

        assertNull(snapshot().notificationSettings().currentDevice());

        String current = device(sessionId, "DENIED", false);
        var state = snapshot();

        assertEquals(
                current,
                state.notificationSettings().currentDevice().installationId()
        );
        assertFalse(
                state.notificationSettings().currentDevice().registered()
        );
        assertFalse(state.notificationSettings().enabled());

        complete(key());

        jdbc.update("""
                update notification_settings
                set enabled = true, scheduled_time = '18:30:00',
                    time_zone = 'Asia/Seoul', version = 3
                where user_id = ?
                """, userId);

        assertEquals(
                "18:30",
                snapshot().notificationSettings().scheduledTime()
        );
        assertEquals(3, snapshot().notificationSettings().version());
    }

    @Test
    void replaysAcrossSessionsAndNeverResetsTheCurrentHomeCompany() {
        ready();

        String key = key();
        var first = complete(key);

        jdbc.update(
                "update user_preferences set selected_company_id = ? where user_id = ?",
                secondCompany,
                userId
        );

        jdbc.update(
                "update companies set is_service_company = false where id = ?",
                firstCompany
        );
        jdbc.update("update jobs set is_active = false where id = ?", jobId);

        String otherSession = session(userId);

        assertEquals(
                first,
                service.complete(userId, otherSession, 0, key.toUpperCase())
        );
        assertEquals(first, complete(key()));

        assertEquals(secondCompany, jdbc.queryForObject(
                "select selected_company_id from user_preferences where user_id = ?",
                Long.class,
                userId
        ));

        assertEquals(2, count("user_companies"));
        assertEquals(7, snapshot().version());

        assertBusiness(
                ErrorCode.VERSION_CONFLICT,
                () -> save(
                        "COMPANIES",
                        Map.of("companyIds", List.of(secondCompany))
                )
        );
    }

    @Test
    void rejectsExpiredKeysWithoutRepeatingCompletion() {
        ready();

        String key = key();
        complete(key);

        clock.set(clock.instant().plusSeconds(86400));
        new VerificationMaintenance(jdbc, clock).clean();

        String freshSession = session(userId);

        assertBusiness(
                ErrorCode.IDEMPOTENCY_KEY_EXPIRED,
                () -> service.complete(userId, freshSession, 0, key)
        );

        assertEquals(2, count("user_companies"));
        assertEquals(7, service.get(userId, freshSession, 0).version());
    }

    @Test
    void isolatesTheSameCompletionKeyBetweenUsers() {
        ready();

        String sharedKey = key();
        complete(sharedKey);

        long other = member();
        String otherSession = session(other);

        for (var input : List.of(
                Map.entry(
                        "COMPANIES",
                        Map.of("companyIds", List.of(secondCompany))
                ),
                Map.entry(
                        "JOBS",
                        Map.of("assignments", List.of(
                                assignment(
                                        secondCompany,
                                        Map.of("customName", "기획")
                                )
                        ))
                ),
                Map.entry(
                        "DATES",
                        Map.of("dates", List.of(date(secondCompany, null)))
                ),
                Map.entry(
                        "EXPERIENCE",
                        Map.of("experienceIds", List.of())
                ),
                Map.entry(
                        "NOTIFICATION_GUIDANCE",
                        Map.of("guidanceCompleted", true)
                )
        )) {
            long version = service.get(other, otherSession, 0).version();

            service.save(
                    other,
                    otherSession,
                    0,
                    request(input.getKey(), version, input.getValue())
            );
        }

        var result = service.complete(other, otherSession, 0, sharedKey);

        assertEquals(secondCompany, result.selectedCompanyId());
        assertEquals(firstCompany, complete(sharedKey).selectedCompanyId());
    }

    @Test
    void removesExpiredPayloadsButKeepsTheKeyTombstone() {
        ready();
        complete(key());

        clock.set(clock.instant().plusSeconds(86401));
        new VerificationMaintenance(jdbc, clock).clean();

        assertEquals(1, count("api_idempotency_requests"));

        assertNull(jdbc.queryForObject("""
                select response_payload from api_idempotency_requests
                where user_id = ? and operation = ?
                """,
                String.class,
                userId,
                OnboardingRequestStore.OPERATION
        ));
    }

    @Test
    void rechecksPublicCompaniesAndActiveJobsBeforeInitialCompletion() {
        ready();

        jdbc.update(
                "update companies set is_service_company = false where id = ?",
                firstCompany
        );

        assertBusiness(
                ErrorCode.RESOURCE_NOT_FOUND,
                () -> complete(key())
        );

        jdbc.update(
                "update companies set is_service_company = true where id = ?",
                firstCompany
        );
        jdbc.update("update jobs set is_active = false where id = ?", jobId);

        assertBusiness(
                ErrorCode.RESOURCE_NOT_FOUND,
                () -> complete(key())
        );

        assertEquals(0, count("user_companies"));
    }

    @Test
    void rejectsIncompleteCompletionInvalidKeysAndUnexpectedBodies()
            throws Exception {
        mvc.perform(post(PATH + "/complete")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", key()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ONBOARDING_INCOMPLETE"));

        mvc.perform(post(PATH + "/complete")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest());

        mvc.perform(post(PATH + "/complete")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "invalid"))
                .andExpect(status().isBadRequest());

        mvc.perform(post(PATH + "/complete")
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", key())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mvc.perform(get(PATH)
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rollsBackCompaniesPreferencesCompletionAndReplayWhenPersistenceFails() {
        ready();

        String key = key();

        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataAccessResourceFailureException(
                    "injected failure after replay insert"
            );
        }).when(requests).complete(any(), anyLong(), any(), any());

        assertBusiness(
                ErrorCode.SERVICE_UNAVAILABLE,
                () -> complete(key)
        );

        assertEquals(0, count("user_companies"));
        assertEquals(0, count("user_preferences"));
        assertEquals(0, count("notification_settings"));
        assertEquals(0, count("api_idempotency_requests"));
        assertFalse(snapshot().completed());
        assertEquals(6, snapshot().version());

        reset(requests);

        assertTrue(complete(key).completed());
    }

    @Test
    void serializesConcurrentEditsWithTheSameExpectedVersion()
            throws Exception {
        var first = request(
                "COMPANIES",
                1,
                Map.of("companyIds", List.of(firstCompany))
        );

        var second = request(
                "COMPANIES",
                1,
                Map.of("companyIds", List.of(secondCompany))
        );

        var result = concurrent(
                () -> saveResult(first),
                () -> saveResult(second)
        );

        assertEquals(1, result.stream().filter("OK"::equals).count());
        assertEquals(
                1,
                result.stream().filter("VERSION_CONFLICT"::equals).count()
        );
        assertEquals(2, snapshot().version());
    }

    @Test
    void serializesConcurrentCompletionForTheSameKey() throws Exception {
        ready();

        String key = key();

        var result = concurrent(
                () -> complete(key),
                () -> complete(key)
        );

        assertEquals(result.get(0), result.get(1));
        assertEquals(2, count("user_companies"));
        assertEquals(1, count("api_idempotency_requests"));
        assertEquals(7, snapshot().version());
    }

    @Test
    void serializesConcurrentCompletionEvenForDifferentKeys()
            throws Exception {
        ready();

        var result = concurrent(
                () -> complete(key()),
                () -> complete(key())
        );

        assertEquals(result.get(0), result.get(1));
        assertEquals(2, count("user_companies"));
        assertEquals(2, count("api_idempotency_requests"));
        assertEquals(7, snapshot().version());
    }

    @Test
    void rechecksSessionAndAccountInsideTheWriteTransaction() {
        jdbc.update(
                "update auth_sessions set revoked_at = utc_timestamp(6) where id = ?",
                sessionId
        );

        assertBusiness(ErrorCode.UNAUTHENTICATED, this::chooseCompanies);
        assertBusiness(ErrorCode.UNAUTHENTICATED, this::snapshot);

        jdbc.update(
                "update auth_sessions set revoked_at = null where id = ?",
                sessionId
        );
        jdbc.update(
                "update users set auth_version = 1 where id = ?",
                userId
        );

        assertBusiness(ErrorCode.UNAUTHENTICATED, this::chooseCompanies);
    }

    @Test
    void rollsBackIfTheSessionExpiresWhileCompletionIsRunning() {
        ready();

        doAnswer(invocation -> {
            invocation.callRealMethod();
            clock.set(clock.instant().plusSeconds(3600));
            return null;
        }).when(requests).complete(any(), anyLong(), any(), any());

        assertBusiness(
                ErrorCode.UNAUTHENTICATED,
                () -> complete(key())
        );

        assertEquals(0, count("user_companies"));
        assertEquals(0, count("api_idempotency_requests"));
    }

    @Test
    void keepsReadOnlyAndWriteTransactionBoundariesAndPropagatesDatabaseFailures()
            throws Exception {
        doAnswer(invocation -> {
            assertTrue(
                    TransactionSynchronizationManager.isActualTransactionActive()
            );
            assertTrue(
                    TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            );
            assertEquals(
                    4,
                    TransactionSynchronizationManager
                            .getCurrentTransactionIsolationLevel()
            );
            return invocation.callRealMethod();
        }).when(repository).find(anyLong());

        snapshot();
        reset(repository);

        doAnswer(invocation -> {
            assertTrue(
                    TransactionSynchronizationManager.isActualTransactionActive()
            );
            assertFalse(
                    TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            );
            return invocation.callRealMethod();
        }).when(repository).lock(anyLong(), any());

        chooseCompanies();
        reset(repository);

        doThrow(new DataAccessResourceFailureException("test"))
                .when(repository).experienceCount(anyLong());

        mvc.perform(get(PATH).header("Authorization", bearer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));

        reset(repository);

        doThrow(new CannotCreateTransactionException("test"))
                .when(transactionManager).getTransaction(any());

        assertBusiness(ErrorCode.SERVICE_UNAVAILABLE, this::snapshot);
    }

    @Test
    void doesNotHideCorruptStoredDraftsAsInitialState() throws Exception {
        chooseCompanies();

        jdbc.update(
                "update user_onboarding set draft_payload = '{}' where user_id = ?",
                userId
        );

        mvc.perform(get(PATH).header("Authorization", bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"));
    }

    private void ready() {
        chooseCompanies();
        chooseJobs();
        chooseDates();
        save("EXPERIENCE", Map.of("experienceIds", List.of()));
        save("NOTIFICATION_GUIDANCE", Map.of("guidanceCompleted", true));
    }

    private void chooseCompanies() {
        save(
                "COMPANIES",
                Map.of("companyIds", List.of(firstCompany, secondCompany))
        );
    }

    private void chooseJobs() {
        save("JOBS", Map.of("assignments", List.of(
                assignment(firstCompany, Map.of("jobId", jobId)),
                assignment(secondCompany, Map.of("customName", "마케팅"))
        )));
    }

    private void chooseDates() {
        save("DATES", Map.of("dates", List.of(
                date(firstCompany, null),
                date(secondCompany, null)
        )));
    }

    private OnboardingResponse snapshot() {
        return service.get(userId, sessionId, 0);
    }

    private OnboardingResponse save(
            String step, Map<String, ?> payload
    ) {
        return service.save(
                userId,
                sessionId,
                0,
                request(step, snapshot().version(), payload)
        );
    }

    private OnboardingPatchRequest request(
            String step, long version, Map<String, ?> payload
    ) {
        return OnboardingPatchRequest.parse(mapper.valueToTree(
                Map.of(
                        "step", step,
                        "version", version,
                        "payload", payload
                )
        ));
    }

    private JsonNode patchOk(
            String step, Map<String, ?> payload
    ) throws Exception {
        String json = mapper.writeValueAsString(Map.of(
                "step", step,
                "version", snapshot().version(),
                "payload", payload
        ));

        var result = mvc.perform(patch(PATH)
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content(json))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        return mapper.readTree(
                result.getContentAsString(StandardCharsets.UTF_8)
        ).get("data");
    }

    private OnboardingCompleteResponse complete(String key) {
        return service.complete(userId, sessionId, 0, key);
    }

    private String saveResult(OnboardingPatchRequest request) {
        try {
            service.save(userId, sessionId, 0, request);
            return "OK";
        } catch (BusinessException ex) {
            return ex.getErrorCode().name();
        }
    }

    private <T> List<T> concurrent(
            Callable<T> first, Callable<T> second
    ) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream()
                    .map(work -> executor.submit(() -> {
                        ready.countDown();

                        if (!start.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Test start timeout");
                        }

                        return work.call();
                    }))
                    .toList();

            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            return List.of(
                    futures.get(0).get(15, TimeUnit.SECONDS),
                    futures.get(1).get(15, TimeUnit.SECONDS)
            );
        }
    }

    private Map<String, Object> assignment(
            long company, Map<String, ?> job
    ) {
        return Map.of("companyId", company, "job", job);
    }

    private Map<String, Object> date(long company, String date) {
        var result = new LinkedHashMap<String, Object>();
        result.put("companyId", company);
        result.put("interviewDate", date);
        return result;
    }

    private long member() {
        long id = insert(
                "insert into users(nickname) values ('온보딩테스트')"
        );

        users.add(id);
        return id;
    }

    private long company(boolean visible) {
        long id = insert("""
                insert into companies(dart_corp_code, name, is_service_company)
                values (?, '온보딩기업', ?)
                """,
                Integer.toString(CORP_CODES.incrementAndGet()),
                visible
        );

        companies.add(id);
        return id;
    }

    private String session(long user) {
        String id = key();
        var issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);

        jdbc.update(
                "insert into auth_sessions(id, user_id, created_at, expires_at) values (?, ?, ?, ?)",
                id,
                user,
                utc(issued),
                utc(issued.plusSeconds(3600))
        );

        return id;
    }

    private String token(long user, String session) {
        return jdbc.queryForObject(
                "select created_at, expires_at from auth_sessions where id = ?",
                (rs, row) -> tokens.issue(
                        user,
                        session,
                        0,
                        rs.getObject(
                                "created_at", LocalDateTime.class
                        ).toInstant(ZoneOffset.UTC),
                        rs.getObject(
                                "expires_at", LocalDateTime.class
                        ).toInstant(ZoneOffset.UTC)
                ),
                session
        );
    }

    private String device(
            String session, String permission, boolean registered
    ) {
        String id = key();

        jdbc.update("""
                insert into user_devices(
                    installation_id, user_id, session_id,
                    platform, permission, push_token_ciphertext, push_token_digest
                )
                values (?, ?, ?, 'IOS', ?, ?, ?)
                """,
                id,
                userId,
                session,
                permission,
                registered ? "test-only-ciphertext" : null,
                registered
                        ? UUID.randomUUID().toString().replace("-", "").repeat(2)
                        : null
        );

        return id;
    }

    private long material(
            long user, String type, boolean published
    ) {
        long id = insert(
                "insert into personal_materials(user_id, type) values (?, ?)",
                user,
                type
        );

        if (published) {
            insert("""
                    insert into material_versions(
                        material_id, user_id, material_type,
                        version_no, title, content_text
                    )
                    values (?, ?, ?, 1, ?, '테스트 내용')
                    """,
                    id,
                    user,
                    type,
                    type.equals("EXPERIENCE") ? "경험" : null
            );

            jdbc.update(
                    "update personal_materials set current_version_no = 1 where id = ?",
                    id
            );
        }

        return id;
    }

    private long count(String table) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "select count(*) from " + table + " where user_id = ?",
                Long.class,
                userId
        ));
    }

    private long insert(String sql, Object... arguments) {
        var keys = new GeneratedKeyHolder();

        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    sql, Statement.RETURN_GENERATED_KEYS
            );

            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }

            return statement;
        }, keys);

        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    private void assertBusiness(
            ErrorCode code,
            org.junit.jupiter.api.function.Executable action
    ) {
        assertEquals(
                code,
                assertThrows(BusinessException.class, action).getErrorCode()
        );
    }

    private String bearer() {
        return "Bearer " + token;
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        @Primary
        MutableClock onboardingClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {

        private final AtomicReference<Instant> now =
                new AtomicReference<>(
                        Instant.parse("2026-10-24T14:30:00Z")
                );

        void set(Instant value) {
            now.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}