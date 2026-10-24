package com.dadot.server.domain.job;

import com.dadot.server.domain.job.repository.JobRepository;
import com.dadot.server.global.security.AccessTokenIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JobIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired AccessTokenIssuer tokens;
    @Autowired Clock clock;

    @MockitoSpyBean JobRepository repository;
    @MockitoSpyBean PlatformTransactionManager transactionManager;

    private long userId;
    private String sessionId;
    private String accessToken;
    private boolean testDatabase;

    @BeforeEach
    void setup() {
        testDatabase = false;
        assertEquals("dadot_test_db",
                jdbc.queryForObject("select database()", String.class));
        testDatabase = true;
        reset(repository, transactionManager);
        clearDictionary();

        userId = insert("insert into users(nickname) values (?)", "직무조회테스트");
        sessionId = UUID.randomUUID().toString();
        var issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var expires = issued.plusSeconds(3600);

        jdbc.update("""
                insert into auth_sessions(id, user_id, created_at, expires_at)
                values (?, ?, ?, ?)
                """, sessionId, userId,
                LocalDateTime.ofInstant(issued, ZoneOffset.UTC),
                LocalDateTime.ofInstant(expires, ZoneOffset.UTC));
        accessToken = tokens.issue(userId, sessionId, 0, issued, expires);
    }

    @AfterEach
    void cleanup() {
        if (!testDatabase) {
            return;
        }
        reset(repository, transactionManager);
        clearDictionary();
        jdbc.update("delete from auth_sessions where user_id = ?", userId);
        jdbc.update("delete from users where id = ?", userId);
    }

    @Test
    void requiresAValidActiveSession() throws Exception {
        mvc.perform(get("/v1/jobs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/v1/jobs").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());

        jdbc.update("update auth_sessions set revoked_at = utc_timestamp(6) where id = ?",
                sessionId);
        mvc.perform(request()).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsTheCompleteEmptyResponseContract() throws Exception {
        String json = mvc.perform(request())
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.categories").isEmpty())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(7, mapper.readTree(json).get("data").size());
    }

    @Test
    void sortsActiveJobsByNameThenIdAndKeepsAllCategoriesInDisplayOrder()
            throws Exception {
        long later = category("나분류", 2);
        long first = category("가분류", 1);
        long tied = category("빈분류", 2);
        long lastJob = job(first, "나직무", false, true);
        long firstJob = job(later, "가직무", false, true);
        long tiedJob = job(first, "가직무", true, true);
        job(first, "가장먼저", true, false);

        mvc.perform(request())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[0].jobId").value(firstJob))
                .andExpect(jsonPath("$.data.content[0].jobName").value("가직무"))
                .andExpect(jsonPath("$.data.content[0].categoryId").value(later))
                .andExpect(jsonPath("$.data.content[0].categoryName").value("나분류"))
                .andExpect(jsonPath("$.data.content[0].popular").value(false))
                .andExpect(jsonPath("$.data.content[1].jobId").value(tiedJob))
                .andExpect(jsonPath("$.data.content[2].jobId").value(lastJob))
                .andExpect(jsonPath("$.data.categories[0].categoryId").value(first))
                .andExpect(jsonPath("$.data.categories[1].categoryId").value(later))
                .andExpect(jsonPath("$.data.categories[2].categoryId").value(tied));
    }

    @Test
    void combinesFiltersWithoutFilteringTheCategoryList() throws Exception {
        long sales = category("영업·무역", 1);
        long support = category("경영지원", 2);
        long target = job(sales, "해외영업", true, true);
        job(sales, "영업관리", false, true);
        job(sales, "영업비공개", true, false);
        job(support, "영업지원", true, true);
        job(sales, "무역", true, true);

        mvc.perform(request().param("categoryId", String.valueOf(sales))
                        .param("query", "영업").param("popular", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].jobId").value(target))
                .andExpect(jsonPath("$.data.content[0].popular").value(true))
                .andExpect(jsonPath("$.data.categories.length()").value(2));

        mvc.perform(request().param("categoryId", String.valueOf(sales))
                        .param("query", "영업").param("popular", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    void paginatesAndKeepsTotalsBeyondTheLastPage() throws Exception {
        long category = category("분류", 1);
        job(category, "가직무", false, true);
        job(category, "나직무", false, true);
        job(category, "다직무", false, true);

        mvc.perform(request().param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(request().param("size", "2").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].jobName").value("다직무"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        mvc.perform(request().param("size", "100").param("page", "2147483647"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.categories.length()").value(1));
    }

    @ParameterizedTest
    @CsvSource({
            "page,-1", "page,text", "page,2147483648",
            "size,0", "size,-1", "size,101", "size,text",
            "categoryId,0", "categoryId,-1", "categoryId,text",
            "categoryId,9007199254740992", "popular,invalid"
    })
    void rejectsInvalidParameters(String name, String value) throws Exception {
        mvc.perform(request().param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void trimsEdgesPreservesInternalSpacesAndIgnoresEnglishCase()
            throws Exception {
        long category = category("분류", 1);
        long expected = job(category, "AI Research", false, true);
        mvc.perform(request().param("query", "  ai research  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].jobId").value(expected));
        mvc.perform(request().param("query", "airesearch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(request().param("query", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"가", "😀"})
    void validatesFiftyUnicodeCodePointsRatherThanUtf16Units(String character)
            throws Exception {
        long category = category("분류", 1);
        job(category, character.repeat(50), false, true);
        mvc.perform(request().param("query", character.repeat(50)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(request().param("query", character.repeat(51)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @ParameterizedTest
    @CsvSource({"%,100%영업,100X영업", "_,B_B영업,B2B영업", "!,영업!관리,영업관리"})
    void treatsLikeWildcardsAsLiteralText(String query, String matching, String other)
            throws Exception {
        long category = category("분류", 1);
        long target = job(category, matching, false, true);
        job(category, other, false, true);
        mvc.perform(request().param("query", query))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].jobId").value(target));
    }

    @Test
    void returnsEmptyResultsForUnknownCategoryAndSqlText() throws Exception {
        long category = category("분류", 1);
        job(category, "영업", false, true);
        mvc.perform(request().param("categoryId", "9007199254740991"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.categories.length()").value(1));
        mvc.perform(request().param("query", "' OR 1=1 --"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty());
    }

    @Test
    void readsJobsAndCategoriesInOneReadOnlySnapshot() throws Exception {
        doAnswer(invocation -> {
            assertReadTransaction();
            return invocation.callRealMethod();
        }).when(repository).search(anyString(), any(), anyBoolean(), any(Pageable.class));
        doAnswer(invocation -> {
            assertReadTransaction();
            return invocation.callRealMethod();
        }).when(repository).findAllCategories();
        mvc.perform(request()).andExpect(status().isOk());
    }

    @Test
    void returnsUnavailableForEitherDictionaryQueryFailure() throws Exception {
        doThrow(new DataAccessResourceFailureException("test"))
                .when(repository).search(anyString(), any(), anyBoolean(), any(Pageable.class));
        mvc.perform(request())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));

        reset(repository);
        doThrow(new DataAccessResourceFailureException("test"))
                .when(repository).findAllCategories();
        mvc.perform(request()).andExpect(status().isServiceUnavailable());
    }

    @Test
    void returnsUnavailableWhenTheTransactionCannotBegin() throws Exception {
        doThrow(new CannotCreateTransactionException("test"))
                .when(transactionManager).getTransaction(any());
        mvc.perform(request())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void explicitlySeedsTheDictionaryAndPopularJobs() throws Exception {
        seed();
        assertEquals(4L, count("job_categories"));
        assertEquals(24L, count("jobs"));
        mvc.perform(request().param("popular", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(6))
                .andExpect(jsonPath("$.data.categories[0].categoryName").value("기획·전략"))
                .andExpect(jsonPath("$.data.categories[3].categoryName").value("경영지원"));
        mvc.perform(request().param("query", "해외영업"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].categoryName").value("영업·무역"));
    }

    @Test
    void reseedingPreservesIdsManualFlagsOrderAndUnrelatedEntries() {
        seed();
        Map<String, Long> categoryIds = ids("job_categories");
        Map<String, Long> jobIds = ids("jobs");
        jdbc.update("update job_categories set display_order = 99 where name = '영업·무역'");
        jdbc.update("update jobs set is_active = false, is_popular = true where name = '해외영업'");
        long extra = category("별도분류", 100);
        job(extra, "별도직무", false, true);
        seed();
        assertEquals(5L, count("job_categories"));
        assertEquals(25L, count("jobs"));
        categoryIds.forEach((key, id) -> assertEquals(id, ids("job_categories").get(key)));
        jobIds.forEach((key, id) -> assertEquals(id, ids("jobs").get(key)));
        assertEquals(99, jdbc.queryForObject(
                "select display_order from job_categories where name = '영업·무역'", Integer.class));
        assertEquals(1L, jdbc.queryForObject("""
                select count(*) from jobs
                where name = '해외영업' and is_active = false and is_popular = true
                """, Long.class));
    }

    @Test
    void enforcesCategoryUniquenessAndJobReferences() {
        long category = category("분류", 1);
        job(category, "영업", false, true);
        assertThrows(DataIntegrityViolationException.class, () -> category("분류", 2));
        assertThrows(DataIntegrityViolationException.class,
                () -> job(category, "영업", true, true));
        assertThrows(DataIntegrityViolationException.class,
                () -> job(9007199254740991L, "영업", false, true));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("delete from job_categories where id = ?", category));
    }

    private void assertReadTransaction() {
        assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        assertEquals(4, TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
    }

    private MockHttpServletRequestBuilder request() {
        return get("/v1/jobs").header("Authorization", "Bearer " + accessToken);
    }

    private long category(String name, int order) {
        return insert("insert into job_categories(name, display_order) values (?, ?)", name, order);
    }

    private long job(long category, String name, boolean popular, boolean active) {
        return insert("""
                insert into jobs(category_id, name, is_popular, is_active)
                values (?, ?, ?, ?)
                """, category, name, popular, active);
    }

    private long insert(String sql, Object... arguments) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    private void seed() {
        var script = new ResourceDatabasePopulator(new ClassPathResource("seed/jobs.sql"));
        script.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
        script.execute(Objects.requireNonNull(jdbc.getDataSource()));
    }

    private long count(String table) {
        return Objects.requireNonNull(jdbc.queryForObject("select count(*) from " + table, Long.class));
    }

    private Map<String, Long> ids(String table) {
        Map<String, Long> result = new HashMap<>();
        jdbc.query("select * from " + table, rs -> {
            String key = table.equals("jobs")
                    ? rs.getLong("category_id") + ":" + rs.getString("name")
                    : rs.getString("name");
            result.put(key, rs.getLong("id"));
        });
        return result;
    }

    private void clearDictionary() {
        jdbc.update("delete from jobs");
        jdbc.update("delete from job_categories");
    }
}