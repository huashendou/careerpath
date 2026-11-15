package com.careerpath.job;

import com.careerpath.analysis.AnalysisClient;
import com.careerpath.common.ApiException;
import com.careerpath.common.ErrorCode;
import com.careerpath.common.PageResult;
import com.careerpath.common.Times;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 岗位与技能要求（模块 3）。
 *
 * <p>三条不变量：
 * <ol>
 *   <li>岗位归属只认 {@code user_id}，演示岗位（{@code is_demo=1}，{@code user_id} 为 NULL）只读；</li>
 *   <li>{@code raw_description} 逐字保留，解析结果单独存 {@code job_skill_requirement}，
 *       两者永不互相覆盖——JD 改写了要重新解析，历史报告仍指向快照；</li>
 *   <li>解析接口只返回建议（{@code parse_status} 不动），用户点保存才写库并置 {@code confirmed}。</li>
 * </ol>
 */
@Service
public class JobService {

    /** 与 Python 侧 max_text_length 对齐：两边都拦，避免前端绕过 Java 直连内网服务。 */
    private static final int MAX_TEXT = 20_000;
    private static final int MAX_REQUIREMENTS = 200;

    private final JdbcTemplate jdbc;
    private final AnalysisClient analysis;
    private final ObjectMapper json;

    public JobService(JdbcTemplate jdbc, AnalysisClient analysis, ObjectMapper json) {
        this.jdbc = jdbc;
        this.analysis = analysis;
        this.json = json;
    }

    // ---------------------------------------------------------------- 查询

