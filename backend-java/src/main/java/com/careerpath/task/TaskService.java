package com.careerpath.task;

import com.careerpath.common.ApiException;
import com.careerpath.common.ErrorCode;
import com.careerpath.common.Times;
import com.careerpath.match.MatchService;
import com.careerpath.portfolio.PortfolioService;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 学习任务（模块 7）。
 *
 * <p>闭环是这一模块的全部意义：**差距 → 任务 → 完成 → 自评等级更新 → 重算**。
 * 前四步是状态机，最后一步会真的重新调用匹配并落新快照——否则「完成之后有没有变好」
 * 就只能靠用户自己回概览页猜。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private static final Set<String> STATUS = Set.of("todo", "doing", "done", "paused");
    private static final Set<String> SOURCE = Set.of("gap", "manual");

    private final JdbcTemplate jdbc;
    private final PortfolioService portfolio;
    private final MatchService matches;

    public TaskService(JdbcTemplate jdbc, PortfolioService portfolio, MatchService matches) {
        this.jdbc = jdbc;
        this.portfolio = portfolio;
        this.matches = matches;
    }

    @Transactional(readOnly = true)
    public List<TaskDtos.TaskView> list(Long userId, String status, String source) {
        String st = blankToNull(status);
        if (st != null && !STATUS.contains(st)) {
            throw ApiException.validation("status 取值不合法：" + status);
        }
        String src = blankToNull(source);
        if (src != null && !SOURCE.contains(src)) {
            throw ApiException.validation("source 取值不合法：" + source);
        }
        List<Object> args = new ArrayList<>();
        args.add(userId);
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.skill_id, s.name AS skill_name, t.source, t.title, t.priority, t.due_date,
                       t.status, t.completed_at, t.created_at
                  FROM learning_task t
                  LEFT JOIN skill s ON s.id = t.skill_id
                 WHERE t.user_id = ?
                """);
        if (st != null) {
            sql.append(" AND t.status = ?");
            args.add(st);
        }
        if (src != null) {
            sql.append(" AND t.source = ?");
            args.add(src);
        }
        // 未完成优先、再按优先级：打开页面第一眼看到的应该是「现在该做什么」
        sql.append(" ORDER BY FIELD(t.status, 'doing', 'todo', 'paused', 'done'), t.priority DESC, t.id DESC");
        return jdbc.query(sql.toString(), (rs, i) -> new TaskDtos.TaskView(
                rs.getLong("id"), rs.getString("skill_id"), rs.getString("skill_name"),
                rs.getString("source"), rs.getString("title"), rs.getInt("priority"),
                rs.getObject("due_date", LocalDate.class), rs.getString("status"),
                Times.toInstant(rs, "completed_at"), Times.toInstant(rs, "created_at")), args.toArray());
    }

    @Transactional
    public TaskDtos.TaskView create(Long userId, TaskDtos.Create input) {
        String source = input.source() == null || input.source().isBlank() ? "manual"
                : requireEnum(input.source(), SOURCE, "source");
        String title = requireTitle(input.title());
        String skillId = blankToNull(input.skillId());
        if (skillId != null) {
            requireSkill(skillId);
        }
        int priority = input.priority() == null ? 0 : Math.max(0, Math.min(999, input.priority()));
        return insert(userId, skillId, source, title, priority, input.dueDate());
    }

    /**
     * 按某岗位最近一次匹配的差距生成任务。
     *
     * <p>没有历史快照就先算一次——「我还没匹配过，但我想知道该学什么」是完全合理的路径，
     * 让用户先回岗位页点一次匹配只是徒增一步。
     */
    @Transactional
    public Map<String, Object> fromGap(Long userId, long jobId, Integer topN) {
        int limit = topN == null ? 3 : Math.max(1, Math.min(10, topN));
        Map<String, Object> snapshot = matches.latestSnapshot(userId, jobId);
        if (snapshot == null) {
            snapshot = matches.match(userId, jobId).body();
        }

        Object gapsRaw = snapshot.get("gaps");
        List<Map<String, Object>> gaps = new ArrayList<>();
        if (gapsRaw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    gaps.add(typed);
                }
            }
        }

        List<TaskDtos.TaskView> created = new ArrayList<>();
        for (Map<String, Object> gap : gaps) {
            if (created.size() >= limit) {
                break;
            }
            String skillId = String.valueOf(gap.get("skillId"));
            if (hasUnfinishedTask(userId, skillId)) {
                continue;
            }
            String skillName = String.valueOf(gap.get("skillName"));
            int target = number(gap.get("target"));
            int level = number(gap.get("level"));
            int priority = number(gap.get("priority"));
            String title = "补齐「" + skillName + "」：L" + level + " → L" + target;
            created.add(insert(userId, skillId, "gap", title, priority, null));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("created", created.size());
        body.put("items", created);
        return body;
    }

    private boolean hasUnfinishedTask(Long userId, String skillId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM learning_task
                 WHERE user_id = ? AND skill_id = ? AND status IN ('todo', 'doing')
                """, Integer.class, userId, skillId);
        return count != null && count > 0;
    }

    @Transactional
    public TaskDtos.TaskView patch(Long userId, long id, TaskDtos.Patch input) {
        TaskDtos.TaskView current = requireTask(userId, id);
        String status = input.status() == null ? null : requireEnum(input.status(), STATUS, "status");
        String title = input.title() == null ? null : requireTitle(input.title());
        Integer priority = input.priority() == null ? null : Math.max(0, Math.min(999, input.priority()));

        Object completedAt = current.completedAt();
        if ("done".equals(status)) {
            completedAt = Times.nowUtc();
        } else if (status != null) {
            completedAt = null;
        }

        jdbc.update("""
                UPDATE learning_task
                   SET status = COALESCE(?, status),
                       title = COALESCE(?, title),
                       priority = COALESCE(?, priority),
                       due_date = COALESCE(?, due_date),
                       completed_at = ?
                 WHERE id = ? AND user_id = ?
                """, status, title, priority, input.dueDate(), completedAt, id, userId);

        return requireTask(userId, id);
    }

    public record CompleteResult(TaskDtos.TaskView task, boolean recalculated, Long matchedJobId) {
    }

    /**
     * 完成任务；可选把该技能的自评等级一并更新，然后**真的重算一次**匹配。
     *
     * <p>重算失败（分析服务没起）不让完成动作回滚：任务已经做完了，这是事实；
     * 返回 {@code recalculated=false}，前端提示「稍后重新匹配」即可。
     * 选哪个岗位重算：取最近一次要求了这项技能的岗位，用户刚从那里过来，心智对得上。
     */
    @Transactional
    public CompleteResult complete(Long userId, long id, TaskDtos.Complete input) {
        TaskDtos.TaskView task = requireTask(userId, id);
        boolean updateLevel = Boolean.TRUE.equals(input.updateSkillLevel());

        jdbc.update("UPDATE learning_task SET status = 'done', completed_at = ? WHERE id = ? AND user_id = ?",
                Times.nowUtc(), id, userId);

        if (updateLevel) {
            if (task.skillId() == null) {
                throw ApiException.validation("该任务没有关联技能，无法更新技能等级");
            }
            if (input.level() == null) {
                throw ApiException.validation("updateSkillLevel=true 时必须给出 level");
            }
            portfolio.saveUserSkills(userId, List.of(
                    new com.careerpath.portfolio.PortfolioDtos.UserSkillInput(
                            task.skillId(), input.level(), null, null)));
        }

        Long jobId = updateLevel ? latestJobRequiring(userId, task.skillId()) : null;
        if (jobId == null) {
            return new CompleteResult(requireTask(userId, id), false, null);
        }
        try {
            matches.match(userId, jobId);
            return new CompleteResult(requireTask(userId, id), true, jobId);
        } catch (ApiException ex) {
            log.warn("完成任务后重算匹配失败（job={}）：{}", jobId, ex.getMessage());
            return new CompleteResult(requireTask(userId, id), false, jobId);
        }
    }

    private Long latestJobRequiring(Long userId, String skillId) {
        List<Long> rows = jdbc.queryForList("""
                SELECT j.id
                  FROM job_position j
                  JOIN job_skill_requirement r ON r.job_position_id = j.id
                 WHERE j.user_id = ? AND r.skill_id = ?
                 ORDER BY j.id DESC LIMIT 1
                """, Long.class, userId, skillId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional
    public void delete(Long userId, long id) {
        int rows = jdbc.update("DELETE FROM learning_task WHERE id = ? AND user_id = ?", id, userId);
        if (rows == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "任务不存在");
        }
    }

    // ---------------------------------------------------------------- 内部

    private TaskDtos.TaskView insert(Long userId, String skillId, String source, String title,
                                     int priority, LocalDate dueDate) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO learning_task
                        (user_id, skill_id, source, title, priority, due_date, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'todo', ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setString(2, skillId);
            ps.setString(3, source);
            ps.setString(4, title);
            ps.setInt(5, priority);
            ps.setObject(6, dueDate);
            ps.setObject(7, Times.nowUtc());
            return ps;
        }, keys);
        long id = keys.getKey() == null ? 0 : keys.getKey().longValue();
        return requireTask(userId, id);
    }

    private TaskDtos.TaskView requireTask(Long userId, long id) {
        List<TaskDtos.TaskView> rows = jdbc.query("""
                SELECT t.id, t.skill_id, s.name AS skill_name, t.source, t.title, t.priority, t.due_date,
                       t.status, t.completed_at, t.created_at
                  FROM learning_task t
                  LEFT JOIN skill s ON s.id = t.skill_id
                 WHERE t.id = ? AND t.user_id = ?
                """, (rs, i) -> new TaskDtos.TaskView(
                rs.getLong("id"), rs.getString("skill_id"), rs.getString("skill_name"),
                rs.getString("source"), rs.getString("title"), rs.getInt("priority"),
                rs.getObject("due_date", LocalDate.class), rs.getString("status"),
                Times.toInstant(rs, "completed_at"), Times.toInstant(rs, "created_at")), id, userId);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        return rows.get(0);
    }

    private void requireSkill(String skillId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM skill WHERE id = ?", Integer.class, skillId);
        if (count == null || count == 0) {
            throw new ApiException(ErrorCode.UNKNOWN_SKILL, "技能词典中没有：" + skillId);
        }
    }

    private String requireTitle(String title) {
        String value = blankToNull(title);
        if (value == null) {
            throw ApiException.validation("任务标题不能为空");
        }
        if (value.length() > 128) {
            throw ApiException.validation("任务标题不能超过 128 字");
        }
        return value;
    }

    private String requireEnum(String value, Set<String> allowed, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!allowed.contains(normalized)) {
            throw ApiException.validation(field + " 取值不合法：" + value);
        }
        return normalized;
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private int number(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
