package com.dadot.server.domain.disclosure;

import com.dadot.server.domain.disclosure.model.DisclosureSync.*;
import com.dadot.server.domain.disclosure.repository.DisclosureOriginalRepository;
import com.dadot.server.domain.disclosure.repository.DisclosureRepository;
import com.dadot.server.domain.disclosure.service.DisclosureWorker;
import com.dadot.server.domain.disclosure.support.OriginalStore;
import com.dadot.server.global.security.AccessTokenIssuer;
import com.dadot.server.infrastructure.dart.DartArchiveExtractor;
import com.dadot.server.infrastructure.dart.DartClient;
import com.dadot.server.infrastructure.dart.DartDocumentClient;
import com.dadot.server.infrastructure.dart.DartFailure;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.dadot.server.infrastructure.dart.DartOriginalFixture.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"app.dart.worker-enabled=false", "app.dart.originals.enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DisclosureOriginalIntegrationTest {
    private static final Path ROOT = temporaryRoot();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.dart.originals.root", ROOT::toString);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired Clock clock;
    @Autowired AccessTokenIssuer tokens;
    @Autowired DisclosureRepository repository;
    @Autowired DisclosureWorker worker;
    @Autowired DartArchiveExtractor extractor;
    @Autowired PlatformTransactionManager manager;
    @MockitoBean DartClient dart;
    @MockitoBean DartDocumentClient documents;
    @MockitoSpyBean DisclosureOriginalRepository originals;
    @MockitoSpyBean OriginalStore store;
    private long user, company;
    private String session, token;
    private boolean safe;
    private final Map<String, byte[]> archives = new HashMap<>();
    private final LocalDate day = LocalDate.of(2026, 1, 1);

    @BeforeEach void setup() throws Exception {
        safe = false; assertEquals("dadot_test_db", jdbc.queryForObject("select database()", String.class)); safe = true;
        reset(dart, documents, originals, store); when(dart.ready()).thenReturn(true);
        user = insert("insert into users(nickname, role) values ('원문테스트', 'ADMIN')");
        company = insert("insert into companies(dart_corp_code, name) values ('99990028', '원문기업')");
        session = UUID.randomUUID().toString(); var issued = clock.instant().truncatedTo(ChronoUnit.SECONDS); var expires = issued.plusSeconds(3600);
        jdbc.update("insert into auth_sessions(id, user_id, created_at, expires_at) values (?, ?, ?, ?)",
                session, user, DisclosureRepository.utc(issued), DisclosureRepository.utc(expires));
        token = tokens.issue(user, session, 0, issued, expires);
        archives.put(RECEIPT, original(RECEIPT, "<SECTION-1><TITLE>사업의 내용</TITLE><P>원문 R&D와 숫자 100</P></SECTION-1>"));
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            Files.write(call.getArgument(1, Path.class), archives.get(call.getArgument(0, String.class))); return null;
        }).when(documents).download(anyString(), any());
        ready();
    }
    @AfterEach void cleanup() throws Exception {
        if (!safe) return;
        reset(dart, documents, originals, store);
        jdbc.update("delete i from disclosure_sync_items i join ai_tasks t on t.id = i.task_id where t.user_id = ?", user);
        jdbc.update("delete r from ai_task_runs r join ai_tasks t on t.id = r.task_id where t.user_id = ?", user);
        jdbc.update("delete from api_idempotency_requests where user_id = ?", user);
        jdbc.update("delete from ai_tasks where user_id = ?", user);
        jdbc.update("delete s from disclosure_sections s join disclosure_versions v on v.id = s.disclosure_version_id where v.company_id = ?", company);
        jdbc.update("delete from disclosure_versions where company_id = ?", company);
        jdbc.update("delete from disclosures where company_id = ?", company);
        jdbc.update("delete from companies where id = ?", company);
        jdbc.update("delete from auth_sessions where user_id = ?", user); jdbc.update("delete from users where id = ?", user);
        deleteTree(ROOT.resolve("disclosures/" + company)); ready();
    }
    @AfterAll static void cleanupRoot() throws Exception { deleteTree(ROOT); }

    @Test void completesOnlyAfterOriginalVersionAndSectionsAreStored() throws Exception {
        String key = key(), task = begin(key, List.of(filing(RECEIPT)));
        assertEquals("PROCESSING", state(task)); assertEquals(0, count("disclosure_versions"));
        assertEquals("PROCESSING", jdbc.queryForObject("select status from disclosure_sync_items where task_id = ?", String.class, task));
        ready(); worker.runOnce();
        assertEquals("SUCCEEDED", state(task)); assertEquals(1, count("disclosure_versions")); assertEquals(2, count("disclosure_sections"));
        String object = jdbc.queryForObject("select original_object_key from disclosure_versions where company_id = ?", String.class, company);
        assertTrue(Files.exists(ROOT.resolve(object))); assertFalse(object.startsWith("/"));
        assertEquals(2L, jdbc.queryForObject("select version from disclosures where company_id = ?", Long.class, company));
        String locator = jdbc.queryForObject("select locator_json from disclosure_sections where company_id = ? order by id desc limit 1", String.class, company);
        assertTrue(locator.contains(RECEIPT + ".xml")); assertTrue(locator.contains("textStart"));
        mvc.perform(get("/v1/admin/disclosures").param("syncTaskId", task).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content[0].sectionCount").value(2))
                .andExpect(jsonPath("$.data.content[0].sections").doesNotExist())
                .andExpect(jsonPath("$.data.syncTask.collectedCount").value(1));
        assertEquals(task, submit(key)); verify(documents, times(1)).download(anyString(), any());
        try (var files = Files.list(ROOT.resolve(".work"))) { assertEquals(0, files.count()); }
    }
    @Test void repeatsSameHashWithoutDuplicateVersionsOrSections() throws Exception {
        finish(begin(key(), List.of(filing(RECEIPT))));
        long version = versionId(); finish(begin(key(), List.of(filing(RECEIPT))));
        assertEquals(1, count("disclosure_versions")); assertEquals(2, count("disclosure_sections")); assertEquals(version, versionId());
        assertEquals(1L, jdbc.queryForObject("select count(*) from disclosure_sync_items where content_result = 'UNCHANGED' and task_id in (select id from ai_tasks where user_id = ?)", Long.class, user));
    }
    @Test void changedOriginalCreatesDraftAndPreservesPublishedVersionAndEvidenceIds() throws Exception {
        finish(begin(key(), List.of(filing(RECEIPT)))); long previous = versionId();
        jdbc.update("update disclosure_versions set status = 'PUBLISHED' where id = ?", previous);
        String oldText = jdbc.queryForObject("select content_text from disclosure_sections where disclosure_version_id = ? order by id desc limit 1", String.class, previous);
        archives.put(RECEIPT, original(RECEIPT, "<P>변경된 원문 200</P>"));
        String task = begin(key(), List.of(filing(RECEIPT))); finish(task);
        assertEquals(2, count("disclosure_versions"));
        assertEquals("PUBLISHED", jdbc.queryForObject("select status from disclosure_versions where id = ?", String.class, previous));
        assertEquals(oldText, jdbc.queryForObject("select content_text from disclosure_sections where disclosure_version_id = ? order by id desc limit 1", String.class, previous));
        assertEquals("DRAFT", jdbc.queryForObject("select status from disclosure_versions where id = ?", String.class, versionId()));
        assertEquals(3L, jdbc.queryForObject("select version from disclosures where company_id = ?", Long.class, company));
        mvc.perform(get("/v1/admin/disclosures").param("syncTaskId", task).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.syncTask.updatedCount").value(1)).andExpect(jsonPath("$.data.syncTask.collectedCount").value(0));
    }
    @Test void newExtractorVersionDoesNotOverwritePreviousExtraction() throws Exception {
        finish(begin(key(), List.of(filing(RECEIPT)))); long previous = versionId();
        jdbc.update("update disclosure_versions set extractor_version = 'previous-extractor' where id = ?", previous);
        finish(begin(key(), List.of(filing(RECEIPT))));
        assertEquals(2, count("disclosure_versions")); assertNotEquals(previous, versionId());
    }
    @Test void fillsMissingOriginalPointerWithoutChangingPublishedBodyOrEvidenceIds() throws Exception {
        finish(begin(key(), List.of(filing(RECEIPT)))); long previous = versionId();
        jdbc.update("update disclosure_versions set original_object_key = null, status = 'PUBLISHED' where id = ?", previous);
        long section = jdbc.queryForObject("select min(id) from disclosure_sections where disclosure_version_id = ?", Long.class, previous);
        finish(begin(key(), List.of(filing(RECEIPT))));
        assertEquals(1, count("disclosure_versions")); assertEquals(previous, versionId());
        assertEquals(section, jdbc.queryForObject("select min(id) from disclosure_sections where disclosure_version_id = ?", Long.class, previous));
        assertEquals("PUBLISHED", jdbc.queryForObject("select status from disclosure_versions where id = ?", String.class, previous));
        String key = jdbc.queryForObject("select original_object_key from disclosure_versions where id = ?", String.class, previous);
        assertNotNull(key); assertTrue(Files.exists(ROOT.resolve(key)));
    }
    @Test void incompleteExistingVersionsDoNotBecomeSuccessfulUnchangedResults() throws Exception {
        finish(begin(key(), List.of(filing(RECEIPT))));
        jdbc.update("delete from disclosure_sections where company_id = ?", company);
        String task = begin(key(), List.of(filing(RECEIPT))); finish(task);
        assertEquals("FAILED", state(task)); assertEquals(1, count("disclosure_versions")); assertEquals(0, count("disclosure_sections"));
        assertEquals("DART_EXISTING_CONTENT_INVALID", jdbc.queryForObject("select error_code from disclosure_sync_items where task_id = ?", String.class, task));
    }
    @Test void permanentItemFailureDoesNotDiscardSuccessfulOtherReports() throws Exception {
        String second = "20260101000002";
        doThrow(new DartFailure("DART_ORIGINAL_NOT_FOUND", false)).when(documents).download(eq(second), any());
        String task = begin(key(), List.of(filing(RECEIPT), filing(second))); finish(task);
        assertEquals("FAILED", state(task)); assertEquals(1, count("disclosure_versions"));
        mvc.perform(get("/v1/admin/disclosures").param("syncTaskId", task).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.syncTask.collectedCount").value(1)).andExpect(jsonPath("$.data.syncTask.failedCount").value(1))
                .andExpect(jsonPath("$.data.syncTask.failures[0].sourceReceiptNo").value(second));
    }
    @Test void temporaryFailureUsesBackoffAndStopsAfterThreeAttempts() throws Exception {
        doThrow(new DartFailure("DART_TEMPORARY_ERROR", true)).when(documents).download(anyString(), any());
        String task = begin(key(), List.of(filing(RECEIPT))); ready(); worker.runOnce();
        worker.runOnce(); verify(documents, times(1)).download(anyString(), any());
        assertEquals("PROCESSING", state(task));
        ready(); worker.runOnce(); ready(); worker.runOnce();
        assertEquals("FAILED", state(task)); verify(documents, times(3)).download(anyString(), any());
        assertEquals(3, jdbc.queryForObject("select attempts from disclosure_sync_items where task_id = ?", Integer.class, task));
    }
    @Test void quotaErrorPausesAllDownloadAndListingCalls() throws Exception {
        String second = "20260101000002";
        doThrow(new DartFailure("DART_RATE_LIMIT", false)).when(documents).download(anyString(), any());
        String task = begin(key(), List.of(filing(RECEIPT), filing(second))); ready(); worker.runOnce(); worker.runOnce();
        verify(documents, times(1)).download(anyString(), any());
        assertTrue(jdbc.queryForObject("select next_call_at from disclosure_sync_control where id = 1", java.time.LocalDateTime.class)
                .isAfter(DisclosureRepository.utc(clock.instant().plusSeconds(3500))));
        assertEquals("PROCESSING", state(task));
    }
    @Test void invalidXmlFailsItemWithoutCreatingFakeContentOrSuccess() throws Exception {
        archives.put(RECEIPT, original(RECEIPT, "<P>닫히지 않는 본문"));
        String task = begin(key(), List.of(filing(RECEIPT))); finish(task);
        assertEquals("FAILED", state(task)); assertEquals(0, count("disclosure_versions")); assertEquals(0, count("disclosure_sections"));
    }
    @Test void storageFailureDoesNotCreateVersionsAndCanBeRetried() throws Exception {
        String task = begin(key(), List.of(filing(RECEIPT)));
        doThrow(new DartFailure("DART_STORAGE_ERROR", true)).doCallRealMethod().when(store).put(anyLong(), anyString(), any());
        ready(); worker.runOnce(); assertEquals(0, count("disclosure_versions")); assertEquals("PROCESSING", state(task));
        ready(); worker.runOnce(); assertEquals(1, count("disclosure_versions")); assertEquals("SUCCEEDED", state(task));
    }
    @Test void databaseFailureRollsBackContentButRetryReusesImmutableOriginal() throws Exception {
        String task = begin(key(), List.of(filing(RECEIPT)));
        doAnswer(call -> { call.callRealMethod(); throw new org.springframework.dao.DataAccessResourceFailureException("test"); })
                .when(originals).complete(any(), any(), any(), any());
        ready(); assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, worker::runOnce);
        assertEquals(0, count("disclosure_versions")); assertEquals(0, count("disclosure_sections"));
        try (var files = Files.walk(ROOT.resolve("disclosures/" + company))) {
            assertEquals(1, files.filter(p -> p.toString().endsWith(".zip")).count());
        }
        reset(originals); expire(task); ready(); worker.runOnce();
        assertEquals("SUCCEEDED", state(task)); assertEquals(1, count("disclosure_versions")); assertEquals(2, count("disclosure_sections"));
    }
    @Test void expiredWorkerCannotCommitSectionsAfterAnotherWorkerTakesOver() throws Exception {
        String task = begin(key(), List.of(filing(RECEIPT))); ready();
        Lease old = tx().execute(s -> repository.claim(clock.instant())); assertNotNull(old);
        expire(task); ready(); Lease current = tx().execute(s -> repository.claim(clock.instant())); assertNotNull(current);
        try (var stage = store.stage()) {
            Files.write(stage.path(), archives.get(RECEIPT)); var parsed = extractor.extract(stage.path(), RECEIPT, CORP, "ANNUAL");
            var saved = store.put(company, RECEIPT, stage.path());
            tx().executeWithoutResult(s -> originals.complete(old, saved, parsed, clock.instant())); assertEquals(0, count("disclosure_versions"));
            tx().executeWithoutResult(s -> originals.complete(current, saved, parsed, clock.instant())); assertEquals(1, count("disclosure_versions"));
        }
    }
    @Test void repeatedCrashesEventuallyFailTheItemWithoutInfiniteReclaims() throws Exception {
        String task = begin(key(), List.of(filing(RECEIPT)));
        for (int i = 0; i < 3; i++) { ready(); assertNotNull(tx().execute(s -> repository.claim(clock.instant()))); expire(task); }
        ready(); worker.runOnce();
        assertEquals("FAILED", state(task)); verify(documents, never()).download(anyString(), any());
        assertEquals(3, jdbc.queryForObject("select attempts from disclosure_sync_items where task_id = ?", Integer.class, task));
    }
    @Test void legacyCompletedRequestsRemainMetadataOnlyAndRequireNewKeyForBackfill() throws Exception {
        String key = key(), task = submit(key);
        jdbc.update("update ai_tasks set request_parameters = json_remove(request_parameters, '$.phase') where id = ?", task);
        listing(List.of(filing(RECEIPT))); ready(); worker.runOnce();
        assertEquals("SUCCEEDED", state(task)); assertEquals(0, count("disclosure_versions"));
        assertEquals(task, submit(key)); verify(documents, never()).download(anyString(), any());
        finish(begin(key(), List.of(filing(RECEIPT)))); assertEquals(1, count("disclosure_versions"));
    }
    @Test void listingRetryPreservesOriginalWorkflowMode() throws Exception {
        String task = submit(key());
        when(dart.list(anyString(), any(), any(), anyInt())).thenThrow(new DartFailure("DART_TEMPORARY_ERROR", true))
                .thenReturn(new Page(1, 1, "list", List.of(filing(RECEIPT))));
        ready(); worker.runOnce(); ready(); worker.runOnce();
        assertEquals("DOCUMENTS", jdbc.queryForObject("select json_value(request_parameters, '$.phase') from ai_tasks where id = ?", String.class, task));
        finish(task); assertEquals(1, count("disclosure_versions"));
    }
    @Test void emptyListingSucceedsWithoutOriginalCalls() throws Exception {
        String task = begin(key(), List.of()); finish(task);
        assertEquals("SUCCEEDED", state(task)); verify(documents, never()).download(anyString(), any());
    }
    @Test void missingMetadataFailsItemInsteadOfLeavingAnUnclaimableQueue() throws Exception {
        String task = begin(key(), List.of(filing(RECEIPT)));
        jdbc.update("delete from disclosures where company_id = ?", company);
        finish(task); assertEquals("FAILED", state(task)); verify(documents, never()).download(anyString(), any());
    }
    @Test void metadataOnlyReplicaDoesNotClaimOriginalWorkflowTasks() throws Exception {
        String task = submit(key());
        var disabled = new com.dadot.server.domain.disclosure.support.OriginalProperties(false, "");
        var replica = new DisclosureRepository(jdbc, mapper, disabled, new com.dadot.server.infrastructure.dart.LocalOriginalStore(disabled), originals);
        ready(); assertNull(tx().execute(status -> replica.claim(clock.instant())));
        listing(List.of(filing(RECEIPT))); worker.runOnce(); finish(task); assertEquals("SUCCEEDED", state(task));
    }
    @Test void itemAvailabilityDoesNotDependOnDatabaseSessionTimeZone() throws Exception {
        String task = submit(key()); ready(); Lease lease = tx().execute(s -> repository.claim(clock.instant())); assertNotNull(lease);
        tx().executeWithoutResult(status -> {
            jdbc.execute("set time_zone = '+09:00'");
            try {
                repository.savePage(lease, new Page(1, 1, "one", List.of(filing(RECEIPT))), clock.instant());
                var available = jdbc.queryForObject("select available_at from disclosure_sync_items where task_id = ?", java.time.LocalDateTime.class, task);
                assertTrue(available.isBefore(DisclosureRepository.utc(clock.instant().plusSeconds(2))));
            } finally { jdbc.execute("set time_zone = '+00:00'"); }
        });
        finish(task); assertEquals("SUCCEEDED", state(task));
    }
    @Test void detailQueryPaginatesBodiesAndRejectsMismatchedVersion() throws Exception {
        finish(begin(key(), List.of(filing(RECEIPT))));
        long disclosure = disclosureId(), version = versionId();
        String path = detail(disclosure, version);
        mvc.perform(get(path).param("size", "1").header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true)).andExpect(jsonPath("$.data.content[0].text").value("사업의 내용"));
        mvc.perform(get(path).param("page", "1").param("size", "1").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.content[0].text").value("원문 R&D와 숫자 100")).andExpect(jsonPath("$.data.hasNext").value(false));
        mvc.perform(get(detail(disclosure + 1, version)).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(get(path).param("page", "2147483647").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.content").isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"size=101", "size=0", "page=-1", "page=1.1", "unknown=1", "size=1&size=2"})
    void validatesDetailQuery(String query) throws Exception {
        mvc.perform(get(detail(1, 1) + "?" + query).header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
    }
    @Test void detailRequiresAdminAndAnActiveSession() throws Exception {
        mvc.perform(get(detail(1, 1))).andExpect(status().isUnauthorized());
        jdbc.update("update users set role = 'USER' where id = ?", user);
        mvc.perform(get(detail(1, 1)).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        jdbc.update("update users set role = 'ADMIN' where id = ?", user);
        jdbc.update("update auth_sessions set revoked_at = utc_timestamp(6) where id = ?", session);
        mvc.perform(get(detail(1, 1)).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    private String begin(String key, List<Filing> filings) throws Exception {
        String task = submit(key); listing(filings); ready(); worker.runOnce(); return task;
    }
    private void listing(List<Filing> filings) {
        when(dart.list(anyString(), any(), any(), anyInt())).thenReturn(new Page(filings.size(), filings.isEmpty() ? 0 : 1, "list", filings));
    }
    private String submit(String key) throws Exception {
        var response = mvc.perform(post("/v1/admin/companies/" + company + "/disclosure-sync-tasks")
                        .header("Authorization", "Bearer " + token).header("Idempotency-Key", key).contentType("application/json")
                        .content("{\"fromDate\":\"2026-01-01\",\"toDate\":\"2026-01-01\",\"reportTypes\":[\"ANNUAL\"]}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).get("data").get("taskId").asText();
    }
    private void finish(String task) {
        for (int i = 0; i < 12 && state(task).equals("PROCESSING"); i++) { ready(); worker.runOnce(); }
        assertNotEquals("PROCESSING", state(task), "Task did not terminate within the fixture bound");
    }
    private Filing filing(String receipt) { return new Filing(receipt, "사업보고서 (2025.12)", ReportType.ANNUAL, day); }
    private byte[] original(String receipt, String body) { return archive(Map.of(receipt + ".xml", xml(body))); }
    private String key() { return UUID.randomUUID().toString(); }
    private String detail(long disclosure, long version) { return "/v1/admin/disclosures/" + disclosure + "/versions/" + version + "/sections"; }
    private String state(String task) { return jdbc.queryForObject("select status from ai_tasks where id = ?", String.class, task); }
    private long versionId() { return Objects.requireNonNull(jdbc.queryForObject("select max(id) from disclosure_versions where company_id = ?", Long.class, company)); }
    private long disclosureId() { return Objects.requireNonNull(jdbc.queryForObject("select id from disclosures where company_id = ?", Long.class, company)); }
    private void expire(String task) { jdbc.update("update ai_tasks set lease_until = ? where id = ?", DisclosureRepository.utc(clock.instant().minusSeconds(1)), task); }
    private void ready() {
        jdbc.update("update disclosure_sync_control set next_call_at = '1970-01-01' where id = 1");
        jdbc.update("update ai_tasks set available_at = '1970-01-01' where user_id = ? and status = 'PROCESSING'", user);
        jdbc.update("update disclosure_sync_items i join ai_tasks t on t.id = i.task_id set i.available_at = '1970-01-01' where t.user_id = ? and i.status = 'PROCESSING'", user);
    }
    private TransactionTemplate tx() { var tx = new TransactionTemplate(manager); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); return tx; }
    private long insert(String sql) {
        var holder = new GeneratedKeyHolder(); jdbc.update(c -> c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS), holder);
        return Objects.requireNonNull(holder.getKey()).longValue();
    }
    private long count(String table) { return Objects.requireNonNull(jdbc.queryForObject("select count(*) from " + table + " where company_id = ?", Long.class, company)); }
    private static Path temporaryRoot() { try { return Files.createTempDirectory("dadot-original-test-"); } catch (java.io.IOException ex) { throw new ExceptionInInitializerError(ex); } }
    private static void deleteTree(Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (var files = Files.walk(path)) { for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
    }
}
