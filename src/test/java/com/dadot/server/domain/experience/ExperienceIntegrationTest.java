package com.dadot.server.domain.experience;

import com.dadot.server.domain.auth.service.VerificationMaintenance;
import com.dadot.server.domain.experience.repository.ExperienceRepository;
import com.dadot.server.domain.experience.repository.ExperienceRequestStore;
import com.dadot.server.domain.onboarding.repository.OnboardingRepository;
import com.dadot.server.global.security.AccessTokenIssuer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.dart.worker-enabled=false", "app.dart.originals.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class ExperienceIntegrationTest {

    private static final String PATH = "/v1/users/me/experiences";
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired AccessTokenIssuer tokens;
    @Autowired PlatformTransactionManager transactions;
    @Autowired OnboardingRepository onboarding;
    @MockitoSpyBean Clock clock;
    @MockitoSpyBean ExperienceRepository repository;
    @MockitoSpyBean ExperienceRequestStore requests;
    private final List<Long> users = new ArrayList<>();
    private Instant now;
    private long user;
    private String token, session;
    private Long studyCompany;
    private boolean safe;

    @BeforeEach
    void setup() {
        safe = false;
        assertEquals("dadot_test_db", jdbc.queryForObject("select database()", String.class));
        safe = true;
        reset(clock, repository, requests);
        users.clear();
        studyCompany = null;
        now = Instant.parse("2026-10-27T14:59:00.123456789Z");
        doReturn(now).when(clock).instant();
        user = account();
        login(user);
    }

    @AfterEach
    void cleanup() {
        if (!safe) return;
        reset(clock, repository, requests);
        for (long id : users) {
            jdbc.update("delete from api_idempotency_requests where user_id = ?", id);
            jdbc.update("delete from quiz_answers where user_id = ?", id);
            jdbc.update("delete from quiz_set_items where user_id = ?", id);
            jdbc.update("delete from quiz_sets where user_id = ?", id);
            jdbc.update("delete from user_companies where user_id = ?", id);
            jdbc.update("update personal_materials set current_version_no = null where user_id = ?", id);
            jdbc.update("delete from material_versions where user_id = ?", id);
            jdbc.update("delete from personal_materials where user_id = ?", id);
            jdbc.update("delete from auth_sessions where user_id = ?", id);
        }
        if (studyCompany != null) {
            jdbc.update("delete o from quiz_options o join quiz_question_versions v on v.id = o.quiz_version_id where v.company_id = ?", studyCompany);
            jdbc.update("delete from quiz_question_versions where company_id = ?", studyCompany);
            jdbc.update("delete from quiz_questions where company_id = ?", studyCompany);
            jdbc.update("delete from companies where id = ?", studyCompany);
        }
        for (long id : users) jdbc.update("delete from users where id = ?", id);
    }

    @Test
    void createDetailListAndOnboardingCountWorkBeforeOnboardingCompletion() throws Exception {
        String original = "  원문 첫 줄\n둘째 줄  ";
        JsonNode item = data(create(key(), body("경험 제목", original)).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store")));
        long id = item.get("experienceId").longValue();
        assertEquals(original, item.get("content").stringValue());
        assertEquals("2026-10-27T14:59:00.123456Z", item.get("createdAt").stringValue());
        assertEquals(item, data(detail(id).andExpect(status().isOk())));
        list("").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.content[0].experienceId").value(id))
                .andExpect(jsonPath("$.data.content[0].preview").value(original))
                .andExpect(jsonPath("$.data.content[0].content").doesNotExist())
                .andExpect(jsonPath("$.data.totalElements").value(1));
        assertEquals(1, personalization());
        assertEquals(1, onboarding.experienceCount(user));
    }

    @Test
    void listIsOwnerAndTypeScopedOrderedAndUsesCodePointPreview() throws Exception {
        long first = seed(user, "EXPERIENCE", "첫째", "가");
        long second = seed(user, "EXPERIENCE", "둘째", "😀".repeat(101));
        long third = seed(user, "EXPERIENCE", "최근", "나");
        jdbc.update("update personal_materials set updated_at = ? where id = ?", utc(now.plusSeconds(1)), third);
        long cover = seed(user, "COVER_LETTER", null, "자소서");
        long other = seed(account(), "EXPERIENCE", "타인", "비공개");
        list("?size=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].experienceId").value(third))
                .andExpect(jsonPath("$.data.content[1].experienceId").value(second))
                .andExpect(jsonPath("$.data.content[1].preview").value("😀".repeat(100)))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        list("?size=2&page=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].experienceId").value(first))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        list("?page=2147483647&size=100").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(3));
        for (long id : List.of(cover, other, 9007199254740991L)) {
            detail(id).andExpect(status().isNotFound());
            update(id, "{\"version\":1,\"title\":\"침범\"}").andExpect(status().isNotFound());
            remove(id).andExpect(status().isNotFound());
        }
        assertEquals(0, personalization());
    }

    @Test
    void emptyListHasStablePagination() throws Exception {
        list("").andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0)).andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void patchCreatesImmutableVersionsPreservesOmittedFieldsAndRejectsStaleVersion() throws Exception {
        JsonNode initial = data(create(key(), body("처음", "원문")).andExpect(status().isCreated()));
        long id = initial.get("experienceId").longValue();
        now = now.plusSeconds(1);
        doReturn(now).when(clock).instant();
        update(id, "{\"version\":1,\"title\":\"수정\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2)).andExpect(jsonPath("$.data.content").value("원문"))
                .andExpect(jsonPath("$.data.createdAt").value(initial.get("createdAt").stringValue()));
        update(id, "{\"version\":2,\"content\":\"새 본문\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(3)).andExpect(jsonPath("$.data.title").value("수정"));
        update(id, "{\"version\":1,\"title\":\"덮어쓰기\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
        assertEquals("원문", jdbc.queryForObject("select content_text from material_versions where material_id = ? and version_no = 1", String.class, id));
        assertEquals(3, scalar("select count(*) from material_versions where material_id = ?", id));
        assertEquals(3, personalization());
    }

    @Test
    void sameValuePatchStillAdvancesBothVersionsExactlyOnce() throws Exception {
        long id = seed(user, "EXPERIENCE", "제목", "본문");
        update(id, "{\"version\":1,\"title\":\"제목\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        assertEquals(1, personalization());
    }

    @Test
    void replayReturnsOriginalVersionAcrossSessionsBodyOrderAndCaseWithoutExtraWrites() throws Exception {
        String key = key();
        JsonNode initial = data(create(key, body("제목", "본문")).andExpect(status().isCreated()));
        long id = initial.get("experienceId").longValue();
        update(id, "{\"version\":1,\"content\":\"수정된 본문\"}").andExpect(status().isOk());
        login(user);
        JsonNode replay = data(create(key.toUpperCase(Locale.ROOT), "{\"content\":\"본문\",\"title\":\"제목\"}")
                .andExpect(status().isCreated()));
        assertEquals(initial, replay);
        assertEquals(2, personalization());
        assertEquals(1, repository.count(user));
        create(key, body("제목", "다른 내용")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));
        String payload = jdbc.queryForObject("select response_payload from api_idempotency_requests where user_id = ? and operation = ?",
                String.class, user, ExperienceRequestStore.OPERATION);
        JsonNode reference = mapper.readTree(payload);
        assertEquals(2, reference.size());
        assertEquals(id, reference.get("experienceId").longValue());
        assertFalse(payload.contains("본문"));
        assertFalse(payload.contains("제목"));
    }

    @Test
    void sameKeyIsIndependentForDifferentUsers() throws Exception {
        String key = key();
        long original = data(create(key, body("제목", "본문")).andExpect(status().isCreated())).get("experienceId").longValue();
        long foreign = account();
        login(foreign);
        long next = data(create(key, body("제목", "본문")).andExpect(status().isCreated())).get("experienceId").longValue();
        assertNotEquals(original, next);
    }

    @Test
    void expiredKeysStayExpiredAfterMaintenanceClearsResponse() throws Exception {
        String key = key();
        create(key, body("제목", "본문")).andExpect(status().isCreated());
        now = now.plusSeconds(86400);
        doReturn(now).when(clock).instant();
        login(user);
        create(key, body("제목", "본문")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_EXPIRED"));
        now = now.plusSeconds(1);
        doReturn(now).when(clock).instant();
        new VerificationMaintenance(jdbc, clock).clean();
        assertEquals(1, scalar("select count(*) from api_idempotency_requests where user_id = ? and response_payload is null", user));
        create(key, body("제목", "본문")).andExpect(status().isConflict());
        assertEquals(1, personalization());
        assertEquals(1, repository.count(user));
    }

    @Test
    void deletingRemovesEveryVersionAndDoesNotResurrectThroughReplay() throws Exception {
        String key = key();
        long id = data(create(key, body("제목", "비공개본문")).andExpect(status().isCreated())).get("experienceId").longValue();
        update(id, "{\"version\":1,\"title\":\"둘째\"}").andExpect(status().isOk());
        long cover = seed(user, "COVER_LETTER", null, "남길 자소서");
        remove(id).andExpect(status().isNoContent()).andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"));
        assertEquals(0, scalar("select count(*) from material_versions where material_id = ?", id));
        assertEquals(0, scalar("select count(*) from personal_materials where id = ?", id));
        assertEquals(1, scalar("select count(*) from personal_materials where id = ?", cover));
        assertEquals(0, onboarding.experienceCount(user));
        assertEquals(3, personalization());
        detail(id).andExpect(status().isNotFound());
        remove(id).andExpect(status().isNotFound());
        create(key, body("제목", "비공개본문")).andExpect(status().isNotFound());
        create(key, body("제목", "다른 본문")).andExpect(status().isConflict());
        assertEquals(3, personalization());
    }

    @Test
    void limitCountsOnlyExperiencesReplayStillWorksAndDeleteReleasesOnePlace() throws Exception {
        String key = key();
        long id = data(create(key, body("제목", "본문")).andExpect(status().isCreated())).get("experienceId").longValue();
        for (int i = 1; i < 100; i++) seed(user, "EXPERIENCE", "자료" + i, "본문");
        seed(user, "COVER_LETTER", null, "제한과 무관");
        create(key(), body("초과", "본문")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EXPERIENCE_LIMIT_EXCEEDED"));
        create(key, body("제목", "본문")).andExpect(status().isCreated());
        assertEquals(1, personalization());
        remove(id).andExpect(status().isNoContent());
        create(key(), body("다시", "본문")).andExpect(status().isCreated());
        assertEquals(100, repository.count(user));
    }

    @Test
    void unicodeLimitsUseCodePointsAndOriginalWhitespaceIsPreserved() throws Exception {
        create(key(), body("😀".repeat(50), "😀".repeat(5000))).andExpect(status().isCreated());
        create(key(), body("😀".repeat(51), "본문")).andExpect(status().isBadRequest());
        create(key(), body("제목", "😀".repeat(5001))).andExpect(status().isBadRequest());
        create(key(), body("\u00a0", "본문")).andExpect(status().isBadRequest());
        create(key(), body("제목", "\u2003\n\t")).andExpect(status().isBadRequest());
        create(key(), "{\"title\":\"제목\",\"content\":\"\\uD800\"}").andExpect(status().isBadRequest());
        assertEquals(1, repository.count(user));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"title\":\"제목\"}",
            "{\"title\":null,\"content\":\"본문\"}", "{\"title\":1,\"content\":\"본문\"}",
            "{\"title\":\"\",\"content\":\"본문\"}", "{\"title\":\"제목\",\"content\":null}",
            "{\"title\":\"제목\",\"content\":\"  \"}",
            "{\"title\":\"제목\",\"content\":\"본문\",\"userId\":1}"})
    void rejectsInvalidCreateBodies(String body) throws Exception {
        create(key(), body).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"version\":1}",
            "{\"version\":0,\"title\":\"제목\"}", "{\"version\":1.5,\"title\":\"제목\"}",
            "{\"version\":\"1\",\"title\":\"제목\"}",
            "{\"version\":9223372036854775808,\"title\":\"제목\"}",
            "{\"version\":1,\"title\":null}", "{\"version\":1,\"content\":null}",
            "{\"version\":1,\"content\":\"\"}", "{\"version\":1,\"title\":\"제목\",\"extra\":1}"})
    void rejectsInvalidPatchBodies(String body) throws Exception {
        long id = seed(user, "EXPERIENCE", "제목", "본문");
        update(id, body).andExpect(status().isBadRequest());
        assertEquals(0, personalization());
    }

    @ParameterizedTest
    @ValueSource(strings = {"?page=", "?size=", "?page=-1", "?page=1.5", "?size=0", "?size=101",
            "?page=2147483648", "?size=1&size=2", "?sort=title"})
    void rejectsInvalidAndUnknownPaging(String query) throws Exception {
        list(query).andExpect(status().isBadRequest());
    }

    @Test
    void enforcesBodiesQueriesKeysAndIdRange() throws Exception {
        long id = seed(user, "EXPERIENCE", "제목", "본문");
        for (var request : List.of(get(PATH), get(PATH + "/" + id), delete(PATH + "/" + id))) {
            mvc.perform(request.header("Authorization", bearer()).contentType("application/json").content("{}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(PATH + "?x=1").header("Authorization", bearer()).header("Idempotency-Key", key())
                .contentType("application/json").content(body("제목", "본문"))).andExpect(status().isBadRequest());
        mvc.perform(patch(PATH + "/" + id + "?x=1").header("Authorization", bearer())
                        .contentType("application/json").content("{\"version\":1,\"title\":\"변경\"}"))
                .andExpect(status().isBadRequest());
        create("not-a-uuid", body("제목", "본문")).andExpect(status().isBadRequest());
        mvc.perform(post(PATH).header("Authorization", bearer()).contentType("application/json").content(body("제목", "본문")))
                .andExpect(status().isBadRequest());
        for (long invalid : List.of(0L, -1L, 9007199254740992L)) {
            detail(invalid).andExpect(status().isBadRequest());
            remove(invalid).andExpect(status().isBadRequest());
        }
    }

    @Test
    void authenticationAppliesToEveryEndpointAndReplayedRequests() throws Exception {
        long id = seed(user, "EXPERIENCE", "제목", "본문");
        for (var request : List.of(get(PATH), get(PATH + "/" + id), delete(PATH + "/" + id),
                patch(PATH + "/" + id).contentType("application/json").content("{}"),
                post(PATH).contentType("application/json").content("{}"))) {
            mvc.perform(request).andExpect(status().isUnauthorized());
        }
        String key = key();
        create(key, body("제목", "본문")).andExpect(status().isCreated());
        jdbc.update("update auth_sessions set revoked_at = ? where id = ?", utc(now), session);
        list("").andExpect(status().isUnauthorized());
        create(key, body("제목", "본문")).andExpect(status().isUnauthorized());
        login(user);
        jdbc.update("update users set auth_version = auth_version + 1 where id = ?", user);
        detail(id).andExpect(status().isUnauthorized());
        jdbc.update("update users set auth_version = 0,status = 'DELETING' where id = ?", user);
        remove(id).andExpect(status().isUnauthorized());
    }

    @Test
    void sameKeyConcurrentCreateSavesOnlyOnce() throws Exception {
        String key = key();
        assertEquals(List.of(201, 201), concurrent(
                () -> code(create(key, body("제목", "본문"))),
                () -> code(create(key, body("제목", "본문")))));
        assertEquals(1, repository.count(user));
        assertEquals(1, personalization());
    }

    @Test
    void sameKeyConcurrentDifferentBodiesConflictWithoutSecondMaterial() throws Exception {
        String key = key();
        var codes = concurrent(() -> code(create(key, body("하나", "본문"))),
                () -> code(create(key, body("둘", "본문"))));
        assertEquals(List.of(201, 409), codes.stream().sorted().toList());
        assertEquals(1, repository.count(user));
        assertEquals(1, personalization());
    }

    @Test
    void concurrentRegistrationCannotExceedOneHundred() throws Exception {
        for (int i = 0; i < 99; i++) seed(user, "EXPERIENCE", "자료" + i, "본문");
        var codes = concurrent(() -> code(create(key(), body("하나", "본문"))),
                () -> code(create(key(), body("둘", "본문"))));
        assertEquals(List.of(201, 409), codes.stream().sorted().toList());
        assertEquals(100, repository.count(user));
        assertEquals(1, personalization());
    }

    @Test
    void concurrentUpdatesWithSameExpectedVersionOnlyCommitOneNewVersion() throws Exception {
        long id = seed(user, "EXPERIENCE", "제목", "본문");
        var codes = concurrent(() -> code(update(id, "{\"version\":1,\"title\":\"하나\"}")),
                () -> code(update(id, "{\"version\":1,\"title\":\"둘\"}")));
        assertEquals(List.of(200, 409), codes.stream().sorted().toList());
        assertEquals(2, scalar("select count(*) from material_versions where material_id = ?", id));
        assertEquals(1, personalization());
    }

    @Test
    void concurrentUpdateAndDeleteNeverRecreateDeletedMaterial() throws Exception {
        long id = seed(user, "EXPERIENCE", "제목", "본문");
        var codes = concurrent(() -> code(update(id, "{\"version\":1,\"title\":\"변경\"}")),
                () -> code(remove(id)));
        assertTrue(codes.get(0) == 200 || codes.get(0) == 404);
        assertEquals(204, codes.get(1));
        assertEquals(0, repository.count(user));
        assertEquals(0, scalar("select count(*) from material_versions where material_id = ?", id));
    }

    @Test
    void createRollsBackIfIdempotencySaveFails() throws Exception {
        doThrow(new TransientDataAccessResourceException("private source must not be logged"))
                .when(requests).complete(eq(user), any(), anyLong(), any());
        create(key(), body("제목", "본문")).andExpect(status().isServiceUnavailable());
        assertEquals(0, repository.count(user));
        assertEquals(0, personalization());
        assertEquals(0, scalar("select count(*) from material_versions where user_id = ?", user));
        assertEquals(0, scalar("select count(*) from api_idempotency_requests where user_id = ?", user));
    }

    @Test
    void patchAndDeleteRollbackAllChangesIfPersonalizationUpdateFails() throws Exception {
        long id = seed(user, "EXPERIENCE", "제목", "본문");
        doThrow(new TransientDataAccessResourceException("storage"))
                .when(repository).advancePersonalization(eq(user), any());
        update(id, "{\"version\":1,\"title\":\"변경\"}").andExpect(status().isServiceUnavailable());
        remove(id).andExpect(status().isServiceUnavailable());
        assertEquals("제목", data(detail(id)).get("title").stringValue());
        assertEquals(1, scalar("select count(*) from material_versions where material_id = ?", id));
        assertEquals(0, personalization());
    }

    @Test
    void storageLogsContainOnlySanitizedMetadata(CapturedOutput output) throws Exception {
        doThrow(new TransientDataAccessResourceException("SECRET_EXPERIENCE_42",
                new SQLException("SECRET_EXPERIENCE_42", "SECRET_STATE", 1234)))
                .when(repository).count(user);
        list("").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
        assertTrue(output.getOut().contains("operation=list"));
        assertTrue(output.getOut().contains("sqlState=null, dbErrorCode=1234"));
        assertFalse(output.getAll().contains("SECRET_EXPERIENCE_42"));
        assertFalse(output.getAll().contains("SECRET_STATE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE", "DELETE", "REPLAY"})
    void expiryBeforeCommitAlsoProtectsUpdateDeleteAndReplay(String operation) throws Exception {
        String key = key();
        long id = data(create(key, body("제목", "본문")).andExpect(status().isCreated())).get("experienceId").longValue();
        if (operation.equals("REPLAY")) {
            doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                doReturn(now.plusSeconds(3600)).when(clock).instant();
                return result;
            }).when(repository).snapshot(eq(user), eq(id), eq(1L));
            create(key, body("제목", "본문")).andExpect(status().isUnauthorized());
        } else {
            doAnswer(invocation -> {
                invocation.callRealMethod();
                doReturn(now.plusSeconds(3600)).when(clock).instant();
                return null;
            }).when(repository).advancePersonalization(eq(user), any());
            if (operation.equals("UPDATE")) {
                update(id, "{\"version\":1,\"title\":\"변경\"}").andExpect(status().isUnauthorized());
            } else {
                remove(id).andExpect(status().isUnauthorized());
            }
        }
        assertEquals(1, repository.count(user));
        assertEquals(1, personalization());
        assertEquals(1, scalar("select count(*) from material_versions where material_id = ?", id));
    }

    @Test
    void deletingExperiencePreservesSavedQuizAnswersAndExplanations() throws Exception {
        long id = seed(user, "EXPERIENCE", "원문경험", "삭제할 원문");
        studyCompany = insert("insert into companies(dart_corp_code,name,is_service_company) values ('98420001','보존검증',true)");
        jdbc.update("insert into user_companies(user_id,company_id,active_slot,custom_job_name) values (?,?,1,'개발')", user, studyCompany);
        long question = insert("insert into quiz_questions(company_id) values (?)", studyCompany);
        long version = insert("insert into quiz_question_versions(question_id,company_id,version_no,question_text,explanation) values (?,?,1,'보존 질문','보존 해설')", question, studyCompany);
        long option = insert("insert into quiz_options(quiz_version_id,position,option_text,is_correct) values (?,1,'보기',true)", version);
        long set = insert("insert into quiz_sets(user_id,company_id,business_date,mode) values (?,?,'2026-10-27','NORMAL')", user, studyCompany);
        long item = insert("insert into quiz_set_items(set_id,user_id,company_id,question_id,quiz_version_id,position) values (?,?,?,?,?,1)", set, user, studyCompany, question, version);
        long answer = insert("insert into quiz_answers(set_item_id,user_id,company_id,question_id,quiz_version_id,selected_option_id,is_correct,request_key_hash,request_body_hmac) values (?,?,?,?,?,?,true,?,?)",
                item, user, studyCompany, question, version, option, "a".repeat(64), "b".repeat(64));
        remove(id).andExpect(status().isNoContent());
        assertEquals(1, scalar("select count(*) from quiz_answers where id = ?", answer));
        assertEquals("보존 해설", jdbc.queryForObject("select explanation from quiz_question_versions where id = ?", String.class, version));
    }

    @Test
    void expiringSessionBeforeCommitRollsBackCreatedMaterialAndReplayKey() throws Exception {
        doAnswer(invocation -> {
            invocation.callRealMethod();
            doReturn(now.plusSeconds(3600)).when(clock).instant();
            return null;
        }).when(requests).complete(eq(user), any(), anyLong(), any());
        create(key(), body("제목", "본문")).andExpect(status().isUnauthorized());
        assertEquals(0, repository.count(user));
        assertEquals(0, personalization());
        assertEquals(0, scalar("select count(*) from api_idempotency_requests where user_id = ?", user));
    }

    @Test
    void countersCannotOverflowOrLeavePartialWrites() throws Exception {
        long id = seed(user, "EXPERIENCE", "제목", "본문");
        jdbc.update("update personal_materials set current_version_no = null where id = ?", id);
        jdbc.update("update material_versions set version_no = ? where material_id = ?", Integer.MAX_VALUE, id);
        jdbc.update("update personal_materials set current_version_no = ? where id = ?", Integer.MAX_VALUE, id);
        update(id, "{\"version\":" + Integer.MAX_VALUE + ",\"title\":\"변경\"}")
                .andExpect(status().isServiceUnavailable());
        jdbc.update("update users set personalization_version = ? where id = ?", Long.MAX_VALUE, user);
        create(key(), body("제목", "본문")).andExpect(status().isServiceUnavailable());
        remove(id).andExpect(status().isServiceUnavailable());
        assertEquals(1, repository.count(user));
        assertEquals(Long.MAX_VALUE, personalization());
    }

    @Test
    void countAndPageShareOneSnapshotDuringConcurrentModification() throws Exception {
        long id = seed(user, "EXPERIENCE", "이전제목", "본문");
        AtomicBoolean once = new AtomicBoolean();
        doAnswer(invocation -> {
            if (once.compareAndSet(false, true)) {
                try (var pool = Executors.newSingleThreadExecutor()) {
                    pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                        jdbc.update("insert into material_versions(material_id,user_id,material_type,version_no,title,content_text) values (?,?,'EXPERIENCE',2,'동시변경','본문')", id, user);
                        jdbc.update("update personal_materials set current_version_no = 2 where id = ?", id);
                        seed(user, "EXPERIENCE", "새경험", "본문");
                    })).get(5, TimeUnit.SECONDS);
                }
            }
            return invocation.callRealMethod();
        }).when(repository).list(eq(user), any());
        list("").andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("이전제목"));
        assertEquals(2, repository.count(user));
    }

    private long seed(long owner, String type, String title, String content) {
        long id = insert("insert into personal_materials(user_id,type,created_at,updated_at) values (?,?,?,?)", owner, type, utc(now), utc(now));
        jdbc.update("insert into material_versions(material_id,user_id,material_type,version_no,title,content_text,created_at) values (?,?,?,1,?,?,?)",
                id, owner, type, title, content, utc(now));
        jdbc.update("update personal_materials set current_version_no = 1 where id = ?", id);
        return id;
    }

    private long account() {
        long id = insert("insert into users(nickname) values ('경험사용자')");
        users.add(id);
        return id;
    }

    private void login(long owner) {
        session = key();
        Instant issued = now.minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        jdbc.update("insert into auth_sessions(id,user_id,created_at,expires_at) values (?,?,?,?)",
                session, owner, utc(issued), utc(issued.plusSeconds(3600)));
        token = tokens.issue(owner, session, 0, issued, issued.plusSeconds(3600));
    }

    private long personalization() {
        return scalar("select personalization_version from users where id = ?", user);
    }

    private List<Integer> concurrent(Callable<Integer> a, Callable<Integer> b) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return a.call(); });
            var second = pool.submit(() -> { assertTrue(start.await(5, TimeUnit.SECONDS)); return b.call(); });
            start.countDown();
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        }
    }

    private String body(String title, String content) {
        return mapper.writeValueAsString(Map.of("title", title, "content", content));
    }
    private ResultActions list(String query) throws Exception { return mvc.perform(get(PATH + query).header("Authorization", bearer())); }
    private ResultActions detail(long id) throws Exception { return mvc.perform(get(PATH + "/" + id).header("Authorization", bearer())); }
    private ResultActions create(String key, String body) throws Exception {
        return mvc.perform(post(PATH).header("Authorization", bearer()).header("Idempotency-Key", key).contentType("application/json").content(body));
    }
    private ResultActions update(long id, String body) throws Exception {
        return mvc.perform(patch(PATH + "/" + id).header("Authorization", bearer()).contentType("application/json").content(body));
    }
    private ResultActions remove(long id) throws Exception { return mvc.perform(delete(PATH + "/" + id).header("Authorization", bearer())); }
    private int code(ResultActions result) { return result.andReturn().getResponse().getStatus(); }
    private JsonNode data(ResultActions result) throws Exception { return mapper.readTree(result.andReturn().getResponse().getContentAsString()).get("data"); }
    private long scalar(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class, args)); }
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
    private static LocalDateTime utc(Instant time) { return LocalDateTime.ofInstant(time, ZoneOffset.UTC); }
}