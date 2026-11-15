package com.careerpath.portfolio;

import com.careerpath.common.ApiException;
import com.careerpath.common.ErrorCode;
import com.careerpath.common.Times;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 技能自评、证据链与项目（模块 5）。
 *
 * <p>三条边界，评审时请重点看：
 * <ol>
 *   <li><b>自评不因证据而改分</b>：证据只进 {@code evidenceCount} 与 {@code coverage}，
 *       {@code level} 永远是用户自己填的那个数；</li>
 *   <li>自评是<b>批量 upsert</b>，未提交的技能不变——全量删除会让「只改一项」误删其余；</li>
 *   <li>证据与项目的技能关联是<b>整组覆盖</b>，因为它们本身就是「这一份材料覆盖了哪些技能」。</li>
 * </ol>
 */
@Service
public class PortfolioService {

    private static final Set<String> EVIDENCE_TYPES =
            Set.of("course", "project", "internship", "certificate", "repo", "other");
    private static final Set<String> VERIFY_STATUS =
            Set.of("unverified", "self_verified", "pending");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PortfolioService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ---------------------------------------------------------------- 技能自评

    @Transactional(readOnly = true)
    public List<PortfolioDtos.UserSkillView> userSkills(Long userId) {
        return jdbc.query("""
                SELECT us.skill_id, s.name, s.category_id, us.level, us.learned_at, us.last_used_at, us.source,
                       (SELECT COUNT(DISTINCT esr.evidence_id)
                          FROM evidence_skill_relation esr
                          JOIN skill_evidence se ON se.id = esr.evidence_id
                         WHERE esr.skill_id = us.skill_id AND se.user_id = us.user_id) AS evidence_count
                  FROM user_skill us
                  JOIN skill s ON s.id = us.skill_id
                 WHERE us.user_id = ?
                 ORDER BY s.category_id, s.sort_order, s.id
                """, (rs, i) -> new PortfolioDtos.UserSkillView(
                rs.getString("skill_id"), rs.getString("name"), rs.getString("category_id"),
                rs.getInt("level"),
                rs.getObject("learned_at", LocalDate.class),
                rs.getObject("last_used_at", LocalDate.class),
                rs.getString("source"), rs.getInt("evidence_count")), userId);
    }

    /**
     * 批量 upsert。
     *
     * <p>等级 clamp 到 [0,5] 而不是拒绝：前端滑块只可能给出这个区间，越界意味着数据来源是脚本或
     * 手工构造的请求，clamp 后行为可预期，报错反而让「一键导入简历」这种场景寸步难行。
     */
    @Transactional
    public List<PortfolioDtos.UserSkillView> saveUserSkills(Long userId, List<PortfolioDtos.UserSkillInput> items) {
        List<PortfolioDtos.UserSkillInput> list = items == null ? List.of() : items;
        Set<String> seen = new HashSet<>();
        for (PortfolioDtos.UserSkillInput item : list) {
            String skillId = item.skillId() == null ? "" : item.skillId().trim();
            if (skillId.isEmpty()) {
                throw ApiException.validation("skillId 不能为空");
            }
            if (!seen.add(skillId)) {
                throw ApiException.validation("同一请求内技能重复：" + skillId);
            }
        }
        requireSkills(seen);

        for (PortfolioDtos.UserSkillInput item : list) {
            int level = item.level() == null ? 0 : Math.max(0, Math.min(5, item.level()));
            jdbc.update("""
                    INSERT INTO user_skill (user_id, skill_id, level, learned_at, last_used_at, source)
                    VALUES (?, ?, ?, ?, ?, 'self')
                    ON DUPLICATE KEY UPDATE level = VALUES(level),
                                            learned_at = VALUES(learned_at),
                                            last_used_at = VALUES(last_used_at),
                                            source = 'self'
                    """, userId, item.skillId().trim(), level, item.learnedAt(), item.lastUsedAt());
        }
        return userSkills(userId);
    }

    @Transactional
    public void deleteUserSkill(Long userId, String skillId) {
        jdbc.update("DELETE FROM user_skill WHERE user_id = ? AND skill_id = ?", userId, skillId);
    }

    // ---------------------------------------------------------------- 证据

