package com.dadot.server.domain.job.repository;

import com.dadot.server.domain.job.dto.response.JobSearchResponse.CategoryItem;
import com.dadot.server.domain.job.dto.response.JobSearchResponse.JobItem;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Repository
@RequiredArgsConstructor
public class JobRepository {

    private final JdbcTemplate jdbc;

    public Page<JobItem> search(
            String query,
            Long categoryId,
            boolean popular,
            Pageable pageable
    ) {
        StringBuilder filter = new StringBuilder("""
                from jobs j
                join job_categories c on c.id = j.category_id
                where j.is_active = true
                  and j.name collate utf8mb4_unicode_ci like ? escape '!'
                """);

        List<Object> arguments = new ArrayList<>();
        arguments.add("%" + escapeLike(query) + "%");

        if (categoryId != null) {
            filter.append(" and j.category_id = ?");
            arguments.add(categoryId);
        }

        if (popular) {
            filter.append(" and j.is_popular = true");
        }

        long total = Objects.requireNonNull(jdbc.queryForObject(
                "select count(*) " + filter,
                Long.class,
                arguments.toArray()
        ));

        if (pageable.getOffset() >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        arguments.add(pageable.getPageSize());
        arguments.add(pageable.getOffset());

        List<JobItem> content = jdbc.query(
                """
                select j.id, j.name, j.category_id,
                       c.name as category_name, j.is_popular
                """ + filter + " order by j.name asc, j.id asc limit ? offset ?",
                (rs, row) -> new JobItem(
                        rs.getLong("id"), rs.getString("name"),
                        rs.getLong("category_id"), rs.getString("category_name"),
                        rs.getBoolean("is_popular")
                ),
                arguments.toArray()
        );

        return new PageImpl<>(content, pageable, total);
    }

    public List<CategoryItem> findAllCategories() {
        return jdbc.query("""
                select id, name from job_categories
                order by display_order asc, id asc
                """,
                (rs, row) -> new CategoryItem(
                        rs.getLong("id"), rs.getString("name")
                )
        );
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }
}