package com.dadot.server.domain.company.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;

@Repository
@RequiredArgsConstructor
public class CompanyRepository {

    private static final String FILTER = """
            from companies
            where is_service_company = true
              and name collate utf8mb4_unicode_ci like ? escape '!'
            """;

    private final JdbcTemplate jdbc;

    public Page<CompanyRow> search(String query, Pageable pageable) {
        String pattern = "%" + escapeLike(query) + "%";

        long total = Objects.requireNonNull(jdbc.queryForObject(
                "select count(*) " + FILTER,
                Long.class,
                pattern
        ));

        if (pageable.getOffset() >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        List<CompanyRow> companies = jdbc.query(
                """
                select id, name, industry, logo_key
                """ + FILTER + """
                order by name asc, id asc
                limit ? offset ?
                """,
                (rs, row) -> new CompanyRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("industry"),
                        rs.getString("logo_key")
                ),
                pattern,
                pageable.getPageSize(),
                pageable.getOffset()
        );

        return new PageImpl<>(companies, pageable, total);
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }

    public record CompanyRow(
            long id,
            String name,
            String industry,
            String logoKey
    ) {
    }
}