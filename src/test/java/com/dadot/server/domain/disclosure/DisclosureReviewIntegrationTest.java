package com.dadot.server.domain.disclosure;

import com.dadot.server.domain.company.support.CompanyLogoUrlResolver;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import com.dadot.server.domain.disclosure.repository.DisclosureRepository;
import com.dadot.server.domain.disclosure.repository.DisclosureReviewRepository;
import com.dadot.server.global.security.AccessTokenIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.json.JsonMapper;

import java.sql.Statement;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
class DisclosureReviewIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired Clock clock;
    @Autowired AccessTokenIssuer tokens;

    @MockitoSpyBean DisclosureReviewRepository repository;
    @MockitoSpyBean CompanyLogoUrlResolver logos;

    private final List<Long> users = new ArrayList<>();
    private final List<Long> companies = new ArrayList<>();

    private boolean safe;
    private long user;
    private long company;
    private long disclosure;
    private long version;
    private String session;
    private String token;

    @BeforeEach
    void setup() {
        safe = false;

        assertEquals(
                "dadot_test_db",
                jdbc.queryForObject(
                        "select database()",
                        String.class
                )
        );

        safe = true;

        reset(repository, logos);
        users.clear();
        companies.clear();

        user = account("ADMIN");
        company = company();

        var login = login(user);

        session = login[0];
        token = login[1];

        disclosure = filing(company);
        version = content(disclosure, "DRAFT", 1);
    }

    @AfterEach
    void cleanup() {
        if (!safe) {
            return;
        }

        reset(repository, logos);

        for (long id : users) {
            jdbc.update(
                    """
                    delete from admin_audit_events
                    where actor_user_id = ?
                    """,
                    id
            );
        }

        for (long id : companies) {
            jdbc.update(
                    """
                    delete s
                    from disclosure_sections s
                    join disclosure_versions v
                      on v.id = s.disclosure_version_id
                    where v.company_id = ?
                    """,
                    id
            );

            jdbc.update(
                    "delete from disclosure_versions where company_id = ?",
                    id
            );

            jdbc.update(
                    """
                    update disclosures
                    set corrects_disclosure_id = null
                    where company_id = ?
                    """,
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
    void reviewsPublishesSuspendsAndReapprovesWithoutChangingOriginals()
            throws Exception {
        long section = scalar(
                """
                select id from disclosure_sections
                where disclosure_version_id = ?
                """,
                version
        );

        send("IN_REVIEW", 1)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.disclosure.sectionCount").value(1))
                .andExpect(jsonPath("$.data.disclosure.sections").doesNotExist())
                .andExpect(jsonPath("$.data.disclosure.reviewNote").value("검수 사유"))
                .andExpect(jsonPath("$.data.disclosure.reviewedBy").value(user))
                .andExpect(jsonPath("$.data.disclosure.reviewedAt").isString())
                .andExpect(jsonPath("$.data.affectedQuizCount").value(0))
                .andExpect(jsonPath("$.data.affectedInterviewCount").value(0));

        send("APPROVED", 2).andExpect(status().isOk());
        send("PUBLISHED", 3).andExpect(status().isOk());
        send("SUSPENDED", 4).andExpect(status().isOk());
        send("PUBLISHED", 5).andExpect(status().isConflict());
        send("IN_REVIEW", 5).andExpect(status().isOk());
        send("APPROVED", 6).andExpect(status().isOk());
        send("PUBLISHED", 7).andExpect(status().isOk());

        assertEquals(8, revision(disclosure));
        assertEquals(7, audits());
        assertEquals("PUBLISHED", state(version));

        assertEquals(
                section,
                scalar(
                        """
                        select id from disclosure_sections
                        where disclosure_version_id = ?
                        """,
                        version
                )
        );

        assertEquals(
                "원문 근거",
                jdbc.queryForObject(
                        "select content_text from disclosure_sections where id = ?",
                        String.class,
                        section
                )
        );

        mvc.perform(
                        get("/v1/admin/disclosures")
                                .param("companyId", Long.toString(company))
                                .header("Authorization", "Bearer " + token)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].version").value(8));

        mvc.perform(
                        get(
                                "/v1/admin/disclosures/{d}/versions/{v}/sections",
                                disclosure,
                                version
                        ).header("Authorization", "Bearer " + token)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].text").value("원문 근거"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "PUBLISHED",
            "APPROVED",
            "SUSPENDED",
            "SUPERSEDED",
            "DRAFT"
    })
    void rejectsInvalidDraftTransitions(String next) throws Exception {
        send(next, 1)
                .andExpect(status().isConflict())
                .andExpect(
                        jsonPath("$.error.code")
                                .value("INVALID_STATUS_TRANSITION")
                );

        assertEquals(1, revision(disclosure));
        assertEquals(0, audits());
    }

    @Test
    void reviewAndApprovalCanBeReturnedToDraft() throws Exception {
        send("IN_REVIEW", 1).andExpect(status().isOk());
        send("DRAFT", 2).andExpect(status().isOk());
        send("IN_REVIEW", 3).andExpect(status().isOk());
        send("APPROVED", 4).andExpect(status().isOk());
        send("DRAFT", 5).andExpect(status().isOk());

        assertEquals("DRAFT", state(version));
    }

    @Test
    void rejectsStaleRevisionWithoutWritingAudit() throws Exception {
        send("IN_REVIEW", 1).andExpect(status().isOk());

        send("APPROVED", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));

        assertEquals("IN_REVIEW", state(version));
        assertEquals(1, audits());
    }

    @Test
    void concurrentRequestsWithSameRevisionOnlyCommitOnce() throws Exception {
        String other = login(account("ADMIN"))[1];

        List<Integer> results = race(
                () -> request(
                        disclosure,
                        body(version, 1, "IN_REVIEW"),
                        token
                ).andReturn().getResponse().getStatus(),

                () -> request(
                        disclosure,
                        body(version, 1, "IN_REVIEW"),
                        other
                ).andReturn().getResponse().getStatus()
        );

        assertEquals(
                List.of(200, 409),
                results.stream().sorted().toList()
        );

        assertEquals(2, revision(disclosure));
        assertEquals(1, audits());
    }

    @Test
    void rejectsVersionFromAnotherDisclosureAndUnknownResources()
            throws Exception {
        long another = filing(company);
        long otherVersion = content(another, "DRAFT", 1);

        request(
                disclosure,
                body(otherVersion, 1, "IN_REVIEW"),
                token
        ).andExpect(status().isNotFound());

        request(
                9_007_199_254_740_991L,
                body(version, 1, "IN_REVIEW"),
                token
        ).andExpect(status().isNotFound());

        request(
                disclosure,
                body(9_007_199_254_740_991L, 1, "IN_REVIEW"),
                token
        ).andExpect(status().isNotFound());

        assertEquals(0, audits());
    }

    @Test
    void requiresOriginalPointerAndNonemptySections() throws Exception {
        jdbc.update(
                """
                update disclosure_versions
                set original_object_key = null
                where id = ?
                """,
                version
        );

        send("IN_REVIEW", 1).andExpect(status().isConflict());

        jdbc.update(
                """
                update disclosure_versions
                set original_object_key = 'original.zip'
                where id = ?
                """,
                version
        );

        jdbc.update(
                "delete from disclosure_sections where disclosure_version_id = ?",
                version
        );

        send("IN_REVIEW", 1).andExpect(status().isConflict());

        assertEquals(0, audits());
    }

    @Test
    void publishingNewBodySupersedesOldBodyAndKeepsItsSections()
            throws Exception {
        jdbc.update(
                "update disclosure_versions set status = 'PUBLISHED' where id = ?",
                version
        );

        long old = version;

        long section = scalar(
                "select id from disclosure_sections where disclosure_version_id = ?",
                old
        );

        version = content(disclosure, "APPROVED", 2);

        send("PUBLISHED", 1).andExpect(status().isOk());

        assertEquals("SUPERSEDED", state(old));
        assertEquals("PUBLISHED", state(version));
        assertEquals(2, revision(disclosure));
        assertEquals(2, audits());

        assertEquals(
                section,
                scalar(
                        "select id from disclosure_sections where disclosure_version_id = ?",
                        old
                )
        );

        version = old;

        send("IN_REVIEW", 2).andExpect(status().isConflict());
    }

    @Test
    void linkingCorrectionSuspendsOldPublicationAndPublishingReplacesIt()
            throws Exception {
        long old = filing(company);
        long oldVersion = content(old, "PUBLISHED", 1);

        var request = body(version, 1, "IN_REVIEW");
        request.put("correctionOf", old);

        request(disclosure, request, token)
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.disclosure.correctionOf").value(old)
                );

        assertEquals("SUSPENDED", state(oldVersion));
        assertEquals(2, revision(old));

        request(
                old,
                body(oldVersion, 2, "IN_REVIEW"),
                token
        ).andExpect(status().isOk());

        request(
                old,
                body(oldVersion, 3, "APPROVED"),
                token
        ).andExpect(status().isOk());

        request(
                old,
                body(oldVersion, 4, "PUBLISHED"),
                token
        ).andExpect(status().isConflict());

        send("APPROVED", 2).andExpect(status().isOk());
        send("PUBLISHED", 3).andExpect(status().isOk());

        assertEquals("SUPERSEDED", state(oldVersion));
        assertEquals(5, revision(old));

        assertEquals(
                old,
                scalar(
                        "select corrects_disclosure_id from disclosures where id = ?",
                        disclosure
                )
        );

        long late = content(old, "APPROVED", 2);

        request(
                old,
                body(late, 5, "PUBLISHED"),
                token
        ).andExpect(status().isConflict());

        assertEquals("APPROVED", state(late));
    }

    @Test
    void correctionOmissionKeepsLinkAndNullClearsOnlyBeforePublication()
            throws Exception {
        long target = filing(company);

        var first = body(version, 1, "IN_REVIEW");
        first.put("correctionOf", target);

        request(disclosure, first, token).andExpect(status().isOk());

        send("APPROVED", 2)
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.disclosure.correctionOf").value(target)
                );

        var clear = body(version, 3, "DRAFT");
        clear.put("correctionOf", null);

        request(disclosure, clear, token)
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.disclosure.correctionOf")
                                .value(org.hamcrest.Matchers.nullValue())
                );

        assertNull(
                jdbc.queryForObject(
                        "select corrects_disclosure_id from disclosures where id = ?",
                        Long.class,
                        disclosure
                )
        );
    }

    @Test
    void cannotRelinkCorrectionAfterPublication() throws Exception {
        jdbc.update(
                "update disclosure_versions set status = 'PUBLISHED' where id = ?",
                version
        );

        var request = body(version, 1, "SUSPENDED");
        request.put("correctionOf", filing(company));

        request(disclosure, request, token)
                .andExpect(status().isConflict());

        assertEquals("PUBLISHED", state(version));
        assertEquals(0, audits());
    }

    @Test
    void rejectsSelfCorrectionAndCycles() throws Exception {
        var self = body(version, 1, "IN_REVIEW");
        self.put("correctionOf", disclosure);

        request(disclosure, self, token)
                .andExpect(status().isConflict());

        long second = filing(company);
        long third = filing(company);

        jdbc.update(
                "update disclosures set corrects_disclosure_id = ? where id = ?",
                third,
                second
        );

        jdbc.update(
                "update disclosures set corrects_disclosure_id = ? where id = ?",
                disclosure,
                third
        );

        var cycle = body(version, 1, "IN_REVIEW");
        cycle.put("correctionOf", second);

        request(disclosure, cycle, token)
                .andExpect(status().isConflict());

        assertEquals(0, audits());
    }

    @Test
    void rejectsCorrectionToMissingOrDifferentCompany() throws Exception {
        long foreign = filing(company());

        for (long target : List.of(
                foreign,
                9_007_199_254_740_991L
        )) {
            var body = body(version, 1, "IN_REVIEW");
            body.put("correctionOf", target);

            request(disclosure, body, token)
                    .andExpect(status().isNotFound());
        }

        assertEquals(0, audits());
    }

    @Test
    void concurrentOppositeCorrectionLinksCannotCreateCycle()
            throws Exception {
        long second = filing(company);
        long secondVersion = content(second, "DRAFT", 1);

        String other = login(account("ADMIN"))[1];

        var a = body(version, 1, "IN_REVIEW");
        a.put("correctionOf", second);

        var b = body(secondVersion, 1, "IN_REVIEW");
        b.put("correctionOf", disclosure);

        List<Integer> results = race(
                () -> request(
                        disclosure,
                        a,
                        token
                ).andReturn().getResponse().getStatus(),

                () -> request(
                        second,
                        b,
                        other
                ).andReturn().getResponse().getStatus()
        );

        assertEquals(
                List.of(200, 409),
                results.stream().sorted().toList()
        );

        assertEquals(
                1,
                scalar(
                        """
                        select count(*)
                        from disclosures
                        where company_id = ?
                          and corrects_disclosure_id is not null
                        """,
                        company
                )
        );
    }

    @Test
    void rollsBackStatusRevisionAndAuditWhenStorageFailsAfterMutation()
            throws Exception {
        doThrow(
                new TransientDataAccessResourceException("test-only")
        ).when(repository).summary(
                eq(disclosure),
                eq(version),
                any()
        );

        send("IN_REVIEW", 1)
                .andExpect(status().isServiceUnavailable());

        assertEquals("DRAFT", state(version));
        assertEquals(1, revision(disclosure));
        assertEquals(0, audits());
    }

    @Test
    void correctionSideEffectsAlsoRollbackTogether() throws Exception {
        long old = filing(company);
        long oldVersion = content(old, "PUBLISHED", 1);

        doThrow(
                new TransientDataAccessResourceException("test-only")
        ).when(repository).summary(
                eq(disclosure),
                eq(version),
                any()
        );

        var body = body(version, 1, "IN_REVIEW");
        body.put("correctionOf", old);

        request(disclosure, body, token)
                .andExpect(status().isServiceUnavailable());

        assertEquals("PUBLISHED", state(oldVersion));
        assertEquals(1, revision(old));
        assertEquals(1, revision(disclosure));

        assertNull(
                jdbc.queryForObject(
                        "select corrects_disclosure_id from disclosures where id = ?",
                        Long.class,
                        disclosure
                )
        );

        assertEquals(0, audits());
    }

    @Test
    void checksAuthenticationRoleAndCurrentSession() throws Exception {
        mvc.perform(
                patch("/v1/admin/disclosures/{id}", disclosure)
                        .contentType("application/json")
                        .content(
                                mapper.writeValueAsString(
                                        body(version, 1, "IN_REVIEW")
                                )
                        )
        ).andExpect(status().isUnauthorized());

        String ordinary = login(account("USER"))[1];

        request(
                disclosure,
                body(version, 1, "IN_REVIEW"),
                ordinary
        ).andExpect(status().isForbidden());

        jdbc.update(
                "update users set role = 'USER' where id = ?",
                user
        );

        send("IN_REVIEW", 1).andExpect(status().isForbidden());

        jdbc.update(
                "update users set role = 'ADMIN' where id = ?",
                user
        );

        jdbc.update(
                """
                update auth_sessions
                set revoked_at = utc_timestamp(6)
                where id = ?
                """,
                session
        );

        send("IN_REVIEW", 1).andExpect(status().isUnauthorized());

        assertEquals(0, audits());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "null",
            "[]",
            "{\"expectedVersion\":1,\"disclosureVersionId\":1,\"status\":\"IN_REVIEW\",\"reason\":\" \"}",
            "{\"expectedVersion\":1.0,\"disclosureVersionId\":1,\"status\":\"IN_REVIEW\",\"reason\":\"x\"}",
            "{\"expectedVersion\":\"1\",\"disclosureVersionId\":1,\"status\":\"IN_REVIEW\",\"reason\":\"x\"}",
            "{\"expectedVersion\":1,\"disclosureVersionId\":0,\"status\":\"IN_REVIEW\",\"reason\":\"x\"}",
            "{\"expectedVersion\":9007199254740992,\"disclosureVersionId\":1,\"status\":\"IN_REVIEW\",\"reason\":\"x\"}",
            "{\"expectedVersion\":1,\"disclosureVersionId\":1,\"status\":\"published\",\"reason\":\"x\"}",
            "{\"expectedVersion\":1,\"disclosureVersionId\":1,\"status\":\"IN_REVIEW\",\"reason\":null}",
            "{\"expectedVersion\":1,\"disclosureVersionId\":1,\"status\":\"IN_REVIEW\",\"reason\":\"x\",\"correctionOf\":true}",
            "{\"expectedVersion\":1,\"disclosureVersionId\":1,\"status\":\"IN_REVIEW\",\"reason\":\"x\",\"extra\":1}"
    })
    void rejectsMalformedBodies(String body) throws Exception {
        mvc.perform(
                patch("/v1/admin/disclosures/{id}", disclosure)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(body)
        ).andExpect(status().isBadRequest());

        assertEquals(0, audits());
    }

    @Test
    void enforcesReasonCodePointsAndRejectsQueriesAndInvalidPath()
            throws Exception {
        var body = body(version, 1, "IN_REVIEW");
        body.put("reason", "😀".repeat(1001));

        request(disclosure, body, token)
                .andExpect(status().isBadRequest());

        body.put("reason", "😀".repeat(1000));

        request(disclosure, body, token)
                .andExpect(status().isOk());

        mvc.perform(
                patch("/v1/admin/disclosures/{id}", disclosure)
                        .param("status", "DRAFT")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(
                                mapper.writeValueAsString(
                                        body(version, 2, "APPROVED")
                                )
                        )
        ).andExpect(status().isBadRequest());

        request(
                0,
                body(version, 2, "APPROVED"),
                token
        ).andExpect(status().isBadRequest());

        assertEquals(1, audits());
    }

    @Test
    void rejectedPublicationCannotBeManuallyMarkedSupersededWithoutReplacement()
            throws Exception {
        jdbc.update(
                "update disclosure_versions set status = 'PUBLISHED' where id = ?",
                version
        );

        send("SUPERSEDED", 1).andExpect(status().isConflict());

        assertEquals("PUBLISHED", state(version));
    }

    @Test
    void doesNotPublishTwoCompetingCorrectionsForOneSource()
            throws Exception {
        long target = filing(company);
        long existing = filing(company);

        content(existing, "PUBLISHED", 1);

        jdbc.update(
                "update disclosures set corrects_disclosure_id = ? where id = ?",
                target,
                existing
        );

        var request = body(version, 1, "IN_REVIEW");
        request.put("correctionOf", target);

        request(disclosure, request, token)
                .andExpect(status().isConflict());

        request.put("correctionOf", existing);

        request(disclosure, request, token)
                .andExpect(status().isOk());

        assertEquals(
                "SUSPENDED",
                jdbc.queryForObject(
                        "select status from disclosure_versions where disclosure_id = ?",
                        String.class,
                        existing
                )
        );
    }

    @Test
    void invalidLogoOnlyDropsLogoButUnavailableConfigurationRollsBack()
            throws Exception {
        jdbc.update(
                "update companies set logo_key = 'bad-key' where id = ?",
                company
        );

        doThrow(
                new IllegalStateException("test-only")
        ).when(logos).resolve("bad-key");

        send("IN_REVIEW", 1)
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.disclosure.company.logoUrl")
                                .value(org.hamcrest.Matchers.nullValue())
                );

        doThrow(
                new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)
        ).when(logos).resolve("bad-key");

        send("APPROVED", 2)
                .andExpect(status().isServiceUnavailable());

        assertEquals("IN_REVIEW", state(version));
        assertEquals(2, revision(disclosure));
        assertEquals(1, audits());
    }

    @Test
    void correctionOnlyChangeStillIncrementsOnceAndKeepsAudit()
            throws Exception {
        long target = filing(company);

        var request = body(version, 1, "DRAFT");
        request.put("correctionOf", target);

        request(disclosure, request, token)
                .andExpect(status().isOk());

        assertEquals(2, revision(disclosure));
        assertEquals(2, audits());
        assertEquals("DRAFT", state(version));
    }

    @Test
    void supersedesOlderUnpublishedDraftsButKeepsNewerDraft()
            throws Exception {
        long old = version;

        version = content(disclosure, "APPROVED", 2);

        long future = content(disclosure, "DRAFT", 3);

        send("PUBLISHED", 1).andExpect(status().isOk());

        assertEquals("SUPERSEDED", state(old));
        assertEquals("DRAFT", state(future));

        request(
                disclosure,
                body(old, 2, "IN_REVIEW"),
                token
        ).andExpect(status().isConflict());
    }

    @Test
    void cannotPublishOlderBodyOverNewerPublishedHistory()
            throws Exception {
        jdbc.update(
                "update disclosure_versions set status = 'APPROVED' where id = ?",
                version
        );

        content(disclosure, "SUSPENDED", 2);

        send("PUBLISHED", 1).andExpect(status().isConflict());

        assertEquals("APPROVED", state(version));
        assertEquals(0, audits());
    }

    private ResultActions send(
            String next,
            long revision
    ) throws Exception {
        return request(
                disclosure,
                body(version, revision, next),
                token
        );
    }

    private ResultActions request(
            long id,
            Object body,
            String bearer
    ) throws Exception {
        return mvc.perform(
                patch("/v1/admin/disclosures/{id}", id)
                        .header("Authorization", "Bearer " + bearer)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(body))
        );
    }

    private Map<String, Object> body(
            long versionId,
            long revision,
            String next
    ) {
        return new java.util.HashMap<>(
                Map.of(
                        "expectedVersion", revision,
                        "disclosureVersionId", versionId,
                        "status", next,
                        "reason", "검수 사유"
                )
        );
    }

    private long account(String role) {
        long id = insert(
                """
                insert into users(nickname, role)
                values ('검수테스트', ?)
                """,
                role
        );

        users.add(id);

        return id;
    }

    private long company() {
        long id = insert(
                """
                insert into companies(dart_corp_code, name)
                values (?, '검수테스트기업')
                """,
                String.format(
                        "%08d",
                        99003000 + companies.size()
                )
        );

        companies.add(id);

        return id;
    }

    private String[] login(long owner) {
        String id = UUID.randomUUID().toString();

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
                UUID.randomUUID()
                        .toString()
                        .replace("-", "")
                        .substring(0, 20)
        );
    }

    private long content(
            long filing,
            String state,
            int number
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
                    ?, ?, ?, ?, 'original.zip', ?, 'review-test'
                )
                """,
                filing,
                owner,
                number,
                state,
                UUID.randomUUID()
                        .toString()
                        .replace("-", "")
                        .repeat(2)
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
                values (
                    ?, ?, 'section-1', '사업의 내용', '원문 근거'
                )
                """,
                id,
                owner
        );

        return id;
    }

    private String state(long id) {
        return jdbc.queryForObject(
                "select status from disclosure_versions where id = ?",
                String.class,
                id
        );
    }

    private long revision(long id) {
        return scalar(
                "select version from disclosures where id = ?",
                id
        );
    }

    private long audits() {
        long total = 0;

        for (long id : users) {
            total += scalar(
                    "select count(*) from admin_audit_events where actor_user_id = ?",
                    id
            );
        }

        return total;
    }

    private long scalar(
            String sql,
            Object... args
    ) {
        return Objects.requireNonNull(
                jdbc.queryForObject(sql, Long.class, args)
        );
    }

    private long insert(
            String sql,
            Object... args
    ) {
        var key = new GeneratedKeyHolder();

        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    sql,
                    Statement.RETURN_GENERATED_KEYS
            );

            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }

            return statement;
        }, key);

        return Objects.requireNonNull(
                key.getKey()
        ).longValue();
    }

    private List<Integer> race(
            java.util.concurrent.Callable<Integer> a,
            java.util.concurrent.Callable<Integer> b
    ) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = List.of(a, b).stream()
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
                    results.get(0).get(10, TimeUnit.SECONDS),
                    results.get(1).get(10, TimeUnit.SECONDS)
            );
        }
    }
}