package com.careerpath.stats;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 概览统计。
 *
 * <p>用 JdbcTemplate 而不是 MyBatis-Plus 实体：五条查询全是聚合，没有一条要回实体；
 * 为它们各建一个 Entity/Mapper 会让「表结构」和「统计口径」两件事绑死。
 *
 * <p>每条 SQL 都带 user_id = ?：统计是按用户隔离的，漏一个条件就是把别人的数据算进你的概览。
 */
@Service
public class StatsService {

    private final JdbcTemplate jdbc;

    public StatsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public OverviewResponse overview(Long userId) {
        // 「档案完善」不能只判「有没有这一行」：PUT 是整体替换，用户只填手机号也会落一行，
        // 那时前端会显示「已完善 / 学校专业已记录」，与事实不符。按学校与专业都非空来判，
        // 与概览页的提示文案（学校专业已记录）对齐。
        Boolean profileCompletedRow = jdbc.queryForObject("""
                SELECT COUNT(*) > 0 FROM user_profile
                 WHERE user_id = ?
                   AND school IS NOT NULL AND TRIM(school) <> ''
                   AND major  IS NOT NULL AND TRIM(major)  <> ''
                """, Boolean.class, userId);
        boolean profileCompleted = Boolean.TRUE.equals(profileCompletedRow);

        int jobCount = count("SELECT COUNT(*) FROM job_position WHERE user_id = ?", userId);
        int taskOpenCount = count(
                "SELECT COUNT(*) FROM learning_task WHERE user_id = ? AND status IN ('todo','doing')", userId);

        // 已自评但没有任何证据关联的技能数——「会但拿不出材料」，这是要显式提醒的一类缺口
        int evidenceMissingCount = count("""
                SELECT COUNT(*) FROM user_skill us
                 WHERE us.user_id = ?
                   AND us.level > 0
                   AND NOT EXISTS (
                       SELECT 1 FROM evidence_skill_relation esr
                         JOIN skill_evidence se ON se.id = esr.evidence_id
                        WHERE esr.skill_id = us.skill_id AND se.user_id = us.user_id)
                """, userId);

        return new OverviewResponse(
                profileCompleted,
                jobCount,
                taskOpenCount,
                evidenceMissingCount,
                lastMatch(userId),
                Instant.now());
    }

    private int count(String sql, Long userId) {
        Integer value = jdbc.queryForObject(sql, Integer.class, userId);
        return value == null ? 0 : value;
    }

    /** 取最近一次匹配。没有记录返回 null，前端据此显示「还没有匹配记录」而不是 0 分。 */
    private OverviewResponse.LastMatch lastMatch(Long userId) {
        List<OverviewResponse.LastMatch> rows = jdbc.query("""
                SELECT j.title, r.required_score, r.bonus_score, r.created_at
                  FROM skill_match_result r
                  JOIN job_position j ON j.id = r.job_position_id
                 WHERE r.user_id = ?
                 ORDER BY r.created_at DESC, r.id DESC
                 LIMIT 1
                """, (rs, rowNum) -> new OverviewResponse.LastMatch(
                        rs.getString("title"),
                        nullableDouble(rs, "required_score"),
                        nullableDouble(rs, "bonus_score"),
                        toInstant(rs, "created_at")), userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 分数在库里允许为 NULL（无必备/无加分要求），不能读成 0。 */
    private Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private Instant toInstant(ResultSet rs, String column) throws SQLException {
        LocalDateTime time = rs.getObject(column, LocalDateTime.class);
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
