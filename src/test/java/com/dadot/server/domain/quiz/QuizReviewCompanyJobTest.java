package com.dadot.server.domain.quiz;

import com.dadot.server.domain.company.support.CompanyLogoUrlResolver;
import com.dadot.server.domain.quiz.model.QuizReview.Paging;
import com.dadot.server.domain.quiz.repository.QuizPlayRepository;
import com.dadot.server.domain.quiz.repository.QuizReviewRepository;
import com.dadot.server.domain.quiz.repository.QuizReviewRepository.CompanyRow;
import com.dadot.server.domain.quiz.service.QuizReviewService;
import com.dadot.server.global.security.SessionAuthenticationToken;
import com.dadot.server.global.security.UserAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuizReviewCompanyJobTest {

    private static final long USER = 11L;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ResultSet row = mock(ResultSet.class);
    private final UserAccess access = mock(UserAccess.class);
    private final SessionAuthenticationToken actor =
            mock(SessionAuthenticationToken.class);
    private final CompanyLogoUrlResolver logos =
            mock(CompanyLogoUrlResolver.class);

    private QuizReviewService service;

    @BeforeEach
    void setup() throws Exception {
        var manager = mock(PlatformTransactionManager.class);

        when(manager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());

        when(access.require(actor, false)).thenReturn(USER);

        when(jdbc.queryForObject(
                anyString(), eq(Long.class), eq(USER)))
                .thenReturn(1L);

        when(row.getLong(1)).thenReturn(31L);
        when(row.getString(2)).thenReturn("준비했던 기업");
        when(row.getString(3)).thenReturn("제조업");
        when(row.getString(4)).thenReturn("logo-key");

        when(logos.resolve("logo-key"))
                .thenReturn("https://example.invalid/logo.png");

        when(jdbc.query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<CompanyRow>>any(),
                eq(USER),
                eq(20),
                eq(0L)
        )).thenAnswer(call -> {
            RowMapper<CompanyRow> mapper = call.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        });

        service = new QuizReviewService(
                new QuizReviewRepository(jdbc),
                mock(QuizPlayRepository.class),
                access,
                logos,
                manager
        );
    }

    @ParameterizedTest
    @CsvSource({
            "true,3,마케팅,false",
            "false,3,마케팅,false",
            "false,,직접 입력한 직무,true"
    })
    void exposesSavedJobForActiveAndExcludedCompanies(
            boolean active,
            Long jobId,
            String jobName,
            boolean custom
    ) throws Exception {
        when(row.getBoolean(5)).thenReturn(active);
        when(row.getObject(6, Long.class)).thenReturn(jobId);
        when(row.getString(7)).thenReturn(jobName);

        var result = service.companies(actor, new Paging(0, 20));
        var item = result.content().getFirst();

        assertEquals(31L, item.company().companyId());
        assertEquals("준비했던 기업", item.company().companyName());
        assertEquals(active, item.isActive());

        assertNotNull(item.job());
        assertEquals(jobId, item.job().jobId());
        assertEquals(jobName, item.job().jobName());
        assertEquals(custom, item.job().isCustom());

        assertEquals(0L, item.quizQuestionCount());
        assertEquals(0L, item.interviewQuestionCount());
        assertEquals(1L, result.totalElements());
        assertEquals(1L, result.totalPages());
        assertFalse(result.hasNext());

        var json = JsonMapper.builder()
                .build()
                .valueToTree(result)
                .get("content")
                .get(0);

        assertEquals(
                jobName,
                json.get("job").get("jobName").asString()
        );
        assertEquals(
                custom,
                json.get("job").get("isCustom").asBoolean()
        );

        if (jobId == null) {
            assertTrue(json.get("job").get("jobId").isNull());
        } else {
            assertEquals(
                    jobId.longValue(),
                    json.get("job").get("jobId").asLong()
            );
        }

        verify(access).require(actor, false);

        verify(jdbc).query(
                contains("left join jobs j on j.id = uc.job_id"),
                org.mockito.ArgumentMatchers.<RowMapper<CompanyRow>>any(),
                eq(USER),
                eq(20),
                eq(0L)
        );

        verify(jdbc, never()).update(
                anyString(),
                org.mockito.ArgumentMatchers.<Object[]>any()
        );
    }

    @Test
    void missingSavedJobDoesNotRemoveCompanyOrCreateCustomJob()
            throws Exception {
        when(row.getBoolean(5)).thenReturn(false);

        var result = service.companies(actor, new Paging(0, 20));

        assertEquals(1, result.content().size());

        var item = result.content().getFirst();

        assertNull(item.job());
        assertFalse(item.isActive());
        assertEquals(
                "https://example.invalid/logo.png",
                item.company().logoUrl()
        );
    }

    @Test
    void outOfRangePageKeepsTotalAndDoesNotQueryRows() {
        var result = service.companies(actor, new Paging(1, 20));

        assertTrue(result.content().isEmpty());
        assertEquals(1L, result.totalElements());

        verify(jdbc, never()).query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<CompanyRow>>any(),
                anyLong(),
                anyInt(),
                anyLong()
        );
    }
}