package com.dadot.server.domain.interview.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Repository
@RequiredArgsConstructor
public class InterviewMaterialImpactRepository {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    // 경험 삭제 서비스가 users 행을 잠근 동일 트랜잭션에서 호출한다.
    public void invalidate(long user, long materialId) {
        var now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        jdbc.update("""
                update ai_contexts x
                join ai_context_materials m on m.context_id = x.id
                join material_versions v on v.id = m.material_version_id
                set x.invalidated_at = coalesce(x.invalidated_at, ?)
                where x.user_id = ?
                  and v.material_id = ?
                  and v.material_type = 'EXPERIENCE'
                """, now, user, materialId);

        jdbc.query("""
                select t.id
                from ai_tasks t
                join ai_contexts x on x.id = t.context_id
                where x.user_id = ?
                  and x.invalidated_at is not null
                  and t.status = 'PROCESSING'
                order by t.id for update
                """, (rs, n) -> rs.getString(1), user);

        jdbc.update("""
                update ai_task_runs r
                join ai_tasks t on t.id = r.task_id
                join ai_contexts x on x.id = t.context_id
                set r.status = 'CANCELLED',
                    r.error_code = 'CONTEXT_UNAVAILABLE',
                    r.finished_at = ?
                where x.user_id = ?
                  and x.invalidated_at is not null
                  and r.status = 'PROCESSING'
                """, now, user);

        jdbc.update("""
                update ai_tasks t
                join ai_contexts x on x.id = t.context_id
                set t.status = 'CANCELLED',
                    t.last_error_code = 'CONTEXT_UNAVAILABLE',
                    t.completed_at = ?,
                    t.lease_owner = null,
                    t.lease_until = null
                where x.user_id = ?
                  and x.invalidated_at is not null
                  and t.status = 'PROCESSING'
                """, now, user);

        jdbc.update("""
                update interview_generation_jobs j
                join ai_tasks t on t.id = j.task_id
                set j.active_user_id = null, j.active_company_id = null
                where j.user_id = ? and t.status = 'CANCELLED'
                """, user);

        jdbc.update("""
                delete l from evaluation_material_links l
                join ai_contexts x on x.id = l.context_id
                join material_versions v on v.id = l.material_version_id
                where x.user_id = ? and v.material_id = ? and v.material_type = 'EXPERIENCE'
                """, user, materialId);

        jdbc.update("""
                delete m
                from ai_context_materials m
                join material_versions v on v.id = m.material_version_id
                where m.user_id = ?
                  and v.material_id = ?
                  and v.material_type = 'EXPERIENCE'
                """, user, materialId);
    }
}