    @Transactional(readOnly = true)
    public PageResult<JobDtos.Summary> list(Long userId, int page, int size, String keyword) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 200);
        String kw = (keyword == null || keyword.isBlank()) ? null : "%" + keyword.trim() + "%";

        StringBuilder where = new StringBuilder(" WHERE (j.user_id = ? OR j.is_demo = 1)");
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (kw != null) {
            where.append(" AND (j.title LIKE ? OR j.company_name LIKE ?)");
            args.add(kw);
            args.add(kw);
        }

        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM job_position j" + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((long) (p - 1) * s);
        List<JobDtos.Summary> rows = jdbc.query("""
                SELECT j.id, j.title, j.company_name, j.city, j.salary_min, j.salary_max,
                       j.parse_status, j.is_demo, j.created_at,
                       (SELECT COUNT(*) FROM job_skill_requirement r
                         WHERE r.job_position_id = j.id AND r.req_type = 'required') AS required_count,
                       (SELECT COUNT(*) FROM job_skill_requirement r
                         WHERE r.job_position_id = j.id AND r.req_type = 'bonus') AS bonus_count
                  FROM job_position j
                """ + where + " ORDER BY j.id DESC LIMIT ? OFFSET ?", summaryMapper(), pageArgs.toArray());

        return new PageResult<>(rows, total == null ? 0 : total, p, s);
    }

    @Transactional(readOnly = true)
    public JobDtos.Detail get(Long userId, long id) {
        return readDetail(id, userId);
    }

    private JobDtos.Detail readDetail(long id, Long userId) {
        List<JobDtos.Detail> rows = jdbc.query("""
                SELECT id, title, company_name, city, salary_min, salary_max, source, url,
                       raw_description, parse_status, is_demo, created_at, updated_at
                  FROM job_position
                 WHERE id = ? AND (user_id = ? OR is_demo = 1)
                """, detailMapper(), id, userId);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.JOB_NOT_FOUND);
        }
        return rows.get(0);
    }

    // ---------------------------------------------------------------- 写

    @Transactional
    public JobDtos.Detail create(Long userId, JobDtos.Upsert req) {
        String title = requireTitle(req.title());
        int salaryMin = salary(req.salaryMin());
        int salaryMax = salary(req.salaryMax());
        if (req.salaryMin() != null && req.salaryMax() != null && salaryMin > salaryMax) {
            throw ApiException.validation("薪资下限不能大于上限");
        }
        String text = text(req.rawDescription());
        LocalDateTime now = Times.nowUtc();

        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO job_position (user_id, company_name, title, salary_min, salary_max, city,
                                              source, url, raw_description, parse_status, is_demo, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', 0, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, trimToNull(req.companyName()));
            ps.setString(3, title);
            ps.setObject(4, req.salaryMin());
            ps.setObject(5, req.salaryMax());
            ps.setString(6, trimToNull(req.city()));
            ps.setString(7, trimToNull(req.source()));
            ps.setString(8, trimToNull(req.url()));
            ps.setString(9, text);
            ps.setObject(10, now);
            ps.setObject(11, now);
            return ps;
        }, keys);
        long id = keys.getKey() == null ? 0 : keys.getKey().longValue();
        return readDetail(id, userId);
    }

    /**
     * 局部更新：字段为 null 表示「不改」。
     *
     * <p>用 COALESCE 而不是「读出来再整体写回」：并发下后者会丢更新，
     * 也会把 {@code raw_description} 之外的时间字段搅乱。
     */
    @Transactional
    public JobDtos.Detail update(Long userId, long id, JobDtos.Upsert req) {
        requireOwnedJob(userId, id);
        if (req.title() != null) {
            requireTitle(req.title());
        }
        if (req.salaryMin() != null && req.salaryMax() != null && salary(req.salaryMin()) > salary(req.salaryMax())) {
            throw ApiException.validation("薪资下限不能大于上限");
        }
        String text = req.rawDescription() == null ? null : text(req.rawDescription());

        jdbc.update("""
                UPDATE job_position
                   SET title = COALESCE(?, title),
                       company_name = COALESCE(?, company_name),
                       city = COALESCE(?, city),
                       salary_min = COALESCE(?, salary_min),
                       salary_max = COALESCE(?, salary_max),
                       source = COALESCE(?, source),
                       url = COALESCE(?, url),
                       raw_description = COALESCE(?, raw_description),
                       updated_at = ?
                 WHERE id = ? AND user_id = ?
                """,
                trimToNull(req.title()),
                trimToNull(req.companyName()),
                trimToNull(req.city()),
                req.salaryMin(),
                req.salaryMax(),
                trimToNull(req.source()),
                trimToNull(req.url()),
                text,
                Times.nowUtc(),
                id, userId);
        return readDetail(id, userId);
    }

    @Transactional
    public void delete(Long userId, long id) {
        int rows = jdbc.update("DELETE FROM job_position WHERE id = ? AND user_id = ?", id, userId);
        if (rows == 0) {
            throw new ApiException(ErrorCode.JOB_NOT_FOUND);
        }
    }

    // ---------------------------------------------------------------- 解析

    /**
     * 调 Python 解析 JD，**只返回建议**。
     *
     * <p>不落库也不改 {@code parse_status}：解析是「看一眼建议」，写库是用户确认后的动作。
     * 两者混在一起时，用户误点一次解析就会把一份没确认过的要求留在库里。
     */
    @Transactional(readOnly = true)
    public JobDtos.ParseResponse parse(Long userId, long id) {
        JobDtos.Detail job = readDetail(id, userId);
        String text = job.rawDescription();
        if (text == null || text.isBlank()) {
            throw ApiException.validation("该岗位没有 JD 原文，无法解析");
        }
        AnalysisClient.JdParseResult result = analysis.parseJd(text, job.title());
        if (result == null || result.requirements() == null) {
            throw new ApiException(ErrorCode.ANALYSIS_FAILED);
        }

        List<JobDtos.Suggestion> items = result.requirements().stream()
                .map(r -> new JobDtos.Suggestion(r.skillId(), r.skillName(), r.dim(), r.reqType(),
                        r.weight(), r.targetLevel(), r.matchedAlias(), r.sourceStart(), r.sourceEnd(),
                        r.sourceText(), r.evidenceHint(), r.confidence()))
                .toList();

        return new JobDtos.ParseResponse(id, items, new JobDtos.Conditions(
                result.conditions() == null ? null : result.conditions().education(),
                result.conditions() == null ? null : result.conditions().experience()),
                result.matchedCount(), result.requiredCount(), result.bonusCount(),
                result.algorithmVersion(), result.dictionaryVersion(), result.disclaimer());
    }

    // ---------------------------------------------------------------- 技能要求

    @Transactional(readOnly = true)
    public List<JobDtos.RequirementItem> requirements(Long userId, long id) {
        readDetail(id, userId);
        return jdbc.query("""
                SELECT r.id, r.skill_id, s.name, s.category_id, r.req_type, r.weight,
                       r.target_level, r.any_of, r.evidence_hint, r.source_span
                  FROM job_skill_requirement r
                  JOIN skill s ON s.id = r.skill_id
                 WHERE r.job_position_id = ?
                 ORDER BY FIELD(r.req_type, 'required', 'bonus'), r.weight DESC, s.name
                """, requirementMapper(), id);
    }

    /**
     * 全量覆盖该岗位的技能要求。
     *
     * <p>为什么是覆盖而不是合并：解析建议、手工增删、改权重都在同一张表上，
     * 合并语义会让「删掉一条误识别的要求」变成不可能——接口层没有「删除」这个动作。
     * 覆盖前先校验全部条目，任何一条不合法就整批拒绝，不会写出半份要求。
     */
    @Transactional
    public List<JobDtos.RequirementItem> saveRequirements(Long userId, long id, List<JobDtos.RequirementInput> items) {
        requireOwnedJob(userId, id);
        List<JobDtos.RequirementInput> list = items == null ? List.of() : items;
        if (list.size() > MAX_REQUIREMENTS) {
            throw ApiException.validation("单个岗位的技能要求不能超过 " + MAX_REQUIREMENTS + " 条");
        }

        Set<String> seen = new HashSet<>();
        for (JobDtos.RequirementInput item : list) {
            String skillId = item.skillId() == null ? "" : item.skillId().trim();
            if (skillId.isEmpty()) {
                throw ApiException.validation("skillId 不能为空");
            }
            if (!seen.add(skillId)) {
                throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "同一岗位的技能不能重复：" + skillId);
            }
            if (!"required".equals(item.reqType()) && !"bonus".equals(item.reqType())) {
                throw ApiException.validation("reqType 只能是 required 或 bonus：" + skillId);
            }
            int weight = item.weight() == null ? 1 : item.weight();
            if (weight < 1 || weight > 99) {
                throw ApiException.validation("weight 需在 1–99：" + skillId);
            }
            int target = item.targetLevel() == null ? 3 : item.targetLevel();
            if (target < 1 || target > 5) {
                throw ApiException.validation("targetLevel 需在 1–5：" + skillId);
            }
        }

        Set<String> existing = new HashSet<>(jdbc.queryForList(
                "SELECT id FROM skill WHERE id IN (" + placeholders(list.size()) + ")",
                String.class, list.stream().map(i -> i.skillId().trim()).toArray()));
        for (JobDtos.RequirementInput item : list) {
            if (!existing.contains(item.skillId().trim())) {
                throw new ApiException(ErrorCode.UNKNOWN_SKILL, "技能词典中没有：" + item.skillId());
            }
            for (String member : item.anyOf() == null ? List.<String>of() : item.anyOf()) {
                if (!existing.contains(member) && !skillExists(member)) {
                    throw new ApiException(ErrorCode.UNKNOWN_SKILL, "anyOf 中的技能不存在：" + member);
                }
            }
        }

        jdbc.update("DELETE FROM job_skill_requirement WHERE job_position_id = ?", id);
        LocalDateTime now = Times.nowUtc();
        for (JobDtos.RequirementInput item : list) {
            List<String> anyOf = item.anyOf() == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(item.anyOf()));
            jdbc.update("""
                    INSERT INTO job_skill_requirement
                        (job_position_id, skill_id, req_type, weight, target_level, any_of, evidence_hint, source_span)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    id, item.skillId().trim(), item.reqType(),
                    item.weight() == null ? 1 : item.weight(),
                    item.targetLevel() == null ? 3 : item.targetLevel(),
                    anyOf.isEmpty() ? null : toJson(anyOf),
                    truncate(item.evidenceHint(), 255), truncate(item.sourceSpan(), 64));
        }
        jdbc.update("UPDATE job_position SET parse_status = 'confirmed', updated_at = ? WHERE id = ?", now, id);
        return requirements(userId, id);
    }

    // ---------------------------------------------------------------- 内部

    private void requireOwnedJob(Long userId, long id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM job_position WHERE id = ? AND user_id = ?", Integer.class, id, userId);
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.JOB_NOT_FOUND);
        }
    }

    private boolean skillExists(String skillId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM skill WHERE id = ?", Integer.class, skillId);
        return count != null && count > 0;
    }

    private String requireTitle(String title) {
        String value = trimToNull(title);
        if (value == null) {
            throw ApiException.validation("岗位名称不能为空");
        }
        if (value.length() > 64) {
            throw ApiException.validation("岗位名称不能超过 64 字");
        }
        return value;
    }

    private int salary(Integer value) {
        if (value == null) {
            return 0;
        }
        if (value < 0 || value > 1_000_000) {
            throw ApiException.validation("薪资数值不合法");
        }
        return value;
    }

    private String text(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.length() > MAX_TEXT) {
            throw ApiException.validation("JD 原文不能超过 " + MAX_TEXT + " 字符");
        }
        return raw;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String placeholders(int n) {
        return n == 0 ? "NULL" : String.join(",", java.util.Collections.nCopies(n, "?"));
    }

    private String toJson(List<String> list) {
        try {
            return json.writeValueAsString(list);
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "技能数组序列化失败");
        }
    }

    private List<String> parseAnyOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private RowMapper<JobDtos.Summary> summaryMapper() {
        return (rs, i) -> new JobDtos.Summary(
                rs.getLong("id"), rs.getString("title"), rs.getString("company_name"), rs.getString("city"),
                (Integer) rs.getObject("salary_min"), (Integer) rs.getObject("salary_max"),
                rs.getString("parse_status"), rs.getBoolean("is_demo"),
                rs.getInt("required_count"), rs.getInt("bonus_count"), Times.toInstant(rs, "created_at"));
    }

    private RowMapper<JobDtos.Detail> detailMapper() {
        return (rs, i) -> new JobDtos.Detail(
                rs.getLong("id"), rs.getString("title"), rs.getString("company_name"), rs.getString("city"),
                (Integer) rs.getObject("salary_min"), (Integer) rs.getObject("salary_max"),
                rs.getString("source"), rs.getString("url"), rs.getString("raw_description"),
                rs.getString("parse_status"), rs.getBoolean("is_demo"),
                Times.toInstant(rs, "created_at"), Times.toInstant(rs, "updated_at"));
    }

    private RowMapper<JobDtos.RequirementItem> requirementMapper() {
        return (rs, i) -> new JobDtos.RequirementItem(
                rs.getLong("id"), rs.getString("skill_id"), rs.getString("name"), rs.getString("category_id"),
                rs.getString("req_type"), rs.getInt("weight"), rs.getInt("target_level"),
                parseAnyOf(rs.getString("any_of")), rs.getString("evidence_hint"), rs.getString("source_span"));
    }

    /** 供其它模块复用：某岗位的必备要求（技能 + 权重 + 目标等级 + anyOf）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> rawRequirements(long jobId) {
        return jdbc.queryForList("""
                SELECT skill_id AS skillId, req_type AS reqType, weight, target_level AS targetLevel, any_of AS anyOf
                  FROM job_skill_requirement WHERE job_position_id = ?
                """, jobId);
    }
}
