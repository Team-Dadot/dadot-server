package com.dadot.server.domain.disclosure;

import com.dadot.server.domain.disclosure.model.DisclosureSync.*;
import com.dadot.server.domain.disclosure.repository.DisclosureRepository;
import com.dadot.server.domain.disclosure.service.DisclosureService;
import com.dadot.server.domain.disclosure.service.DisclosureWorker;
import com.dadot.server.global.security.AccessTokenIssuer;
import com.dadot.server.global.security.SessionAuthenticationToken;
import com.dadot.server.global.security.UserPrincipal;
import com.dadot.server.infrastructure.dart.DartClient;
import com.dadot.server.infrastructure.dart.DartFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.dart.worker-enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DisclosureIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired Clock clock;
    @Autowired AccessTokenIssuer tokens;
    @Autowired JsonMapper mapper;
    @Autowired DisclosureService service;
    @Autowired DisclosureWorker worker;
    @Autowired PlatformTransactionManager manager;

    @MockitoSpyBean DisclosureRepository repository;
    @MockitoBean DartClient dart;

    private long user, company;
    private String session, token;
    private boolean safe;
    private final LocalDate day = LocalDate.of(2026, 1, 1);

    @BeforeEach
    void setup() {
        safe = false;
        assertEquals("dadot_test_db", jdbc.queryForObject("select database()", String.class));
        safe = true;

        reset(repository, dart);
        when(dart.ready()).thenReturn(true);

        user = insert("insert into users(nickname, role) values ('공시테스트', 'ADMIN')");
        company = insert(
                "insert into companies(dart_corp_code, name) values ('99990026', '공시테스트기업')"
        );

        session = UUID.randomUUID().toString();
        var issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var expires = issued.plusSeconds(3600);

        jdbc.update("""
                insert into auth_sessions(id, user_id, created_at, expires_at)
                values (?, ?, ?, ?)
                """, session, user,
                DisclosureRepository.utc(issued), DisclosureRepository.utc(expires));

        token = tokens.issue(user, session, 0, issued, expires);
        ready();
    }

    @AfterEach
    void cleanup() {
        if (!safe) return;

        reset(repository, dart);

        jdbc.update("""
                delete i from disclosure_sync_items i
                join ai_tasks t on t.id = i.task_id where t.user_id = ?
                """, user);
        jdbc.update("""
                delete r from ai_task_runs r
                join ai_tasks t on t.id = r.task_id where t.user_id = ?
                """, user);
        jdbc.update("delete from api_idempotency_requests where user_id = ?", user);
        jdbc.update("delete from ai_tasks where user_id = ?", user);
        jdbc.update("""
                delete s from disclosure_sections s
                join disclosure_versions v on v.id = s.disclosure_version_id
                where v.company_id = ?
                """, company);
        jdbc.update("delete from disclosure_versions where company_id = ?", company);
        jdbc.update("delete from disclosures where company_id = ?", company);
        jdbc.update("delete from companies where id = ?", company);
        jdbc.update("delete from auth_sessions where user_id = ?", user);
        jdbc.update("delete from users where id = ?", user);
        ready();
    }

    @Test
    void requiresAdminAndActiveSession() throws Exception {
        mvc.perform(get("/v1/admin/disclosures"))
                .andExpect(status().isUnauthorized());

        jdbc.update("update users set role = 'USER' where id = ?", user);

        mvc.perform(get("/v1/admin/disclosures")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mvc.perform(post(path())
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key())
                        .contentType("application/json").content(body()))
                .andExpect(status().isForbidden());

        jdbc.update("update users set role = 'ADMIN' where id = ?", user);
        jdbc.update(
                "update auth_sessions set revoked_at = utc_timestamp(6) where id = ?",
                session
        );

        mvc.perform(get("/v1/admin/disclosures")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());

        assertEquals(0, count("ai_tasks"));
    }

    @Test
    void queuesAtomicallyAndReplaysOriginalAcceptance() throws Exception {
        String key = key();
        String first = submit(key, body());
        String second = submit(key, body());

        assertEquals(first, second);
        assertEquals(1, count("ai_tasks"));
        assertEquals(1, count("api_idempotency_requests"));
        assertEquals(0, count("disclosures"));

        verify(dart, never()).list(anyString(), any(), any(), anyInt());

        assertFalse(jdbc.queryForObject(
                "select request_key_hash from ai_tasks where user_id = ?",
                String.class, user
        ).contains(key));
    }

    @Test
    void rejectsConflictingAndExpiredKeys() throws Exception {
        String key = key();
        submit(key, body());

        mvc.perform(post(path())
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(body().replace(
                                "2026-01-01\",\"reportTypes",
                                "2026-01-02\",\"reportTypes")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));

        jdbc.update("""
                update api_idempotency_requests
                set created_at = ?, expires_at = ? where user_id = ?
                """,
                DisclosureRepository.utc(clock.instant().minusSeconds(90000)),
                DisclosureRepository.utc(clock.instant().minusSeconds(1)),
                user);

        repository.clearExpiredResponses(clock.instant());

        mvc.perform(post(path())
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType("application/json").content(body()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_EXPIRED"));

        assertEquals(1, count("ai_tasks"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "[]",
            "null",
            "{\"fromDate\":\"2026-01-01\",\"toDate\":\"2026-01-01\",\"reportTypes\":[]}",
            "{\"fromDate\":\"2026-01-01\",\"toDate\":\"2026-01-01\",\"reportTypes\":[\"ANNUAL\",\"ANNUAL\"]}",
            "{\"fromDate\":\"2026-02-30\",\"toDate\":\"2026-03-01\",\"reportTypes\":[\"ANNUAL\"]}",
            "{\"fromDate\":\"2025-01-01\",\"toDate\":\"2026-01-02\",\"reportTypes\":[\"ANNUAL\"]}",
            "{\"fromDate\":\"2026-01-02\",\"toDate\":\"2026-01-01\",\"reportTypes\":[\"ANNUAL\"]}",
            "{\"fromDate\":\"2026-01-01\",\"toDate\":\"2026-01-01\",\"reportTypes\":[\"OTHER\"]}",
            "{\"fromDate\":\"2026-01-01\",\"toDate\":\"2026-01-01\",\"reportTypes\":[\"ANNUAL\"],\"extra\":1}"
    })
    void validatesRequestStrictly(String body) throws Exception {
        mvc.perform(post(path())
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key())
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest());

        assertEquals(0, count("ai_tasks"));
    }

    @Test
    void rejectsMissingKeyFutureDateAndMissingCompany() throws Exception {
        mvc.perform(post(path())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(body()))
                .andExpect(status().isBadRequest());

        String future = LocalDate.now(
                clock.withZone(java.time.ZoneId.of("Asia/Seoul"))
        ).plusDays(1).toString();

        mvc.perform(post(path())
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key())
                        .contentType("application/json")
                        .content(body().replace("2026-01-01", future)))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/v1/admin/companies/9007199254740000/disclosure-sync-tasks")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key())
                        .contentType("application/json").content(body()))
                .andExpect(status().isNotFound());
    }

    @Test
    void disabledClientDoesNotCommitTask() throws Exception {
        when(dart.ready()).thenReturn(false);

        mvc.perform(post(path())
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key())
                        .contentType("application/json").content(body()))
                .andExpect(status().isServiceUnavailable());

        assertEquals(0, count("ai_tasks"));
    }

    @Test
    void concurrentSameKeyCreatesOneTask() throws Exception {
        var request = new Request(day, day, List.of(ReportType.ANNUAL));
        String key = key();

        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> service.start(actor(), company, key, request));
            var two = pool.submit(() -> service.start(actor(), company, key, request));

            assertEquals(
                    one.get(10, TimeUnit.SECONDS),
                    two.get(10, TimeUnit.SECONDS)
            );
        }

        assertEquals(1, count("ai_tasks"));
    }

    @Test
    void concurrentWorkersCannotClaimTwoTasksAtOnce() throws Exception {
        submit(key(), body());
        submit(key(), body());

        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(
                    () -> tx().execute(s -> repository.claim(clock.instant()))
            );
            var two = pool.submit(
                    () -> tx().execute(s -> repository.claim(clock.instant()))
            );

            Lease first = one.get(10, TimeUnit.SECONDS);
            Lease second = two.get(10, TimeUnit.SECONDS);

            assertTrue((first == null) != (second == null));
        }

        assertEquals(1, count("ai_task_runs"));
    }

    @Test
    void taskAndIdempotencyInsertRollBackTogether() throws Exception {
        doAnswer(call -> {
            call.callRealMethod();
            throw new org.springframework.dao.DataAccessResourceFailureException("test");
        }).when(repository).create(
                anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), any(), any()
        );

        mvc.perform(post(path())
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key())
                        .contentType("application/json").content(body()))
                .andExpect(status().isServiceUnavailable());

        assertEquals(0, count("ai_tasks"));
        assertEquals(0, count("api_idempotency_requests"));
    }

    @Test
    void canonicalReportTypeSetReplaysAcrossOrderChanges() throws Exception {
        String key = key();

        String original = body().replace(
                "[\"ANNUAL\"]", "[\"QUARTERLY\",\"ANNUAL\",\"HALF_YEAR\"]"
        );
        String reordered = body().replace(
                "[\"ANNUAL\"]", "[\"HALF_YEAR\",\"QUARTERLY\",\"ANNUAL\"]"
        );

        assertEquals(submit(key, original), submit(key, reordered));
        assertEquals(1, count("ai_tasks"));
    }

    @Test
    void databaseFailureIsNotReportedAsAnEmptySuccessfulList() throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("test"))
                .when(repository).countVersions(any());

        mvc.perform(get("/v1/admin/disclosures")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void storesMetadataWithoutInventingContentAndDeduplicatesAcrossTasks() throws Exception {
        String id = taskId(submit(key(), body()));

        when(dart.list(anyString(), any(), any(), anyInt())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return page(1, "one", List.of(filing("20260101000001")));
        });

        worker.runOnce();

        assertEquals("SUCCEEDED", taskStatus(id));
        assertEquals(1, count("disclosures"));
        assertEquals(0, count("disclosure_versions"));

        mvc.perform(get("/v1/admin/disclosures")
                        .param("syncTaskId", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.syncTask.collectedCount").value(1))
                .andExpect(jsonPath("$.data.syncTask.updatedCount").value(0));

        String next = taskId(submit(key(), body()));
        ready();
        worker.runOnce();

        assertEquals("SUCCEEDED", taskStatus(next));
        assertEquals(1, count("disclosures"));
        assertEquals("EXISTING", jdbc.queryForObject(
                "select disposition from disclosure_sync_items where task_id = ?",
                String.class, next
        ));
    }

    @Test
    void paginatesAndAdvancesThirtyDayWindowsWithoutOverlaps() throws Exception {
        String id = taskId(submit(
                key(),
                body().replace(
                        "\"toDate\":\"2026-01-01\"",
                        "\"toDate\":\"2026-01-31\""
                )
        ));

        var first = new ArrayList<Filing>();
        for (int i = 1; i <= 100; i++)
            first.add(filing("20260101%06d".formatted(i)));

        when(dart.list("99990026", day, day.plusDays(29), 1))
                .thenReturn(new Page(101, 2, "first", first));
        when(dart.list("99990026", day, day.plusDays(29), 2))
                .thenReturn(new Page(
                        101, 2, "second", List.of(filing("20260101000101"))
                ));
        when(dart.list("99990026", day.plusDays(30), day.plusDays(30), 1))
                .thenReturn(new Page(0, 0, "", List.of()));

        worker.runOnce();
        assertEquals("PROCESSING", taskStatus(id));

        ready();
        worker.runOnce();
        assertEquals("PROCESSING", taskStatus(id));

        ready();
        worker.runOnce();
        assertEquals("SUCCEEDED", taskStatus(id));
        assertEquals(101, count("disclosures"));
        assertEquals(3, count("ai_task_runs"));
    }

    @Test
    void noDataSucceedsWithZeroItems() throws Exception {
        String id = taskId(submit(key(), body()));

        when(dart.list(anyString(), any(), any(), anyInt()))
                .thenReturn(new Page(0, 0, "", List.of()));

        worker.runOnce();

        assertEquals("SUCCEEDED", taskStatus(id));
        assertEquals(0, count("disclosure_sync_items"));
    }

    @Test
    void filtersRequestedTypes() throws Exception {
        submit(key(), body());

        when(dart.list(anyString(), any(), any(), anyInt()))
                .thenReturn(page(1, "one", List.of(
                        new Filing(
                                "20260101000001", "분기보고서",
                                ReportType.QUARTERLY, day
                        )
                )));

        worker.runOnce();
        assertEquals(0, count("disclosures"));
    }

    @Test
    void retriesTemporaryFailureAtMostThreeTimes() throws Exception {
        String id = taskId(submit(key(), body()));

        when(dart.list(anyString(), any(), any(), anyInt()))
                .thenThrow(new DartFailure("DART_TEMPORARY_ERROR", true));

        worker.runOnce();
        assertEquals("PROCESSING", taskStatus(id));

        ready();
        worker.runOnce();
        assertEquals("PROCESSING", taskStatus(id));

        ready();
        worker.runOnce();
        assertEquals("FAILED", taskStatus(id));

        ready();
        worker.runOnce();
        verify(dart, times(3)).list(anyString(), any(), any(), anyInt());
    }

    @Test
    void quotaFailureStopsOtherTasksAndDoesNotBecomeNoData() throws Exception {
        String id = taskId(submit(key(), body()));
        submit(key(), body());

        when(dart.list(anyString(), any(), any(), anyInt()))
                .thenThrow(new DartFailure("DART_RATE_LIMIT", false));

        worker.runOnce();
        worker.runOnce();

        assertEquals("FAILED", taskStatus(id));
        verify(dart, times(1)).list(anyString(), any(), any(), anyInt());

        assertTrue(jdbc.queryForObject(
                "select next_call_at from disclosure_sync_control where id = 1",
                java.time.LocalDateTime.class
        ).isAfter(DisclosureRepository.utc(clock.instant().plusSeconds(3500))));
    }

    @Test
    void expiredLeaseIsRecoveredAndOldWorkerCannotWrite() throws Exception {
        String id = taskId(submit(key(), body()));

        Lease old = tx().execute(s -> repository.claim(clock.instant()));
        assertNotNull(old);

        jdbc.update(
                "update ai_tasks set lease_until = ? where id = ?",
                DisclosureRepository.utc(clock.instant().minusSeconds(1)), id
        );
        ready();

        Lease current = tx().execute(s -> repository.claim(clock.instant()));
        assertNotNull(current);
        assertEquals(old.version() + 1, current.version());

        tx().executeWithoutResult(s -> repository.savePage(
                old, page(1, "old", List.of(filing("20260101000001"))), clock.instant()
        ));
        assertEquals(0, count("disclosures"));

        tx().executeWithoutResult(s -> repository.savePage(
                current, page(1, "new", List.of(filing("20260101000001"))), clock.instant()
        ));
        assertEquals("SUCCEEDED", taskStatus(id));
        assertEquals(1, count("disclosures"));
    }

    @Test
    void pageFailureRollsBackMetadataAndCursorTogether() throws Exception {
        String id = taskId(submit(key(), body()));

        when(dart.list(anyString(), any(), any(), anyInt()))
                .thenReturn(page(1, "one", List.of(filing("20260101000001"))));

        doAnswer(call -> {
            call.callRealMethod();
            throw new org.springframework.dao.DataAccessResourceFailureException("test");
        }).when(repository).savePage(any(), any(), any());

        assertThrows(
                org.springframework.dao.DataAccessResourceFailureException.class,
                worker::runOnce
        );

        assertEquals(0, count("disclosures"));
        assertEquals(0, count("disclosure_sync_items"));

        reset(repository);

        jdbc.update(
                "update ai_tasks set lease_until = ? where id = ?",
                DisclosureRepository.utc(clock.instant().minusSeconds(1)), id
        );

        ready();
        worker.runOnce();

        assertEquals("SUCCEEDED", taskStatus(id));
        assertEquals(1, count("disclosures"));
    }

    @Test
    void changedMetadataIsReportedWithoutOverwritingExistingReport() throws Exception {
        insert("""
                insert into disclosures(
                    company_id, receipt_no, report_name, report_type,
                    disclosed_on, source_url)
                values (
                """ + company
                + ", '20260101000001', '기존 제목', 'ANNUAL', '2026-01-01', 'https://example.invalid')");

        String id = taskId(submit(key(), body()));

        when(dart.list(anyString(), any(), any(), anyInt()))
                .thenReturn(page(1, "one", List.of(filing("20260101000001"))));

        worker.runOnce();

        assertEquals("FAILED", taskStatus(id));
        assertEquals("기존 제목", jdbc.queryForObject(
                "select report_name from disclosures where company_id = ?",
                String.class, company
        ));

        mvc.perform(get("/v1/admin/disclosures")
                        .param("syncTaskId", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.syncTask.failedCount").value(1))
                .andExpect(jsonPath("$.data.syncTask.failures[0].code")
                        .value("DART_METADATA_CHANGED"));
    }

    @Test
    void corruptJobFailsInsteadOfResettingOrBlockingQueue() throws Exception {
        String id = taskId(submit(key(), body()));

        jdbc.update(
                "update ai_tasks set request_parameters = '{}' where id = ?",
                id
        );

        worker.runOnce();

        assertEquals("FAILED", taskStatus(id));
        verify(dart, never()).list(anyString(), any(), any(), anyInt());
    }

    @Test
    void versionListHonorsContractAndReturnsSectionsInOneBatch() throws Exception {
        long disclosure = insert("""
                insert into disclosures(
                    company_id, receipt_no, report_name, report_type,
                    disclosed_on, source_url)
                values (
                """ + company
                + ", '20260101000001', '사업보고서', 'ANNUAL', '2026-01-01', 'https://example.invalid')");

        long version = insert("""
                insert into disclosure_versions(
                    disclosure_id, company_id, version_no, content_hash, extractor_version)
                values (
                """ + disclosure + ", " + company
                + ", 1, '" + "a".repeat(64) + "', 'test-extractor')");

        jdbc.update("""
                insert into disclosure_sections(
                    disclosure_version_id, company_id, section_key, content_text)
                values (?, ?, 'test', '근거 원문')
                """, version, company);

        mvc.perform(get("/v1/admin/disclosures")
                        .param("companyId", Long.toString(company))
                        .param("status", "DRAFT")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].disclosureVersionId").value(version))
                .andExpect(jsonPath("$.data.content[0].company.logoUrl")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.content[0].sections[0].title")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.content[0].sections[0].text").value("근거 원문"))
                .andExpect(jsonPath("$.data.syncTask").value(org.hamcrest.Matchers.nullValue()));

        mvc.perform(get("/v1/admin/disclosures")
                        .param("companyId", Long.toString(company))
                        .param("page", "2147483647")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "page=-1",
            "size=101",
            "companyId=0",
            "status=OTHER",
            "syncTaskId=bad",
            "page=1.0",
            "unknown=1"
    })
    void rejectsInvalidSearch(String query) throws Exception {
        mvc.perform(get("/v1/admin/disclosures?" + query)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingTaskAndConflictingCompanyAreNotSuccessfulEmptyResults() throws Exception {
        String id = taskId(submit(key(), body()));

        mvc.perform(get("/v1/admin/disclosures")
                        .param("syncTaskId", key())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        mvc.perform(get("/v1/admin/disclosures")
                        .param("syncTaskId", id)
                        .param("companyId", Long.toString(company + 1))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    private String body() {
        return "{\"fromDate\":\"2026-01-01\",\"toDate\":\"2026-01-01\",\"reportTypes\":[\"ANNUAL\"]}";
    }

    private String path() {
        return "/v1/admin/companies/" + company + "/disclosure-sync-tasks";
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private String submit(String key, String body) throws Exception {
        return mvc.perform(post(path())
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
    }

    private String taskId(String json) {
        return mapper.readTree(json).get("data").get("taskId").asText();
    }

    private String taskStatus(String id) {
        return jdbc.queryForObject(
                "select status from ai_tasks where id = ?", String.class, id
        );
    }

    private void ready() {
        jdbc.update("""
                update disclosure_sync_control
                set next_call_at = '1970-01-01' where id = 1
                """);
        jdbc.update("""
                update ai_tasks set available_at = '1970-01-01'
                where type = 'DISCLOSURE_SYNC' and status = 'PROCESSING'
                """);
    }

    private Filing filing(String receipt) {
        return new Filing(
                receipt, "사업보고서 (2025.12)", ReportType.ANNUAL, day
        );
    }

    private Page page(int total, String hash, List<Filing> filings) {
        return new Page(total, (total + 99) / 100, hash, filings);
    }

    private SessionAuthenticationToken actor() {
        return new SessionAuthenticationToken(
                new UserPrincipal(user), session, 0,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }

    private TransactionTemplate tx() {
        var tx = new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setTimeout(10);
        return tx;
    }

    private long insert(String sql) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(
                c -> c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS),
                holder
        );
        return Objects.requireNonNull(holder.getKey()).longValue();
    }

    private long count(String table) {
        String column = table.equals("ai_tasks") || table.equals("api_idempotency_requests")
                ? "user_id" : "company_id";

        if (table.equals("ai_task_runs") || table.equals("disclosure_sync_items"))
            return Objects.requireNonNull(jdbc.queryForObject(
                    "select count(*) from " + table
                            + " r join ai_tasks t on t.id = r.task_id where t.user_id = ?",
                    Long.class, user
            ));

        return Objects.requireNonNull(jdbc.queryForObject(
                "select count(*) from " + table + " where " + column + " = ?",
                Long.class, column.equals("user_id") ? user : company
        ));
    }
}