package com.dadot.server.domain.experience.repository;

import com.dadot.server.domain.experience.model.Experience;
import com.dadot.server.domain.experience.model.Experience.*;
import com.dadot.server.global.exception.BusinessException;
import com.dadot.server.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

@Repository
@RequiredArgsConstructor
public class ExperienceRepository {

    private final JdbcTemplate jdbc;

    public long count(long user) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                select count(*) from personal_materials
                where user_id = ? and type = 'EXPERIENCE'
                """, Long.class, user));
    }

    public List<Summary> list(long user, Paging paging) {
        return jdbc.query("""
                select m.id, v.title, left(v.content_text, 100),
                    v.version_no, m.created_at, m.updated_at
                from personal_materials m
                join material_versions v on v.material_id = m.id
                    and v.version_no = m.current_version_no
                where m.user_id = ? and m.type = 'EXPERIENCE'
                order by m.updated_at desc, m.id desc
                limit ? offset ?
                """, (rs, n) -> new Summary(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getLong(4), instant(rs, 5), instant(rs, 6)),
                user, paging.size(), paging.offset());
    }

    public Detail current(long user, long id, boolean lock) {
        return jdbc.query("""
                select m.id, v.title, v.content_text, v.version_no,
                    m.created_at, m.updated_at
                from personal_materials m
                join material_versions v on v.material_id = m.id
                    and v.version_no = m.current_version_no
                where m.user_id = ? and m.type = 'EXPERIENCE' and m.id = ?
                """ + (lock ? " for update" : ""),
                        (rs, n) -> detail(rs), user, id).stream().findFirst()
                .orElseThrow(Experience::missing);
    }

    // 생성 재시도는 현재 버전이 아닌 최초 응답의 불변 버전을 복구한다.
    public Detail snapshot(long user, long id, long version) {
        return jdbc.query("""
                select m.id, v.title, v.content_text, v.version_no,
                    m.created_at, v.created_at
                from personal_materials m
                join material_versions v on v.material_id = m.id and v.version_no = ?
                where m.user_id = ? and m.type = 'EXPERIENCE' and m.id = ?
                """, (rs, n) -> detail(rs), version, user, id).stream().findFirst()
                .orElseThrow(Experience::missing);
    }

    // 모든 쓰기는 users → auth_sessions 잠금 안에서 실행한다.
    public long create(long user, Create request, Instant now) {
        var holder = new GeneratedKeyHolder();
        changed(jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into personal_materials(user_id,type,created_at,updated_at)
                    values (?,'EXPERIENCE',?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, user);
            statement.setObject(2, utc(now));
            statement.setObject(3, utc(now));
            return statement;
        }, holder));
        long id = Objects.requireNonNull(holder.getKey()).longValue();
        if (id > 9_007_199_254_740_991L) unavailable();
        append(user, id, 1, request.title(), request.content(), now);
        changed(jdbc.update("""
                update personal_materials set current_version_no = 1
                where user_id = ? and type = 'EXPERIENCE' and id = ?
                  and current_version_no is null
                """, user, id));
        return id;
    }

    public void update(long user, Detail previous, Patch request, Instant now) {
        if (previous.version() >= Integer.MAX_VALUE) unavailable();
        long next = previous.version() + 1;
        append(user, previous.experienceId(), next,
                request.title() == null ? previous.title() : request.title(),
                request.content() == null ? previous.content() : request.content(), now);
        changed(jdbc.update("""
                update personal_materials set current_version_no = ?, updated_at = ?
                where user_id = ? and type = 'EXPERIENCE' and id = ?
                    and current_version_no = ?
                """, next, utc(now), user, previous.experienceId(), previous.version()));
    }

    private void append(long user, long id, long version,
                        String title, String content, Instant now) {
        changed(jdbc.update("""
                insert into material_versions(
                    material_id,user_id,material_type,version_no,title,content_text,created_at)
                values (?,?,'EXPERIENCE',?,?,?,?)
                """, id, user, version, title, content, utc(now)));
    }

    public void delete(long user, long id) {
        changed(jdbc.update("""
                update personal_materials set current_version_no = null
                where user_id = ? and type = 'EXPERIENCE' and id = ?
                """, user, id));
        jdbc.update("""
                delete from material_versions
                where user_id = ? and material_type = 'EXPERIENCE' and material_id = ?
                """, user, id);
        changed(jdbc.update("""
                delete from personal_materials
                where user_id = ? and type = 'EXPERIENCE' and id = ?
                """, user, id));
    }

    public void advancePersonalization(long user, Instant now) {
        if (jdbc.update("""
                update users set personalization_version = personalization_version + 1,
                    updated_at = ?
                where id = ? and personalization_version < 9223372036854775807
                """, utc(now), user) != 1) unavailable();
    }

    private static Detail detail(ResultSet rs) throws SQLException {
        return new Detail(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getLong(4), instant(rs, 5), instant(rs, 6));
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }

    private static LocalDateTime utc(Instant now) {
        return LocalDateTime.ofInstant(now, ZoneOffset.UTC);
    }

    private static void changed(int count) {
        if (count != 1) throw new IllegalStateException("Experience write lost its lock");
    }

    private static void unavailable() {
        throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
    }
}