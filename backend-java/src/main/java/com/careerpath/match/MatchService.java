package com.careerpath.match;

import com.careerpath.analysis.AnalysisClient;
import com.careerpath.common.ApiException;
import com.careerpath.common.ErrorCode;
import com.careerpath.common.Times;
import com.careerpath.job.JobDtos;
import com.careerpath.job.JobService;
import com.careerpath.portfolio.PortfolioService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 匹配与报告存档（模块 6）。
 *
 * <p>算分在 Python，**落库在 Java**：分析服务不碰业务表，好处是它随时可以换实现，
 * 而「这份分数是什么时候、按哪个算法版本算出来的」始终有一个 Java 侧可审计的写入点。
 *
 * <p>快照必须完整：岗位事后被改名、权重被调、技能被删，都不影响历史报告——
 * 报告读的是 {@code detail} 里的 JSON，不重新算分。
 */
@Service
public class MatchService {

    /** 与 skills.json 的 meta.disclaimer 前缀一致，仅用于展示徽标。 */
    private static final String DATA_VERSION = "示例数据 v0.1";

    private final JdbcTemplate jdbc;
    private final JobService jobs;
    private final PortfolioService portfolio;
    private final AnalysisClient analysis;
    private final ObjectMapper json;

    public MatchService(JdbcTemplate jdbc, JobService jobs, PortfolioService portfolio,
                        AnalysisClient analysis, ObjectMapper json) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.portfolio = portfolio;
        this.analysis = analysis;
        this.json = json;
    }

    /** 一次匹配的完整结果：对外响应体 + 快照 id（`/api/reports` 要带上，`/api/match` 不带）。 */
    public record Outcome(Map<String, Object> body, long reportId, Instant createdAt) {
    }

    /**
     * 算一次匹配并落库，返回契约 3.5 的完整响应（不含快照 id/createdAt）。
     *
     * <p>每次调用都会新增一条快照，不做「覆盖上一次」：分数是过程数据，
     * 覆盖会把「我改了什么导致分数变了」变成无法回溯的问题。
     */
    @Transactional
    public Outcome match(Long userId, long jobId) {
        JobDtos.Detail job = jobs.get(userId, jobId);
        List<Map<String, Object>> requirements = jobs.rawRequirements(jobId);
        Map<String, Integer> levels = portfolio.levels(userId);
        Map<String, Integer> evidence = portfolio.evidenceStrength(userId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jobId", String.valueOf(jobId));
        payload.put("jobName", job.title());
        payload.put("userId", userId);
        payload.put("requirements", requirements.stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("skillId", row.get("skillId"));
            item.put("reqType", row.get("reqType"));
            item.put("weight", row.get("weight"));
            item.put("targetLevel", row.get("targetLevel"));
            item.put("anyOf", parseAnyOf(row.get("anyOf")));
            return item;
        }).toList());
        payload.put("userSkills", levels.entrySet().stream()
                .map(e -> Map.of("skillId", e.getKey(), "level", e.getValue())).toList());
        payload.put("evidence", evidence.entrySet().stream()
                .map(e -> Map.of("skillId", e.getKey(), "strength", e.getValue())).toList());
        payload.put("dataVersion", DATA_VERSION);
        payload.put("disclaimer", "");

        Map<String, Object> result = analysis.match(payload);
        Snapshot snapshot = persist(userId, jobId, job.title(), result);

        Map<String, Object> response = new LinkedHashMap<>(result);
        response.put("jobPositionId", jobId);
        response.put("jobTitle", job.title());
        return new Outcome(response, snapshot.id(), snapshot.createdAt());
    }

    private record Snapshot(long id, Instant createdAt) {
    }

    private Snapshot persist(Long userId, long jobId, String jobTitle, Map<String, Object> result) {
        Map<String, Object> detail = new LinkedHashMap<>(result);
        detail.put("jobTitle", jobTitle);

        LocalDateTime now = Times.nowUtc();
        Object meta = result.get("meta");
        String algorithm = meta instanceof Map<?, ?> m && m.get("scoringVersion") != null
                ? String.valueOf(m.get("scoringVersion"))
                : "v2.0-required-only";

        BigDecimal required = decimal(result.get("requiredScore"), 4);
        BigDecimal bonus = decimal(result.get("bonusScore"), 4);
        BigDecimal coverage = decimal(result.get("evidenceCoverage"), 2);

        org.springframework.jdbc.support.GeneratedKeyHolder keys =
                new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO skill_match_result
                        (user_id, job_position_id, required_score, bonus_score, evidence_coverage,
                         algorithm_version, detail, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setLong(2, jobId);
            ps.setObject(3, required);
            ps.setObject(4, bonus);
            ps.setObject(5, coverage);
            ps.setString(6, algorithm);
            ps.setString(7, toJson(detail));
            ps.setObject(8, now);
            return ps;
        }, keys);

        long resultId = keys.getKey() == null ? 0 : keys.getKey().longValue();
        jdbc.update("""
                INSERT INTO skill_gap_report (match_result_id, gap_items, top_priorities, explanation)
                VALUES (?, ?, ?, ?)
                """, resultId, toJson(result.getOrDefault("gaps", List.of())),
                toJson(result.getOrDefault("topActions", List.of())),
                toJson(Map.of(
                        "counts", result.getOrDefault("counts", Map.of()),
                        "meta", result.getOrDefault("meta", Map.of()),
                        "bonusItems", result.getOrDefault("bonusItems", List.of()))));

        return new Snapshot(resultId, now.toInstant(java.time.ZoneOffset.UTC));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> reports(Long userId, Long jobId) {
        List<Object> args = new ArrayList<>();
        args.add(userId);
        String filter = "";
        if (jobId != null) {
            filter = " AND r.job_position_id = ?";
            args.add(jobId);
        }
        return jdbc.query("""
                SELECT r.id, r.required_score, r.bonus_score, r.algorithm_version, r.created_at,
                       COALESCE(JSON_UNQUOTE(JSON_EXTRACT(r.detail, '$.jobTitle')), j.title) AS job_title
                  FROM skill_match_result r
                  LEFT JOIN job_position j ON j.id = r.job_position_id
                 WHERE r.user_id = ?""" + filter + """
                 ORDER BY r.id DESC
                 LIMIT 50
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("jobTitle", rs.getString("job_title"));
            row.put("requiredScore", rs.getBigDecimal("required_score"));
            row.put("bonusScore", rs.getBigDecimal("bonus_score"));
            row.put("scoringVersion", rs.getString("algorithm_version"));
            row.put("createdAt", Times.toInstant(rs, "created_at"));
            return row;
        }, args.toArray());
    }

    /** 报告全文：优先读快照，岗位已删除也能打开。 */
    @Transactional(readOnly = true)
    public Map<String, Object> report(Long userId, long id) {
        List<Map<String, Object>> rows = jdbc.query("""
                SELECT id, detail, created_at FROM skill_match_result WHERE id = ? AND user_id = ?
                """, (rs, i) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("detail", rs.getString("detail"));
            row.put("createdAt", Times.toInstant(rs, "created_at"));
            return row;
        }, id, userId);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "报告不存在");
        }
        Map<String, Object> snapshot = readJson(rows.get(0).get("detail"));
        Map<String, Object> response = new LinkedHashMap<>(snapshot);
        response.put("id", id);
        response.put("createdAt", rows.get(0).get("createdAt"));
        return response;
    }

    /** 某岗位最近一次快照的 detail；任务模块用它拿到 gaps，避免重复算分。 */
    @Transactional(readOnly = true)
    public Map<String, Object> latestSnapshot(Long userId, long jobId) {
        List<String> rows = jdbc.queryForList("""
                SELECT detail FROM skill_match_result
                 WHERE user_id = ? AND job_position_id = ?
                 ORDER BY id DESC LIMIT 1
                """, String.class, userId, jobId);
        return rows.isEmpty() || rows.get(0) == null ? null : readJson(rows.get(0));
    }

    private BigDecimal decimal(Object value, int scale) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd.setScale(scale, RoundingMode.HALF_UP);
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue()).setScale(scale, RoundingMode.HALF_UP);
        }
        return null;
    }

    private List<String> parseAnyOf(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        try {
            return json.readValue(String.valueOf(raw), new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "报告序列化失败");
        }
    }

    private Map<String, Object> readJson(Object raw) {
        try {
            return json.readValue(String.valueOf(raw), new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "报告内容损坏");
        }
    }
}
