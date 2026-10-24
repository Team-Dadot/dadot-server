package com.dadot.server.domain.company;

import com.dadot.server.domain.company.repository.CompanyRepository;
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
import org.springframework.jdbc.core.ConnectionCallback;
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

@SpringBootTest(properties = {
        "app.company-logo-base-url=https://cdn.example.invalid/assets"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CompanyIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired AccessTokenIssuer tokens;
    @Autowired Clock clock;

    @MockitoSpyBean CompanyRepository repository;
    @MockitoSpyBean PlatformTransactionManager transactionManager;

    private long userId;
    private String sessionId;
    private String accessToken;

    @BeforeEach
    void setup() {
        assertEquals("dadot_test_db",
                jdbc.queryForObject("select database()", String.class));

        reset(repository, transactionManager);
        jdbc.update("delete from companies");

        userId = insert(
                "insert into users(nickname) values (?)",
                "기업조회테스트"
        );

        sessionId = UUID.randomUUID().toString();

        var issued = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var expires = issued.plusSeconds(3600);

        jdbc.update("""
                insert into auth_sessions(id, user_id, created_at, expires_at)
                values (?, ?, ?, ?)
                """,
                sessionId,
                userId,
                LocalDateTime.ofInstant(issued, ZoneOffset.UTC),
                LocalDateTime.ofInstant(expires, ZoneOffset.UTC));

        accessToken = tokens.issue(
                userId,
                sessionId,
                0,
                issued,
                expires
        );
    }

    @AfterEach
    void cleanup() {
        reset(repository, transactionManager);

        jdbc.update("delete from companies");
        jdbc.update("delete from auth_sessions where user_id = ?", userId);
        jdbc.update("delete from users where id = ?", userId);
    }

    @Test
    void requiresBearerAuthentication() throws Exception {
        mvc.perform(get("/v1/companies"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code")
                        .value("UNAUTHENTICATED"));

        mvc.perform(get("/v1/companies")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsRevokedSession() throws Exception {
        jdbc.update("""
                update auth_sessions
                set revoked_at = utc_timestamp(6)
                where id = ?
                """, sessionId);

        mvc.perform(request())
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsTheDefaultEmptyPageWithEveryResponseField() throws Exception {
        mvc.perform(request())
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void hidesPrivateCompaniesAndSortsByNameThenId() throws Exception {
        long second = company("나기업", true, null, null);
        long first = company("가기업", true, null, null);
        long tied = company("가기업", true, null, null);

        company("가장먼저", false, null, null);

        mvc.perform(request())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.content[0].companyId")
                        .value(first))
                .andExpect(jsonPath("$.data.content[1].companyId")
                        .value(tied))
                .andExpect(jsonPath("$.data.content[2].companyId")
                        .value(second));
    }

    @Test
    void paginatesFilteredResults() throws Exception {
        company("삼성가", true, null, null);
        company("삼성나", true, null, null);
        company("삼성다", true, null, null);
        company("삼성비공개", false, null, null);
        company("다른기업", true, null, null);

        mvc.perform(request()
                        .param("query", "삼성")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true));

        mvc.perform(request()
                        .param("query", "삼성")
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].companyName")
                        .value("삼성다"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void acceptsTheMaximumPageSizeAndLargePageWithoutOffsetOverflow()
            throws Exception {
        company("가기업", true, null, null);

        mvc.perform(request().param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));

        mvc.perform(request()
                        .param("page", "2147483647")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @ParameterizedTest
    @CsvSource({
            "page,-1",
            "page,text",
            "page,2147483648",
            "size,0",
            "size,-1",
            "size,101",
            "size,text"
    })
    void rejectsInvalidPaging(String parameter, String value) throws Exception {
        mvc.perform(request().param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    void trimsTheQueryAndMatchesEnglishWithoutCaseSensitivity()
            throws Exception {
        company("삼성전자", true, null, null);
        company("LG CNS", true, null, null);

        mvc.perform(request().param("query", "  삼성  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].companyName")
                        .value("삼성전자"));

        mvc.perform(request().param("query", "lg cns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].companyName")
                        .value("LG CNS"));

        mvc.perform(request().param("query", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    void enforcesTheNormalizedQueryLength() throws Exception {
        mvc.perform(request().param("query", "가".repeat(100)))
                .andExpect(status().isOk());

        mvc.perform(request().param("query", "가".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @ParameterizedTest
    @CsvSource({
            "100%,100%기업,1000기업",
            "A_B,A_B기업,ACB기업",
            "wow!,wow!기업,wow기업"
    })
    void treatsLikeWildcardsAsLiteralCharacters(
            String query,
            String matching,
            String other
    ) throws Exception {
        company(matching, true, null, null);
        company(other, true, null, null);

        mvc.perform(request().param("query", query))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].companyName")
                        .value(matching));
    }

    @Test
    void doesNotInterpretSqlFromTheSearchQuery() throws Exception {
        company("일반기업", true, null, null);

        mvc.perform(request().param("query", "%' OR 1=1 --"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        assertEquals(1, count());
    }

    @Test
    void preservesNullIndustryAndLogoKeysInTheResponse() throws Exception {
        company("가기업", true, null, null);

        String json = mvc.perform(request())
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var item = mapper.readTree(json)
                .get("data")
                .get("content")
                .get(0);

        assertEquals(4, item.size());

        assertTrue(item.has("industry"));
        assertTrue(item.get("industry").isNull());

        assertTrue(item.has("logoUrl"));
        assertTrue(item.get("logoUrl").isNull());
    }

    @Test
    void resolvesTheStoredLogoKeyToAnHttpsUrl() throws Exception {
        company(
                "가기업",
                true,
                "테스트업종",
                "company/logo.png"
        );

        mvc.perform(request())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].industry")
                        .value("테스트업종"))
                .andExpect(jsonPath("$.data.content[0].logoUrl")
                        .value(
                                "https://cdn.example.invalid/assets/company/logo.png"
                        ));
    }

    @Test
    void readsCountAndContentInsideOneReadOnlyTransaction() throws Exception {
        company("가기업", true, null, null);

        doAnswer(invocation -> {
            assertTrue(
                    TransactionSynchronizationManager
                            .isActualTransactionActive()
            );

            assertTrue(
                    TransactionSynchronizationManager
                            .isCurrentTransactionReadOnly()
            );

            assertEquals(
                    4,
                    TransactionSynchronizationManager
                            .getCurrentTransactionIsolationLevel()
            );

            return invocation.callRealMethod();
        }).when(repository).search(
                anyString(),
                any(Pageable.class)
        );

        mvc.perform(request())
                .andExpect(status().isOk());
    }

    @Test
    void returnsUnavailableInsteadOfAnEmptyPageOnDatabaseFailure()
            throws Exception {
        doThrow(
                new DataAccessResourceFailureException("test database failure")
        ).when(repository).search(
                anyString(),
                any(Pageable.class)
        );

        mvc.perform(request())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code")
                        .value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void returnsUnavailableWhenTheReadTransactionCannotBegin()
            throws Exception {
        doThrow(
                new CannotCreateTransactionException("test transaction failure")
        ).when(transactionManager).getTransaction(any());

        mvc.perform(request())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code")
                        .value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void explicitlySeedsOneHundredUniquePrivateCompanies() throws Exception {
        assertEquals(0, count());

        seed();

        assertEquals(100, count());

        assertEquals(
                100L,
                jdbc.queryForObject(
                        "select count(distinct dart_corp_code) from companies",
                        Long.class
                )
        );

        assertEquals(
                0L,
                jdbc.queryForObject("""
                        select count(*) from companies
                        where is_service_company = true
                           or logo_key is not null
                           or industry_code is null
                           or industry is null
                        """, Long.class)
        );

        assertEquals(
                "통신 및 방송 장비 제조업",
                jdbc.queryForObject("""
                        select industry from companies
                        where dart_corp_code = '00126380'
                        """, String.class)
        );

        mvc.perform(request())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void reseedingKeepsExistingIdsAndManualChanges() {
        seed();

        Map<String, Long> originalIds = ids();

        jdbc.update("""
                update companies
                set logo_key = 'company/manual.png',
                    is_service_company = true,
                    name = '수동표시명',
                    industry = '수동업종'
                where dart_corp_code = '00126380'
                """);

        var timestamps = jdbc.queryForMap("""
                select created_at, updated_at from companies
                where dart_corp_code = '00126380'
                """);

        seed();

        assertEquals(100, count());
        assertEquals(originalIds, ids());

        var company = jdbc.queryForMap("""
                select name,
                       industry,
                       logo_key,
                       is_service_company,
                       created_at,
                       updated_at
                from companies
                where dart_corp_code = '00126380'
                """);

        assertEquals("수동표시명", company.get("name"));
        assertEquals("수동업종", company.get("industry"));
        assertEquals("company/manual.png", company.get("logo_key"));
        assertEquals(
                timestamps.get("created_at"),
                company.get("created_at")
        );
        assertEquals(
                timestamps.get("updated_at"),
                company.get("updated_at")
        );

        assertEquals(
                1L,
                jdbc.queryForObject("""
                        select count(*) from companies
                        where dart_corp_code = '00126380'
                          and is_service_company = true
                        """, Long.class)
        );
    }

    @Test
    void seedingKeepsUnrelatedCompanies() {
        long existing = company(
                "별도등록기업",
                true,
                null,
                null
        );

        seed();

        assertEquals(101, count());

        assertEquals(
                "별도등록기업",
                jdbc.queryForObject(
                        "select name from companies where id = ?",
                        String.class,
                        existing
                )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "1", "123456", "26A", "26 ", "２６４"
    })
    void rejectsInvalidIndustryCodesAtTheDatabase(String code) {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        insert into companies(
                            dart_corp_code,
                            name,
                            industry_code
                        )
                        values ('99999999', '제약테스트', ?)
                        """, code)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "12", "264", "2612", "30121"
    })
    void preservesTheOriginalIndustryCodeWithoutPadding(String code) {
        jdbc.update("""
                insert into companies(
                    dart_corp_code,
                    name,
                    industry_code
                )
                values ('99999999', '제약테스트', ?)
                """, code);

        assertEquals(
                code,
                jdbc.queryForObject(
                        "select industry_code from companies",
                        String.class
                )
        );
    }

    private MockHttpServletRequestBuilder request() {
        return get("/v1/companies")
                .header("Authorization", "Bearer " + accessToken);
    }

    private long company(
            String name,
            boolean visible,
            String industry,
            String logoKey
    ) {
        String corpCode = jdbc.queryForObject("""
                select lpad(
                    coalesce(
                        max(cast(dart_corp_code as unsigned)),
                        90000000
                    ) + 1,
                    8,
                    '0'
                )
                from companies
                """, String.class);

        return insert("""
                insert into companies(
                    dart_corp_code,
                    name,
                    industry,
                    logo_key,
                    is_service_company
                )
                values (?, ?, ?, ?, ?)
                """,
                corpCode,
                name,
                industry,
                logoKey,
                visible);
    }

    private long insert(String sql, Object... arguments) {
        var key = new GeneratedKeyHolder();

        jdbc.update(connection -> {
            var statement = connection.prepareStatement(
                    sql,
                    new String[]{"id"}
            );

            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }

            return statement;
        }, key);

        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private int count() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "select count(*) from companies",
                Integer.class
        ));
    }

    private Map<String, Long> ids() {
        Map<String, Long> result = new HashMap<>();

        jdbc.query(
                "select dart_corp_code, id from companies",
                (rs, row) -> {
                    result.put(
                            rs.getString("dart_corp_code"),
                            rs.getLong("id")
                    );

                    return rs.getLong("id");
                }
        );

        return result;
    }

    private void seed() {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            new ResourceDatabasePopulator(
                    new ClassPathResource("seed/companies.sql")
            ).populate(connection);

            return null;
        });
    }
}