    @Transactional(readOnly = true)
    public List<PortfolioDtos.EvidenceView> evidence(Long userId, String skillId) {
        String filter = (skillId == null || skillId.isBlank()) ? null : skillId.trim();
        List<Object> args = new ArrayList<>();
        args.add(userId);
        StringBuilder sql = new StringBuilder("""
                SELECT se.id, se.evidence_type, se.title, se.url, se.description, se.completeness,
                       se.verify_status, se.submitted_at
                  FROM skill_evidence se
                 WHERE se.user_id = ?
                """);
        if (filter != null) {
            sql.append("""
                       AND EXISTS (SELECT 1 FROM evidence_skill_relation esr
                                    WHERE esr.evidence_id = se.id AND esr.skill_id = ?)
                    """);
            args.add(filter);
        }
        sql.append(" ORDER BY se.id DESC");

        List<PortfolioDtos.EvidenceView> base = jdbc.query(sql.toString(), (rs, i) ->
                new PortfolioDtos.EvidenceView(
                        rs.getLong("id"), rs.getString("evidence_type"), rs.getString("title"),
                        rs.getString("url"), rs.getString("description"), rs.getInt("completeness"),
                        rs.getString("verify_status"), Times.toInstant(rs, "submitted_at"), List.of()),
                args.toArray());

        Map<Long, List<PortfolioDtos.EvidenceSkill>> skills = evidenceSkills(
                base.stream().map(PortfolioDtos.EvidenceView::id).toList());
        return base.stream()
                .map(v -> new PortfolioDtos.EvidenceView(v.id(), v.evidenceType(), v.title(), v.url(),
                        v.description(), v.completeness(), v.verifyStatus(), v.submittedAt(),
                        skills.getOrDefault(v.id(), List.of())))
                .toList();
    }

    private Map<Long, List<PortfolioDtos.EvidenceSkill>> evidenceSkills(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<PortfolioDtos.EvidenceSkill>> result = new LinkedHashMap<>();
        jdbc.query("""
                SELECT esr.evidence_id, esr.skill_id, s.name, esr.relevance, esr.contribution_note
                  FROM evidence_skill_relation esr
                  JOIN skill s ON s.id = esr.skill_id
                 WHERE esr.evidence_id IN (""" + placeholders(ids.size()) + """
                )
                 ORDER BY esr.relevance DESC, s.name
                """, rs -> {
            long evidenceId = rs.getLong("evidence_id");
            result.computeIfAbsent(evidenceId, k -> new ArrayList<>()).add(new PortfolioDtos.EvidenceSkill(
                    rs.getString("skill_id"), rs.getString("name"), rs.getInt("relevance"),
                    rs.getString("contribution_note")));
        }, ids.toArray());
        return result;
    }

