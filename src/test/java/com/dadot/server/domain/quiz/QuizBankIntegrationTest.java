package com.dadot.server.domain.quiz;

import com.dadot.server.domain.disclosure.repository.DisclosureRepository;
import com.dadot.server.domain.quiz.repository.QuizBankRepository;
import com.dadot.server.domain.quiz.repository.QuizDisclosureImpactRepository;
import com.dadot.server.domain.quiz.repository.QuizRequestStore;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Statement;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
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
class QuizBankIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired Clock clock;
    @Autowired AccessTokenIssuer tokens;

    @MockitoSpyBean QuizBankRepository repository;
    @MockitoSpyBean QuizRequestStore requests;
    @MockitoSpyBean QuizDisclosureImpactRepository impacts;

    private final List<Long> users = new ArrayList<>();
    private final List<Long> companies = new ArrayList<>();

    private long user;
    private long company;
    private long disclosure;
    private long source;
    private long section;
    private String session;
    private String token;
    private boolean safe;

    private static final String ORIGINAL =
            "매출은 100원이며 연구개발에 투자합니다. ABC";
    private static final String QUOTE =
            "연구개발에 투자합니다.";

    @BeforeEach
    void setup() {
        safe = false;

        assertEquals(
                "dadot_test_db",
                jdbc.queryForObject("select database()", String.class)
        );

        safe = true;

        reset(repository, requests, impacts);
        users.clear();
        companies.clear();

        user = account("ADMIN");
        company = company();

        var login = login(user);
        session = login[0];
        token = login[1];

        disclosure = filing(company);
        source = original(disclosure, "PUBLISHED", 1);

        section = scalar(
                "select id from disclosure_sections where disclosure_version_id = ?",
                source
        );
    }

    @AfterEach
    void cleanup() {
        if (!safe) {
            return;
        }

        reset(repository, requests, impacts);

        for (long id : users) {
            jdbc.update(
                    "delete from admin_audit_events where actor_user_id = ?",
                    id
            );
            jdbc.update(
                    "delete from api_idempotency_requests where user_id = ?",
                    id
            );
        }

        for (long id : companies) {
            jdbc.update(
                    "update quiz_questions set published_version_no = null where company_id = ?",
                    id
            );
            jdbc.update(
                    "delete e from quiz_evidence e join quiz_question_versions v on v.id = e.quiz_version_id where v.company_id = ?",
                    id
            );
            jdbc.update(
                    "delete o from quiz_options o join quiz_question_versions v on v.id = o.quiz_version_id where v.company_id = ?",
                    id
            );
            jdbc.update(
                    "delete from quiz_question_versions where company_id = ?",
                    id
            );
            jdbc.update(
                    "delete from quiz_questions where company_id = ?",
                    id
            );
            jdbc.update(
                    "delete s from disclosure_sections s join disclosure_versions v on v.id = s.disclosure_version_id where v.company_id = ?",
                    id
            );
            jdbc.update(
                    "delete from disclosure_versions where company_id = ?",
                    id
            );
            jdbc.update(
                    "update disclosures set corrects_disclosure_id = null where company_id = ?",
                    id
            );
            jdbc.update(
                    "delete from disclosures where company_id = ?",
                    id
            );
            jdbc.update(
                    "delete from companies where id = ?",
                    id
            );
        }

        for (long id : users) {
            jdbc.update(
                    "delete from auth_sessions where user_id = ?",
                    id
            );
            jdbc.update(
                    "delete from users where id = ?",
                    id
            );
        }
    }

    @Test
    void createsCompleteDraftWithFourOptionsOneAnswerAndNullableHeading()
            throws Exception {
        jdbc.update(
                "update disclosure_sections set heading = null where id = ?",
                section
        );

        var result = create(content(), key())
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.options.length()").value(4))
                .andExpect(jsonPath("$.data.evidence[0].sectionId").value(section))
                .andExpect(
                        jsonPath("$.data.evidence[0].sectionTitle")
                                .value(org.hamcrest.Matchers.nullValue())
                );

        var data = data(result);

        assertEquals(
                data.get("options").get(1).get("optionId").longValue(),
                data.get("correctOptionId").longValue()
        );

        assertEquals(
                1,
                scalar(
                        "select count(*) from quiz_options where quiz_version_id = ? and is_correct = true",
                        vid(data)
                )
        );

        assertEquals(
                1,
                scalar(
                        "select count(*) from quiz_evidence where quiz_version_id = ?",
                        vid(data)
                )
        );

        assertEquals(1, audits());
    }

    @Test
    void sameRequestReplaysOriginalSnapshotAfterLaterChanges()
            throws Exception {
        String key = key();
        var body = content();

        var first = data(
                create(body, key).andExpect(status().isCreated())
        );

        advance(first, "IN_REVIEW");

        var replay = data(
                create(body, key).andExpect(status().isCreated())
        );

        assertEquals(first, replay);
        assertEquals(1, questions());

        body.put("question", "다른 문제");

        create(body, key)
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.error.code")
                                .value("IDEMPOTENCY_CONFLICT")
                );
    }

    @Test
    void expiredRequestNeverCreatesAnotherQuestion() throws Exception {
        String key = key();

        create(content(), key).andExpect(status().isCreated());

        jdbc.update(
                """
                update api_idempotency_requests
                set created_at = '1999-01-01',
                    expires_at = '2000-01-01'
                where user_id = ?
                """,
                user
        );

        create(content(), key)
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.error.code")
                                .value("IDEMPOTENCY_KEY_EXPIRED")
                );

        assertEquals(1, questions());
    }

    @Test
    void concurrentSameKeyCreatesOneQuestion() throws Exception {
        String key = key();
        var body = content();

        var results = race(
                () -> data(
                        create(body, key).andExpect(status().isCreated())
                ).get("questionId").longValue(),
                () -> data(
                        create(body, key).andExpect(status().isCreated())
                ).get("questionId").longValue()
        );

        assertEquals(results.get(0), results.get(1));
        assertEquals(1, questions());
        assertEquals(1, audits());
    }

    @Test
    void rejectsInvalidKeyAndCrossCompanyOrMismatchedSources()
            throws Exception {
        create(content(), "bad-key")
                .andExpect(status().isBadRequest());

        mvc.perform(
                post("/v1/admin/quiz-questions")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(content()))
        ).andExpect(status().isBadRequest());

        var wrongCompany = content();
        wrongCompany.put("companyId", company());

        create(wrongCompany, key())
                .andExpect(status().isNotFound());

        var wrongVersion = content();
        wrongVersion.put(
                "evidence",
                List.of(Map.of(
                        "sectionId", section,
                        "disclosureVersionId",
                        original(disclosure, "PUBLISHED", 2),
                        "quote", QUOTE
                ))
        );

        create(wrongVersion, key())
                .andExpect(status().isNotFound());

        var wrongQuote = content();
        wrongQuote.put(
                "evidence",
                List.of(Map.of(
                        "sectionId", section,
                        "disclosureVersionId", source,
                        "quote", "abc"
                ))
        );

        create(wrongQuote, key())
                .andExpect(status().isBadRequest());

        assertEquals(0, questions());
    }

    @Test
    void draftSourcesCanBeRegisteredButCannotEnterReviewUntilPublished()
            throws Exception {
        jdbc.update(
                "update disclosure_versions set status = 'DRAFT' where id = ?",
                source
        );

        var item = data(
                create(content(), key()).andExpect(status().isCreated())
        );

        change(item, "IN_REVIEW")
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.error.code").value("CONTENT_UNAVAILABLE")
                );

        jdbc.update(
                "update disclosure_versions set status = 'PUBLISHED' where id = ?",
                source
        );

        advance(item, "IN_REVIEW");
    }

    @Test
    void followsReviewPublicationSuspensionAndReapprovalFlow()
            throws Exception {
        var item = data(create(content(), key()));

        change(item, "PUBLISHED").andExpect(status().isConflict());

        item = advance(item, "IN_REVIEW");
        item = advance(item, "APPROVED");
        item = advance(item, "PUBLISHED");

        assertNotNull(jdbc.queryForObject(
                "select published_version_no from quiz_questions where id = ?",
                Integer.class,
                qid(item)
        ));

        item = advance(item, "SUSPENDED");

        assertNull(jdbc.queryForObject(
                "select published_version_no from quiz_questions where id = ?",
                Integer.class,
                qid(item)
        ));

        change(item, "PUBLISHED").andExpect(status().isConflict());

        item = advance(item, "IN_REVIEW");
        item = advance(item, "APPROVED");
        item = advance(item, "PUBLISHED");

        assertEquals("PUBLISHED", state(vid(item)));
        assertEquals(8, revision(qid(item)));
    }

    @Test
    void editingPublishedContentCreatesNewDraftAndPreservesOriginalOptionAndEvidenceIds()
            throws Exception {
        var first = published();

        long old = vid(first);
        long option = first.get("options").get(0)
                .get("optionId").longValue();

        var body = patchBody(first);
        var content = content();

        content.put("question", "수정한 문제");
        body.put("content", content);

        var edited = data(
                update(qid(first), body).andExpect(status().isOk())
        );

        assertNotEquals(old, vid(edited));
        assertEquals("DRAFT", edited.get("status").asText());
        assertEquals("PUBLISHED", state(old));

        assertEquals(
                1,
                scalar(
                        "select published_version_no from quiz_questions where id = ?",
                        qid(first)
                )
        );

        assertEquals(
                option,
                scalar(
                        "select id from quiz_options where quiz_version_id = ? and position = 1",
                        old
                )
        );

        edited = advance(edited, "IN_REVIEW");
        edited = advance(edited, "APPROVED");
        edited = advance(edited, "PUBLISHED");

        assertEquals("SUPERSEDED", state(old));

        assertEquals(
                2,
                scalar(
                        "select published_version_no from quiz_questions where id = ?",
                        qid(first)
                )
        );

        assertEquals(
                QUOTE,
                jdbc.queryForObject(
                        "select quotation from quiz_evidence where quiz_version_id = ?",
                        String.class,
                        old
                )
        );
    }

    @Test
    void editingDraftAlsoCreatesNewImmutableContentVersion()
            throws Exception {
        var item = data(create(content(), key()));
        var body = patchBody(item);

        body.put("content", content());
        body.put("status", "DRAFT");

        var next = data(
                update(qid(item), body).andExpect(status().isOk())
        );

        assertNotEquals(vid(item), vid(next));
        assertEquals(2, revision(qid(item)));

        body.put("status", "APPROVED");

        update(qid(next), body).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsStaleRevisionAndVersionFromAnotherQuestion()
            throws Exception {
        var first = data(create(content(), key()));
        var second = data(create(content(), key()));

        var wrong = patchBody(first);
        wrong.put("questionVersionId", vid(second));
        wrong.put("status", "IN_REVIEW");

        update(qid(first), wrong).andExpect(status().isNotFound());

        advance(first, "IN_REVIEW");

        change(first, "IN_REVIEW")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @Test
    void concurrentReviewWithSameRevisionOnlyCommitsOnce()
            throws Exception {
        var item = data(create(content(), key()));
        String other = login(account("ADMIN"))[1];

        var body = patchBody(item);
        body.put("status", "IN_REVIEW");

        var results = race(
                () -> update(qid(item), body)
                        .andReturn().getResponse().getStatus(),
                () -> updateAs(qid(item), body, other)
                        .andReturn().getResponse().getStatus()
        );

        assertEquals(
                List.of(200, 409),
                results.stream().sorted().toList()
        );

        assertEquals(2, revision(qid(item)));
    }

    @Test
    void sourceSuspensionAtomicallyStopsLinkedQuizAndDoesNotAutoResume()
            throws Exception {
        var item = published();
        long before = revision(qid(item));

        sourceChange(
                disclosure, source, 1, "SUSPENDED", null
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affectedQuizCount").value(1));

        assertEquals("SUSPENDED", state(vid(item)));
        assertEquals(before + 1, revision(qid(item)));

        assertNull(jdbc.queryForObject(
                "select published_version_no from quiz_questions where id = ?",
                Integer.class,
                qid(item)
        ));

        sourceChange(
                disclosure, source, 2, "IN_REVIEW", null
        ).andExpect(status().isOk());

        sourceChange(
                disclosure, source, 3, "APPROVED", null
        ).andExpect(status().isOk());

        sourceChange(
                disclosure, source, 4, "PUBLISHED", null
        ).andExpect(status().isOk());

        assertEquals("SUSPENDED", state(vid(item)));

        var current = current(item);
        current = advance(current, "IN_REVIEW");
        current = advance(current, "APPROVED");
        advance(current, "PUBLISHED");
    }

    @Test
    void sourceCorrectionConnectionImmediatelyStopsOldQuiz()
            throws Exception {
        var item = published();
        long corrected = filing(company);
        long correctedVersion = original(corrected, "DRAFT", 1);

        sourceChange(
                corrected, correctedVersion, 1, "IN_REVIEW", disclosure
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affectedQuizCount").value(1));

        assertEquals("SUSPENDED", state(vid(item)));

        sourceChange(
                corrected, correctedVersion, 2, "APPROVED", null
        ).andExpect(status().isOk());

        sourceChange(
                corrected, correctedVersion, 3, "PUBLISHED", null
        ).andExpect(status().isOk());

        mvc.perform(
                        get("/v1/admin/quiz-questions")
                                .param("companyId", Long.toString(company))
                                .header("Authorization", "Bearer " + token)
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.content[0].evidence[0].isSuperseded")
                                .value(true)
                );
    }

    @Test
    void sourceReplacementPreservesEvidenceAndStopsQuizUsingPriorSourceVersion()
            throws Exception {
        var item = published();
        long second = original(disclosure, "APPROVED", 2);

        sourceChange(
                disclosure, second, 1, "PUBLISHED", null
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affectedQuizCount").value(1));

        assertEquals("SUSPENDED", state(vid(item)));

        assertEquals(
                section,
                scalar(
                        "select section_id from quiz_evidence where quiz_version_id = ?",
                        vid(item)
                )
        );
    }

    @Test
    void multipleAffectedVersionsIncrementLogicalQuestionOnlyOnce()
            throws Exception {
        var item = published();
        var edit = patchBody(item);
        edit.put("content", content());

        var next = data(update(qid(item), edit));
        next = advance(next, "IN_REVIEW");

        long before = revision(qid(item));

        sourceChange(
                disclosure, source, 1, "SUSPENDED", null
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affectedQuizCount").value(1));

        assertEquals(before + 1, revision(qid(item)));
        assertEquals("SUSPENDED", state(vid(item)));
        assertEquals("SUSPENDED", state(vid(next)));
    }

    @Test
    void publishingRacingSourceSuspensionCannotLeaveActiveQuiz()
            throws Exception {
        var item = data(create(content(), key()));
        item = advance(item, "IN_REVIEW");
        item = advance(item, "APPROVED");

        final var target = item;

        String other = login(account("ADMIN"))[1];

        var body = patchBody(target);
        body.put("status", "PUBLISHED");

        var sourceBody = new HashMap<String, Object>(Map.of(
                "expectedVersion", 1,
                "disclosureVersionId", source,
                "status", "SUSPENDED",
                "reason", "공시 중단"
        ));

        var results = race(
                () -> update(qid(target), body)
                        .andReturn().getResponse().getStatus(),
                () -> mvc.perform(
                        patch("/v1/admin/disclosures/{id}", disclosure)
                                .header("Authorization", "Bearer " + other)
                                .contentType("application/json")
                                .content(mapper.writeValueAsString(sourceBody))
                ).andReturn().getResponse().getStatus()
        );

        assertEquals(200, results.get(1));
        assertTrue(results.get(0) == 200 || results.get(0) == 409);
        assertEquals("SUSPENDED", state(vid(target)));

        assertNull(jdbc.queryForObject(
                "select published_version_no from quiz_questions where id = ?",
                Integer.class,
                qid(target)
        ));
    }

    @Test
    void failureAfterQuizImpactRollsBackSourceQuizPointerRevisionsAndAudits()
            throws Exception {
        var item = published();

        long audit = audits();
        long before = revision(qid(item));

        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new TransientDataAccessResourceException("test-only");
        }).when(impacts).suspendUnavailable(
                anySet(),
                anyLong(),
                anyString(),
                any()
        );

        sourceChange(
                disclosure, source, 1, "SUSPENDED", null
        ).andExpect(status().isServiceUnavailable());

        assertEquals(
                "PUBLISHED",
                jdbc.queryForObject(
                        "select status from disclosure_versions where id = ?",
                        String.class,
                        source
                )
        );

        assertEquals("PUBLISHED", state(vid(item)));
        assertEquals(before, revision(qid(item)));
        assertEquals(audit, audits());

        assertEquals(
                1,
                scalar(
                        "select version from disclosures where id = ?",
                        disclosure
                )
        );

        assertEquals(
                1,
                scalar(
                        "select published_version_no from quiz_questions where id = ?",
                        qid(item)
                )
        );
    }

    @Test
    void failureBeforeIdempotencyCompletionRollsBackWholeCreation()
            throws Exception {
        doThrow(
                new TransientDataAccessResourceException("test-only")
        ).when(requests).complete(
                any(),
                anyLong(),
                any(),
                any()
        );

        create(content(), key())
                .andExpect(status().isServiceUnavailable());

        assertEquals(0, questions());
        assertEquals(0, audits());

        assertEquals(
                0,
                scalar(
                        "select count(*) from api_idempotency_requests where user_id = ?",
                        user
                )
        );
    }

    @Test
    void invalidStoredAnswerOrCitationCannotBePublished()
            throws Exception {
        var item = data(create(content(), key()));
        item = advance(item, "IN_REVIEW");
        item = advance(item, "APPROVED");

        jdbc.update(
                "update quiz_options set is_correct = false where quiz_version_id = ?",
                vid(item)
        );

        change(item, "PUBLISHED").andExpect(status().isConflict());

        jdbc.update(
                "update quiz_options set is_correct = true where quiz_version_id = ? and position = 2",
                vid(item)
        );

        jdbc.update(
                "update quiz_evidence set quotation = '없는 문장' where quiz_version_id = ?",
                vid(item)
        );

        change(item, "PUBLISHED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONTENT_UNAVAILABLE"));
    }

    @Test
    void databasePreventsCrossCompanyEvidenceEvenWhenBypassingService()
            throws Exception {
        var item = data(create(content(), key()));
        long foreign = company();

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        "update quiz_evidence set company_id = ? where quiz_version_id = ?",
                        foreign,
                        vid(item)
                )
        );
    }

    @Test
    void listsContentVersionsWithFiltersPagingAndAnswerData()
            throws Exception {
        var first = data(create(content(), key()));
        var edit = patchBody(first);

        edit.put("content", content());

        var newer = data(update(qid(first), edit));
        advance(newer, "IN_REVIEW");

        mvc.perform(
                        get("/v1/admin/quiz-questions")
                                .param("companyId", Long.toString(company))
                                .param("size", "1")
                                .header("Authorization", "Bearer " + token)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(
                        jsonPath("$.data.content[0].questionVersionId")
                                .value(vid(newer))
                )
                .andExpect(
                        jsonPath("$.data.content[0].options.length()").value(4)
                );

        mvc.perform(
                        get("/v1/admin/quiz-questions")
                                .param("companyId", Long.toString(company))
                                .param("status", "DRAFT")
                                .header("Authorization", "Bearer " + token)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));

        mvc.perform(
                        get("/v1/admin/quiz-questions")
                                .param("companyId", Long.toString(company))
                                .param("page", "2147483647")
                                .header("Authorization", "Bearer " + token)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    void validatesQueriesAuthenticationRoleAndRevokedSessions()
            throws Exception {
        mvc.perform(
                get("/v1/admin/quiz-questions")
        ).andExpect(status().isUnauthorized());

        String ordinary = login(account("USER"))[1];

        mvc.perform(
                get("/v1/admin/quiz-questions")
                        .header("Authorization", "Bearer " + ordinary)
        ).andExpect(status().isForbidden());

        for (String query : List.of(
                "page=-1",
                "size=0",
                "size=101",
                "page=2147483648",
                "companyId=9007199254740992",
                "status=bad",
                "other=1",
                "size=1&size=2"
        )) {
            mvc.perform(
                    get("/v1/admin/quiz-questions?" + query)
                            .header("Authorization", "Bearer " + token)
            ).andExpect(status().isBadRequest());
        }

        mvc.perform(
                get("/v1/admin/quiz-questions")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{}")
        ).andExpect(status().isBadRequest());

        jdbc.update(
                """
                update auth_sessions
                set revoked_at = utc_timestamp(6)
                where id = ?
                """,
                session
        );

        create(content(), key())
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "question-empty",
            "question-long",
            "question-null",
            "question-number",
            "options-three",
            "options-five",
            "options-repeat",
            "options-null",
            "option-unknown",
            "answer-zero",
            "answer-five",
            "answer-decimal",
            "evidence-empty",
            "evidence-repeat",
            "evidence-too-many",
            "evidence-null",
            "quote-empty",
            "unknown",
            "company-zero",
            "company-string",
            "unsafe-id"
    })
    void rejectsInvalidContentWithoutPartialWrites(String scenario)
            throws Exception {
        var body = (tools.jackson.databind.node.ObjectNode)
                mapper.valueToTree(content());

        switch (scenario) {
            case "question-empty" ->
                    body.put("question", " ");

            case "question-long" ->
                    body.put("question", "가".repeat(2001));

            case "question-null" ->
                    body.putNull("question");

            case "question-number" ->
                    body.put("question", 1);

            case "options-three" ->
                    body.withArray("options").remove(3);

            case "options-five" ->
                    body.withArray("options").add(
                            body.get("options").get(0)
                    );

            case "options-repeat" ->
                    ((tools.jackson.databind.node.ObjectNode)
                            body.get("options").get(1))
                            .put("position", 1);

            case "options-null" ->
                    body.putNull("options");

            case "option-unknown" ->
                    ((tools.jackson.databind.node.ObjectNode)
                            body.get("options").get(0))
                            .put("extra", true);

            case "answer-zero" ->
                    body.put("correctOptionPosition", 0);

            case "answer-five" ->
                    body.put("correctOptionPosition", 5);

            case "answer-decimal" ->
                    body.put("correctOptionPosition", 2.0);

            case "evidence-empty" ->
                    body.putArray("evidence");

            case "evidence-repeat" ->
                    body.withArray("evidence").add(
                            body.get("evidence").get(0)
                    );

            case "evidence-too-many" -> {
                var example = body.get("evidence").get(0);

                for (int i = 0; i < 20; i++) {
                    body.withArray("evidence").add(example);
                }
            }

            case "evidence-null" ->
                    body.putNull("evidence");

            case "quote-empty" ->
                    ((tools.jackson.databind.node.ObjectNode)
                            body.get("evidence").get(0))
                            .put("quote", " ");

            case "unknown" ->
                    body.put("status", "PUBLISHED");

            case "company-zero" ->
                    body.put("companyId", 0);

            case "company-string" ->
                    body.put("companyId", Long.toString(company));

            case "unsafe-id" ->
                    body.put("companyId", 9_007_199_254_740_992L);
        }

        create(body, key()).andExpect(status().isBadRequest());

        assertEquals(0, questions());
        assertEquals(0, audits());
    }

    @Test
    void validatesPatchShapeAndContentCompany() throws Exception {
        var item = data(create(content(), key()));
        var body = patchBody(item);

        update(qid(item), body).andExpect(status().isBadRequest());

        body.put("status", null);

        update(qid(item), body).andExpect(status().isBadRequest());

        body.remove("status");

        var other = content();
        other.put("companyId", company());
        body.put("content", other);

        update(qid(item), body).andExpect(status().isBadRequest());

        body.put("content", null);

        update(qid(item), body).andExpect(status().isBadRequest());

        assertEquals(1, revision(qid(item)));
    }

    @Test
    void reviewNotesAndTextLimitsUseCodePoints() throws Exception {
        var content = content();
        content.put("question", "😀".repeat(2000));

        var item = data(
                create(content, key()).andExpect(status().isCreated())
        );

        var body = patchBody(item);
        body.put("status", "IN_REVIEW");
        body.put("reviewNote", "😀".repeat(1001));

        update(qid(item), body).andExpect(status().isBadRequest());

        body.put("reviewNote", "😀".repeat(1000));

        update(qid(item), body).andExpect(status().isOk());
    }

    @Test
    void failedPublicationRestoresPriorPublishedVersionAndPointer()
            throws Exception {
        var old = published();

        var body = patchBody(old);
        body.put("content", content());

        var next = data(update(qid(old), body));
        next = advance(next, "IN_REVIEW");
        next = advance(next, "APPROVED");

        long before = revision(qid(old));
        long count = audits();

        var calls = new java.util.concurrent.atomic.AtomicInteger();

        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                throw new TransientDataAccessResourceException("test-only");
            }

            return invocation.callRealMethod();
        }).when(repository).view(vid(next));

        change(next, "PUBLISHED")
                .andExpect(status().isServiceUnavailable());

        assertEquals("PUBLISHED", state(vid(old)));
        assertEquals("APPROVED", state(vid(next)));

        assertEquals(
                1,
                scalar(
                        "select published_version_no from quiz_questions where id = ?",
                        qid(old)
                )
        );

        assertEquals(before, revision(qid(old)));
        assertEquals(count, audits());
    }

    @Test
    void pendingCorrectionBlocksReviewEvenIfSourceStatusWasNotChanged()
            throws Exception {
        var item = data(create(content(), key()));
        long correction = filing(company);

        jdbc.update(
                "update disclosures set corrects_disclosure_id = ? where id = ?",
                disclosure,
                correction
        );

        change(item, "IN_REVIEW")
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.error.code").value("CONTENT_UNAVAILABLE")
                );
    }

    @Test
    void publicationPreservesNewerDraftAndCannotReactivateSupersededBody()
            throws Exception {
        var old = published();

        var edit = patchBody(old);
        edit.put("content", content());

        var second = data(update(qid(old), edit));

        edit = patchBody(second);
        edit.put("content", content());

        var third = data(update(qid(second), edit));

        second = advance(current(second), "IN_REVIEW");
        second = advance(second, "APPROVED");
        advance(second, "PUBLISHED");

        assertEquals("DRAFT", state(vid(third)));
        assertEquals("SUPERSEDED", state(vid(old)));

        change(current(old), "IN_REVIEW")
                .andExpect(status().isConflict());
    }

    @Test
    void reviewAndApprovalMayBeReturnedToDraft() throws Exception {
        var item = data(create(content(), key()));

        item = advance(item, "IN_REVIEW");
        item = advance(item, "DRAFT");
        item = advance(item, "IN_REVIEW");
        item = advance(item, "APPROVED");

        advance(item, "DRAFT");
    }

    @Test
    void everyEvidenceMustBePublishedBeforeReview() throws Exception {
        long second = original(disclosure, "DRAFT", 2);

        long otherSection = scalar(
                "select id from disclosure_sections where disclosure_version_id = ?",
                second
        );

        var body = content();
        body.put(
                "evidence",
                List.of(
                        Map.of(
                                "disclosureVersionId", source,
                                "sectionId", section,
                                "quote", QUOTE
                        ),
                        Map.of(
                                "disclosureVersionId", second,
                                "sectionId", otherSection,
                                "quote", QUOTE
                        )
                )
        );

        var item = data(create(body, key()));

        change(item, "IN_REVIEW").andExpect(status().isConflict());

        jdbc.update(
                "update disclosure_versions set status = 'PUBLISHED' where id = ?",
                second
        );

        advance(item, "IN_REVIEW");
    }

    @Test
    void unrelatedDisclosureDoesNotSuspendValidQuiz() throws Exception {
        var item = published();

        long before = revision(qid(item));
        long other = filing(company);
        long otherVersion = original(other, "DRAFT", 1);

        sourceChange(
                other, otherVersion, 1, "IN_REVIEW", null
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.affectedQuizCount").value(0));

        assertEquals("PUBLISHED", state(vid(item)));
        assertEquals(before, revision(qid(item)));
    }

    @Test
    void acceptsTwentyDistinctEvidenceSectionsAndPreservesAll()
            throws Exception {
        var items = new ArrayList<Map<String, Object>>();

        items.add(Map.of(
                "disclosureVersionId", source,
                "sectionId", section,
                "quote", QUOTE
        ));

        for (int i = 1; i < 20; i++) {
            long id = insert(
                    """
                    insert into disclosure_sections(
                        disclosure_version_id,
                        company_id,
                        section_key,
                        content_text
                    )
                    values (?, ?, ?, ?)
                    """,
                    source,
                    company,
                    "s" + (i + 1),
                    ORIGINAL
            );

            items.add(Map.of(
                    "disclosureVersionId", source,
                    "sectionId", id,
                    "quote", QUOTE
            ));
        }

        var body = content();
        body.put("evidence", items);

        var item = data(
                create(body, key())
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.evidence.length()").value(20))
        );

        advance(item, "IN_REVIEW");
    }

    @Test
    void arrayOrderChangesAreNotTreatedAsSameIdempotentBody()
            throws Exception {
        String key = key();
        var body = content();

        create(body, key).andExpect(status().isCreated());

        var options = new ArrayList<>((List<?>) body.get("options"));
        Collections.reverse(options);

        body.put("options", options);

        create(body, key)
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.error.code")
                                .value("IDEMPOTENCY_CONFLICT")
                );

        assertEquals(1, questions());
    }

    @Test
    void maintenanceClearsExpiredQuizSnapshotButKeepsReplayTombstone()
            throws Exception {
        String key = key();

        create(content(), key).andExpect(status().isCreated());

        jdbc.update(
                """
                update api_idempotency_requests
                set created_at = '1999-01-01',
                    expires_at = '2000-01-01'
                where user_id = ?
                """,
                user
        );

        new com.dadot.server.domain.auth.service.VerificationMaintenance(
                jdbc,
                clock
        ).clean();

        assertEquals(
                1,
                scalar(
                        """
                        select count(*)
                        from api_idempotency_requests
                        where user_id = ?
                          and response_payload is null
                        """,
                        user
                )
        );

        create(content(), key)
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.error.code")
                                .value("IDEMPOTENCY_KEY_EXPIRED")
                );

        assertEquals(1, questions());
    }

    @Test
    void databaseRejectsNonBooleanCorrectAnswerFlags() throws Exception {
        var item = data(create(content(), key()));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        "update quiz_options set is_correct = 2 where quiz_version_id = ? and position = 1",
                        vid(item)
                )
        );
    }

    private Map<String, Object> content() {
        return new HashMap<>(Map.of(
                "companyId", company,
                "question", "어느 사업에 투자하나요?",
                "options", List.of(
                        Map.of("position", 1, "text", "유통"),
                        Map.of("position", 2, "text", "연구개발"),
                        Map.of("position", 3, "text", "관광"),
                        Map.of("position", 4, "text", "물류")
                ),
                "correctOptionPosition", 2,
                "explanation", "공시에서 연구개발 투자를 확인할 수 있습니다.",
                "evidence", List.of(Map.of(
                        "disclosureVersionId", source,
                        "sectionId", section,
                        "quote", QUOTE
                ))
        ));
    }

    private Map<String, Object> patchBody(JsonNode item) {
        return new HashMap<>(Map.of(
                "expectedVersion", item.get("version").longValue(),
                "questionVersionId", vid(item),
                "reviewNote", "원문 대조 완료"
        ));
    }

    private ResultActions create(
            Object body,
            String key
    ) throws Exception {
        return mvc.perform(
                post("/v1/admin/quiz-questions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(body))
        );
    }

    private ResultActions update(
            long id,
            Object body
    ) throws Exception {
        return updateAs(id, body, token);
    }

    private ResultActions updateAs(
            long id,
            Object body,
            String bearer
    ) throws Exception {
        return mvc.perform(
                patch("/v1/admin/quiz-questions/{id}", id)
                        .header("Authorization", "Bearer " + bearer)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(body))
        );
    }

    private ResultActions change(
            JsonNode item,
            String state
    ) throws Exception {
        var body = patchBody(item);
        body.put("status", state);

        return update(qid(item), body);
    }

    private JsonNode advance(
            JsonNode item,
            String state
    ) throws Exception {
        return data(
                change(item, state).andExpect(status().isOk())
        );
    }

    private JsonNode published() throws Exception {
        var item = data(
                create(content(), key()).andExpect(status().isCreated())
        );

        item = advance(item, "IN_REVIEW");
        item = advance(item, "APPROVED");

        return advance(item, "PUBLISHED");
    }

    private JsonNode current(JsonNode old) throws Exception {
        var node = (tools.jackson.databind.node.ObjectNode)
                mapper.readTree(mapper.writeValueAsString(old));

        node.put("version", revision(qid(old)));

        return node;
    }

    private ResultActions sourceChange(
            long id,
            long version,
            long revision,
            String state,
            Long correction
    ) throws Exception {
        var body = new HashMap<String, Object>(Map.of(
                "expectedVersion", revision,
                "disclosureVersionId", version,
                "status", state,
                "reason", "공시 검수"
        ));

        if (correction != null) {
            body.put("correctionOf", correction);
        }

        return mvc.perform(
                patch("/v1/admin/disclosures/{id}", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(body))
        );
    }

    private JsonNode data(ResultActions result) throws Exception {
        return mapper.readTree(
                result.andReturn().getResponse().getContentAsString()
        ).get("data");
    }

    private static long qid(JsonNode node) {
        return node.get("questionId").longValue();
    }

    private static long vid(JsonNode node) {
        return node.get("questionVersionId").longValue();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private long questions() {
        return scalar(
                "select count(*) from quiz_questions where company_id = ?",
                company
        );
    }

    private String state(long id) {
        return jdbc.queryForObject(
                "select status from quiz_question_versions where id = ?",
                String.class,
                id
        );
    }

    private long revision(long id) {
        return scalar(
                "select version from quiz_questions where id = ?",
                id
        );
    }

    private long audits() {
        long count = 0;

        for (long id : users) {
            count += scalar(
                    "select count(*) from admin_audit_events where actor_user_id = ?",
                    id
            );
        }

        return count;
    }

    private long scalar(
            String sql,
            Object... args
    ) {
        return Objects.requireNonNull(
                jdbc.queryForObject(sql, Long.class, args)
        );
    }

    private long account(String role) {
        long id = insert(
                "insert into users(nickname, role) values ('퀴즈테스트', ?)",
                role
        );

        users.add(id);

        return id;
    }

    private long company() {
        long id = insert(
                "insert into companies(dart_corp_code, name) values (?, '퀴즈기업')",
                String.format(
                        "%08d",
                        99003200 + companies.size()
                )
        );

        companies.add(id);

        return id;
    }

    private String[] login(long owner) {
        String id = key();

        var issued = clock.instant()
                .truncatedTo(ChronoUnit.SECONDS);

        var expires = issued.plusSeconds(3600);

        jdbc.update(
                """
                insert into auth_sessions(
                    id, user_id, created_at, expires_at
                )
                values (?, ?, ?, ?)
                """,
                id,
                owner,
                DisclosureRepository.utc(issued),
                DisclosureRepository.utc(expires)
        );

        return new String[]{
                id,
                tokens.issue(
                        owner,
                        id,
                        0,
                        issued,
                        expires
                )
        };
    }

    private long filing(long owner) {
        return insert(
                """
                insert into disclosures(
                    company_id,
                    receipt_no,
                    report_name,
                    report_type,
                    disclosed_on,
                    source_url
                )
                values (
                    ?, ?, '사업보고서', 'ANNUAL',
                    '2026-01-01', 'https://dart.fss.or.kr/'
                )
                """,
                owner,
                key().replace("-", "").substring(0, 20)
        );
    }

    private long original(
            long filing,
            String state,
            int no
    ) {
        long owner = scalar(
                "select company_id from disclosures where id = ?",
                filing
        );

        long id = insert(
                """
                insert into disclosure_versions(
                    disclosure_id,
                    company_id,
                    version_no,
                    status,
                    original_object_key,
                    content_hash,
                    extractor_version
                )
                values (
                    ?, ?, ?, ?, 'original.zip', ?, 'quiz-test'
                )
                """,
                filing,
                owner,
                no,
                state,
                key().replace("-", "").repeat(2)
        );

        insert(
                """
                insert into disclosure_sections(
                    disclosure_version_id,
                    company_id,
                    section_key,
                    heading,
                    content_text
                )
                values (?, ?, 's1', '사업의 내용', ?)
                """,
                id,
                owner,
                ORIGINAL
        );

        return id;
    }

    private long insert(
            String sql,
            Object... args
    ) {
        var key = new GeneratedKeyHolder();

        jdbc.update(connection -> {
            var s = connection.prepareStatement(
                    sql,
                    Statement.RETURN_GENERATED_KEYS
            );

            for (int i = 0; i < args.length; i++) {
                s.setObject(i + 1, args[i]);
            }

            return s;
        }, key);

        return Objects.requireNonNull(
                key.getKey()
        ).longValue();
    }

    private <T> List<T> race(
            Callable<T> a,
            Callable<T> b
    ) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(a, b).stream()
                    .map(action -> executor.submit(() -> {
                        ready.countDown();

                        assertTrue(
                                start.await(5, TimeUnit.SECONDS)
                        );

                        return action.call();
                    }))
                    .toList();

            assertTrue(
                    ready.await(5, TimeUnit.SECONDS)
            );

            start.countDown();

            return List.of(
                    futures.get(0).get(10, TimeUnit.SECONDS),
                    futures.get(1).get(10, TimeUnit.SECONDS)
            );
        }
    }
}