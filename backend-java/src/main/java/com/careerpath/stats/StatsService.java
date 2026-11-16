package com.careerpath.stats;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public StatsService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
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
                SELECT j.id, j.title, r.required_score, r.bonus_score, r.created_at
                  FROM skill_match_result r
                  JOIN job_position j ON j.id = r.job_position_id
                 WHERE r.user_id = ?
                 ORDER BY r.created_at DESC, r.id DESC
                 LIMIT 1
                """, (rs, rowNum) -> new OverviewResponse.LastMatch(
                        rs.getLong("id"),
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

    // ---------------------------------------------------------------- 数据看板（二期模块 9）

    /**
     * 数据看板聚合。
     *
     * <p>模块 9 按 c3.3 裁决**不单开页面**，这些数字直接进概览页的卡片区。四块内容都只做
     * 「已经存在的数据的另一种切法」，不引入新的埋点或统计表——为一张演示看板加事件表，
     * 是在用真实复杂度换一个漂亮图。
     *
     * <p>{@code topGaps} 取「每个岗位最近一次快照」的差距并计频次：同一个技能在三份 JD 里都差，
     * 才是真的该先补的；把历史上所有快照都算进来会让「反复匹配同一岗位」把频次刷高。
     */
    @Transactional(readOnly = true)
    public StatsInsights insights(Long userId) {
        List<StatusCount> applicationStatus = jdbc.query("""
                SELECT status, COUNT(*) AS cnt FROM job_application
                 WHERE user_id = ? GROUP BY status
                 ORDER BY FIELD(status, 'todo', 'applied', 'interview', 'closed')
                """, (rs, i) -> new StatusCount(rs.getString("status"), rs.getInt("cnt")), userId);

        List<StatusCount> interviewResult = jdbc.query("""
                SELECT result, COUNT(*) AS cnt FROM interview_record
                 WHERE user_id = ? GROUP BY result
                 ORDER BY FIELD(result, 'pass', 'pending', 'reject')
                """, (rs, i) -> new StatusCount(rs.getString("result"), rs.getInt("cnt")), userId);

        List<MatchPoint> matchTrend = jdbc.query("""
                SELECT r.created_at, r.required_score,
                       COALESCE(JSON_UNQUOTE(JSON_EXTRACT(r.detail, '$.jobTitle')), j.title) AS job_title
                  FROM skill_match_result r
                  LEFT JOIN job_position j ON j.id = r.job_position_id
                 WHERE r.user_id = ?
                 ORDER BY r.id DESC LIMIT 10
                """, (rs, i) -> new MatchPoint(toInstant(rs, "created_at"), rs.getString("job_title"),
                nullableDouble(rs, "required_score")), userId);

        List<SkillFrequency> topGaps = topGaps(userId);

        int taskTotal = count("SELECT COUNT(*) FROM learning_task WHERE user_id = ?", userId);
        int taskDone = count("SELECT COUNT(*) FROM learning_task WHERE user_id = ? AND status = 'done'", userId);
        // 没有任务时完成率为 null（不适用），不返回 0% —— 「还没开始」和「一个都没完成」不是一回事
        Double taskRate = taskTotal == 0 ? null : Math.round(taskDone * 1000.0 / taskTotal) / 10.0;

        return new StatsInsights(applicationStatus, interviewResult, matchTrend, topGaps,
                new TaskCompletion(taskDone, taskTotal, taskRate));
    }

    /** 每个岗位只取最近一次快照，解析 JSON 里的 gaps 计频次。 */
    private List<SkillFrequency> topGaps(Long userId) {
        List<String> details = jdbc.queryForList("""
                SELECT r.detail
                  FROM skill_match_result r
                  JOIN (SELECT job_position_id, MAX(id) AS mid
                          FROM skill_match_result
                         WHERE user_id = ? AND job_position_id IS NOT NULL
                         GROUP BY job_position_id) latest ON latest.mid = r.id
                 WHERE r.detail IS NOT NULL
                """, String.class, userId);

        Map<String, SkillFrequency> counter = new java.util.LinkedHashMap<>();
        for (String raw : details) {
            try {
                com.fasterxml.jackson.databind.JsonNode root = json.readTree(raw);
                for (com.fasterxml.jackson.databind.JsonNode gap : root.path("gaps")) {
                    String skillId = gap.path("skillId").asText(null);
                    if (skillId == null) {
                        continue;
                    }
                    String name = gap.path("skillName").asText(skillId);
                    SkillFrequency current = counter.get(skillId);
                    counter.put(skillId, new SkillFrequency(skillId, name,
                            current == null ? 1 : current.count() + 1,
                            (current == null ? 0 : current.priority()) + gap.path("priority").asInt(0)));
                }
            } catch (Exception ex) {
                // 单条快照坏了不影响整块看板：跳过它，其余照常统计
                log.warn("解析匹配快照失败，已跳过：{}", ex.getMessage());
            }
        }
        return counter.values().stream()
                .sorted((a, b) -> b.count() != a.count()
                        ? b.count() - a.count()
                        : b.priority() - a.priority())
                .limit(5)
                .toList();
    }

    public record StatusCount(String status, int count) {
    }

    public record MatchPoint(Instant at, String jobTitle, Double requiredScore) {
    }

    public record SkillFrequency(String skillId, String skillName, int count, int priority) {
    }

    public record TaskCompletion(int done, int total, Double rate) {
    }

    public record StatsInsights(List<StatusCount> applicationStatus, List<StatusCount> interviewResult,
                                List<MatchPoint> matchTrend, List<SkillFrequency> topGaps,
                                TaskCompletion taskCompletion) {
    }
}