    @Transactional
    public PortfolioDtos.EvidenceView createEvidence(Long userId, PortfolioDtos.EvidenceInput input) {
        String type = requireEnum(input.evidenceType(), EVIDENCE_TYPES, "evidenceType");
        String title = requireText(input.title(), 128, "证据标题不能为空", "证据标题不能超过 128 字");
        String status = input.verifyStatus() == null || input.verifyStatus().isBlank()
                ? "unverified"
                : requireEnum(input.verifyStatus(), VERIFY_STATUS, "verifyStatus");
        int completeness = clampPercent(input.completeness());

        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO skill_evidence
                        (user_id, evidence_type, title, url, description, completeness, verify_status, submitted_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, type);
            ps.setString(3, title);
            ps.setString(4, trimToNull(input.url()));
            ps.setString(5, trimToNull(input.description()));
            ps.setInt(6, completeness);
            ps.setString(7, status);
            ps.setObject(8, Times.nowUtc());
            return ps;
        }, keys);
        long id = keys.getKey() == null ? 0 : keys.getKey().longValue();

        if (input.skills() != null && !input.skills().isEmpty()) {
            replaceEvidenceSkills(userId, id, input.skills());
        }
        return evidence(userId, null).stream().filter(v -> v.id() == id).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "证据写入后读回失败"));
    }

    @Transactional
    public PortfolioDtos.EvidenceView updateEvidence(Long userId, long id, PortfolioDtos.EvidenceInput input) {
        requireEvidence(userId, id);
        String type = input.evidenceType() == null ? null
                : requireEnum(input.evidenceType(), EVIDENCE_TYPES, "evidenceType");
        String status = input.verifyStatus() == null || input.verifyStatus().isBlank() ? null
                : requireEnum(input.verifyStatus(), VERIFY_STATUS, "verifyStatus");
        String title = input.title() == null ? null : requireText(input.title(), 128, "证据标题不能为空", "证据标题不能超过 128 字");

        jdbc.update("""
                UPDATE skill_evidence
                   SET evidence_type = COALESCE(?, evidence_type),
                       title = COALESCE(?, title),
                       url = COALESCE(?, url),
                       description = COALESCE(?, description),
                       completeness = COALESCE(?, completeness),
                       verify_status = COALESCE(?, verify_status)
                 WHERE id = ? AND user_id = ?
                """,
                type, title, trimToNull(input.url()), trimToNull(input.description()),
                input.completeness() == null ? null : clampPercent(input.completeness()),
                status, id, userId);

        if (input.skills() != null) {
            replaceEvidenceSkills(userId, id, input.skills());
        }
        return evidence(userId, null).stream().filter(v -> v.id() == id).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    @Transactional
    public void deleteEvidence(Long userId, long id) {
        int rows = jdbc.update("DELETE FROM skill_evidence WHERE id = ? AND user_id = ?", id, userId);
        if (rows == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "证据不存在");
        }
    }

    /** 整组覆盖该证据的技能关联——「这份材料证明了哪些技能」是一次性判断，不做增量合并。 */
    @Transactional
    public List<PortfolioDtos.EvidenceSkill> saveEvidenceSkills(Long userId, long evidenceId,
                                                                List<PortfolioDtos.EvidenceSkillInput> items) {
        requireEvidence(userId, evidenceId);
        replaceEvidenceSkills(userId, evidenceId, items == null ? List.of() : items);
        return evidence(userId, null).stream().filter(v -> v.id() == evidenceId)
                .findFirst().map(PortfolioDtos.EvidenceView::skills).orElse(List.of());
    }

    private void replaceEvidenceSkills(Long userId, long evidenceId, List<PortfolioDtos.EvidenceSkillInput> items) {
        Set<String> ids = new LinkedHashSet<>();
        for (PortfolioDtos.EvidenceSkillInput item : items) {
            if (item.skillId() == null || item.skillId().isBlank()) {
                throw ApiException.validation("skillId 不能为空");
            }
            ids.add(item.skillId().trim());
        }
        requireSkills(ids);

        jdbc.update("DELETE FROM evidence_skill_relation WHERE evidence_id = ?", evidenceId);
        for (PortfolioDtos.EvidenceSkillInput item : items) {
            jdbc.update("""
                    INSERT INTO evidence_skill_relation (evidence_id, skill_id, relevance, contribution_note)
                    VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE relevance = VALUES(relevance),
                                            contribution_note = VALUES(contribution_note)
                    """, evidenceId, item.skillId().trim(), clampPercent(item.relevance()),
                    truncate(item.contributionNote(), 255));
        }
    }

    /**
     * 证据覆盖面。
     *
     * <p>分母是「当前用户所有已确认岗位的必备技能去重数」——没有岗位时为 0，此时
     * {@code coverage} 返回 {@code null}（不适用），不是 0%（那会被读成「一份证据都没有」）。
     */
    @Transactional(readOnly = true)
    public PortfolioDtos.CoverageView coverage(Long userId) {
        Integer requiredTotal = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT r.skill_id)
                  FROM job_skill_requirement r
                  JOIN job_position j ON j.id = r.job_position_id
                 WHERE j.user_id = ? AND r.req_type = 'required'
                """, Integer.class, userId);
        int total = requiredTotal == null ? 0 : requiredTotal;

        Integer covered = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT r.skill_id)
                  FROM job_skill_requirement r
                  JOIN job_position j ON j.id = r.job_position_id
                 WHERE j.user_id = ? AND r.req_type = 'required'
                   AND EXISTS (SELECT 1 FROM evidence_skill_relation esr
                                 JOIN skill_evidence se ON se.id = esr.evidence_id
                                WHERE esr.skill_id = r.skill_id AND se.user_id = ?)
                """, Integer.class, userId, userId);
        int coveredCount = covered == null ? 0 : covered;

        List<PortfolioDtos.MissingEvidence> missing = jdbc.query("""
                SELECT DISTINCT r.skill_id, s.name, us.level
                  FROM job_skill_requirement r
                  JOIN job_position j ON j.id = r.job_position_id
                  JOIN skill s ON s.id = r.skill_id
                  JOIN user_skill us ON us.skill_id = r.skill_id AND us.user_id = ?
                 WHERE j.user_id = ? AND r.req_type = 'required' AND us.level > 0
                   AND NOT EXISTS (SELECT 1 FROM evidence_skill_relation esr
                                     JOIN skill_evidence se ON se.id = esr.evidence_id
                                    WHERE esr.skill_id = r.skill_id AND se.user_id = ?)
                 ORDER BY us.level DESC, s.name
                """, (rs, i) -> new PortfolioDtos.MissingEvidence(
                rs.getString("skill_id"), rs.getString("name"), rs.getInt("level")), userId, userId, userId);

        Double value = total == 0 ? null : Math.round(coveredCount * 1000.0 / total) / 10.0;
        return new PortfolioDtos.CoverageView(value, total, coveredCount, missing);
    }

    // ---------------------------------------------------------------- 项目

    @Transactional(readOnly = true)
    public List<PortfolioDtos.ProjectView> projects(Long userId) {
        List<PortfolioDtos.ProjectView> base = jdbc.query("""
                SELECT id, name, role, tech_stack, description, repo_url, start_date, end_date
                  FROM user_project WHERE user_id = ? ORDER BY id DESC
                """, (rs, i) -> new PortfolioDtos.ProjectView(
                rs.getLong("id"), rs.getString("name"), rs.getString("role"),
                parseStringList(rs.getString("tech_stack")), rs.getString("description"),
                rs.getString("repo_url"),
                rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class),
                List.of()), userId);

        if (base.isEmpty()) {
            return base;
        }
        Map<Long, List<PortfolioDtos.ProjectSkill>> skills = new LinkedHashMap<>();
        jdbc.query("""
                SELECT psr.project_id, psr.skill_id, s.name, psr.contribution_note
                  FROM project_skill_relation psr
                  JOIN skill s ON s.id = psr.skill_id
                 WHERE psr.project_id IN (""" + placeholders(base.size()) + """
                )
                """, rs -> {
            skills.computeIfAbsent(rs.getLong("project_id"), k -> new ArrayList<>())
                    .add(new PortfolioDtos.ProjectSkill(rs.getString("skill_id"),
                            rs.getString("name"), rs.getString("contribution_note")));
        }, base.stream().map(PortfolioDtos.ProjectView::id).toArray());

        return base.stream().map(p -> new PortfolioDtos.ProjectView(p.id(), p.name(), p.role(),
                p.techStack(), p.description(), p.repoUrl(), p.startDate(), p.endDate(),
                skills.getOrDefault(p.id(), List.of()))).toList();
    }

    @Transactional
    public PortfolioDtos.ProjectView createProject(Long userId, PortfolioDtos.ProjectInput input) {
        String name = requireText(input.name(), 128, "项目名称不能为空", "项目名称不能超过 128 字");
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO user_project (user_id, name, role, tech_stack, description, repo_url,
                                              start_date, end_date)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, name);
            ps.setString(3, trimToNull(input.role()));
            ps.setString(4, toJson(input.techStack() == null ? List.of() : input.techStack()));
            ps.setString(5, trimToNull(input.description()));
            ps.setString(6, trimToNull(input.repoUrl()));
            ps.setObject(7, input.startDate());
            ps.setObject(8, input.endDate());
            return ps;
        }, keys);
        long id = keys.getKey() == null ? 0 : keys.getKey().longValue();
        return projects(userId).stream().filter(p -> p.id() == id).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.INTERNAL_ERROR, "项目写入后读回失败"));
    }

    @Transactional
    public PortfolioDtos.ProjectView updateProject(Long userId, long id, PortfolioDtos.ProjectInput input) {
        requireProject(userId, id);
        String name = input.name() == null ? null : requireText(input.name(), 128, "项目名称不能为空", "项目名称不能超过 128 字");
        jdbc.update("""
                UPDATE user_project
                   SET name = COALESCE(?, name),
                       role = COALESCE(?, role),
                       tech_stack = COALESCE(?, tech_stack),
                       description = COALESCE(?, description),
                       repo_url = COALESCE(?, repo_url),
                       start_date = COALESCE(?, start_date),
                       end_date = COALESCE(?, end_date)
                 WHERE id = ? AND user_id = ?
                """, name, trimToNull(input.role()),
                input.techStack() == null ? null : toJson(input.techStack()),
                trimToNull(input.description()), trimToNull(input.repoUrl()),
                input.startDate(), input.endDate(), id, userId);
        return projects(userId).stream().filter(p -> p.id() == id).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    @Transactional
    public void deleteProject(Long userId, long id) {
        int rows = jdbc.update("DELETE FROM user_project WHERE id = ? AND user_id = ?", id, userId);
        if (rows == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "项目不存在");
        }
    }

    @Transactional
    public List<PortfolioDtos.ProjectSkill> saveProjectSkills(Long userId, long projectId,
                                                              List<PortfolioDtos.ProjectSkillInput> items) {
        requireProject(userId, projectId);
        List<PortfolioDtos.ProjectSkillInput> list = items == null ? List.of() : items;
        Set<String> ids = new LinkedHashSet<>();
        for (PortfolioDtos.ProjectSkillInput item : list) {
            if (item.skillId() == null || item.skillId().isBlank()) {
                throw ApiException.validation("skillId 不能为空");
            }
            ids.add(item.skillId().trim());
        }
        requireSkills(ids);

        jdbc.update("DELETE FROM project_skill_relation WHERE project_id = ?", projectId);
        for (PortfolioDtos.ProjectSkillInput item : list) {
            jdbc.update("""
                    INSERT INTO project_skill_relation (project_id, skill_id, contribution_note)
                    VALUES (?, ?, ?)
                    ON DUPLICATE KEY UPDATE contribution_note = VALUES(contribution_note)
                    """, projectId, item.skillId().trim(), truncate(item.contributionNote(), 255));
        }
        return projects(userId).stream().filter(p -> p.id() == projectId)
                .findFirst().map(PortfolioDtos.ProjectView::skills).orElse(List.of());
    }

    // ---------------------------------------------------------------- 对匹配模块的只读支持

    /** 技能 → 自评等级。没自评过的技能不出现在这里，匹配侧按 0 处理。 */
    @Transactional(readOnly = true)
    public Map<String, Integer> levels(Long userId) {
        Map<String, Integer> result = new HashMap<>();
        jdbc.query("SELECT skill_id, level FROM user_skill WHERE user_id = ?",
                rs -> {
                    result.put(rs.getString("skill_id"), rs.getInt("level"));
                }, userId);
        return result;
    }

    /** 技能 → 证据强度（同一技能多份证据取最高 relevance），只用于 coverage 与 hasEvidence。 */
    @Transactional(readOnly = true)
    public Map<String, Integer> evidenceStrength(Long userId) {
        Map<String, Integer> result = new HashMap<>();
        jdbc.query("""
                SELECT esr.skill_id, MAX(esr.relevance) AS strength
                  FROM evidence_skill_relation esr
                  JOIN skill_evidence se ON se.id = esr.evidence_id
                 WHERE se.user_id = ?
                 GROUP BY esr.skill_id
                """, rs -> {
            result.put(rs.getString("skill_id"), rs.getInt("strength"));
        }, userId);
        return result;
    }

    // ---------------------------------------------------------------- 内部

    private void requireEvidence(Long userId, long evidenceId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM skill_evidence WHERE id = ? AND user_id = ?", Integer.class, evidenceId, userId);
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "证据不存在");
        }
    }

    private void requireProject(Long userId, long projectId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_project WHERE id = ? AND user_id = ?", Integer.class, projectId, userId);
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "项目不存在");
        }
    }

    private void requireSkills(Set<String> skillIds) {
        if (skillIds.isEmpty()) {
            return;
        }
        Set<String> found = new HashSet<>(jdbc.queryForList(
                "SELECT id FROM skill WHERE id IN (" + placeholders(skillIds.size()) + ")",
                String.class, skillIds.toArray()));
        for (String id : skillIds) {
            if (!found.contains(id)) {
                throw new ApiException(ErrorCode.UNKNOWN_SKILL, "技能词典中没有：" + id);
            }
        }
    }

    private String requireEnum(String value, Set<String> allowed, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!allowed.contains(normalized)) {
            throw ApiException.validation(field + " 取值不合法：" + value);
        }
        return normalized;
    }

    private String requireText(String value, int max, String emptyMessage, String tooLongMessage) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw ApiException.validation(emptyMessage);
        }
        if (trimmed.length() > max) {
            throw ApiException.validation(tooLongMessage);
        }
        return trimmed;
    }

    private int clampPercent(Integer value) {
        if (value == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, value));
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
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "数组序列化失败");
        }
    }

    private List<String> parseStringList(String raw) {
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
}
