package com.careerpath.application;

import com.careerpath.common.ApiException;
import com.careerpath.common.ErrorCode;
import com.careerpath.common.Times;
import com.careerpath.job.JobDtos;
import com.careerpath.job.JobService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 投递清单（模块 2 简化版）。
 *
 * <p>刻意只做四件事：列出、加入、改状态、加备注。不做漏斗图、不做跟进提醒、不做面试日程——
 * 这些是一期范围裁决里明确砍掉的部分，二期也只补「简化版」。
 *
 * <p>状态历史存 {@code status_log} JSON 而不是独立表：一条投递最多改五六次状态，
 * 为它建一张带外键的表，收益远小于「每次改状态要写两处」的成本。
 */
@Service
public class ApplicationService {

    private static final Set<String> STATUS = Set.of("todo", "applied", "interview", "closed");

    private final JdbcTemplate jdbc;
    private final JobService jobs;
    private final ObjectMapper json;

    public ApplicationService(JdbcTemplate jdbc, JobService jobs, ObjectMapper json) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<ApplicationDtos.View> list(Long userId, String status) {
        String st = blankToNull(status);
        if (st != null && !STATUS.contains(st)) {
            throw ApiException.validation("status 取值不合法：" + status);
        }
        List<Object> args = new ArrayList<>();
        args.add(userId);
        String filter = "";
        if (st != null) {
            filter = " AND status = ?";
            args.add(st);
        }
        return jdbc.query("""
                SELECT id, job_position_id, title, company_name, status, status_log, note,
                       applied_at, updated_at, created_at
                  FROM job_application
                 WHERE user_id = ?""" + filter + """
                 ORDER BY FIELD(status, 'interview', 'applied', 'todo', 'closed'), updated_at DESC, id DESC
                """, (rs, i) -> new ApplicationDtos.View(
                rs.getLong("id"), (Long) rs.getObject("job_position_id"), rs.getString("title"),
                rs.getString("company_name"), rs.getString("status"),
                readLog(rs.getString("status_log")), rs.getString("note"),
                Times.toInstant(rs, "applied_at"), Times.toInstant(rs, "updated_at"),
                Times.toInstant(rs, "created_at")), args.toArray());
    }

    @Transactional
    public ApplicationDtos.View create(Long userId, ApplicationDtos.Create input) {
        Long jobId = input.jobPositionId();
        String title = blankToNull(input.title());
        String company = blankToNull(input.companyName());

        if (jobId != null) {
            JobDtos.Detail job = jobs.get(userId, jobId);
            if (title == null) {
                title = job.title();
            }
            if (company == null) {
                company = job.companyName();
            }
        }
        if (title == null) {
            throw ApiException.validation("岗位名称不能为空（没有关联岗位时必须手填）");
        }
        if (title.length() > 64) {
            throw ApiException.validation("岗位名称不能超过 64 字");
        }

        try {
            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            Long finalJobId = jobId;
            String finalTitle = title;
            String finalCompany = company;
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO job_application
                            (user_id, job_position_id, company_name, title, status, status_log, note,
                             created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'todo', ?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, userId);
                ps.setObject(2, finalJobId);
                ps.setString(3, finalCompany);
                ps.setString(4, finalTitle);
                ps.setString(5, "[]");
                ps.setString(6, truncate(blankToNull(input.note()), 500));
                ps.setObject(7, Times.nowUtc());
                ps.setObject(8, Times.nowUtc());
                return ps;
            }, keys);
            long id = keys.getKey() == null ? 0 : keys.getKey().longValue();
            return require(userId, id);
        } catch (DuplicateKeyException ex) {
            // 唯一索引 uk_app_user_job 兜底：同一岗位重复投递不是「服务器错误」，是可解释的冲突
            throw new ApiException(ErrorCode.DUPLICATE_RESOURCE, "该岗位已在投递清单里");
        }
    }

    @Transactional
    public ApplicationDtos.View patch(Long userId, long id, ApplicationDtos.Patch input) {
        ApplicationDtos.View current = require(userId, id);
        String status = blankToNull(input.status());
        if (status != null && !STATUS.contains(status)) {
            throw ApiException.validation("status 取值不合法：" + input.status());
        }
        if (status != null && status.equals(current.status())) {
            status = null; // 同状态不写历史，避免日志里出现一堆 todo→todo
        }

        List<ApplicationDtos.StatusChange> log = new ArrayList<>(current.statusLog());
        if (status != null) {
            log.add(new ApplicationDtos.StatusChange(current.status(), status, Times.nowUtc().toInstant(java.time.ZoneOffset.UTC)));
        }
        Instant appliedAt = current.appliedAt();
        if ("applied".equals(status) && appliedAt == null) {
            appliedAt = Times.nowUtc().toInstant(java.time.ZoneOffset.UTC);
        }

        jdbc.update("""
                UPDATE job_application
                   SET status = COALESCE(?, status),
                       note = COALESCE(?, note),
                       status_log = ?,
                       applied_at = ?,
                       updated_at = ?
                 WHERE id = ? AND user_id = ?
                """, status, truncate(blankToNull(input.note()), 500), writeLog(log), appliedAt,
                Times.nowUtc(), id, userId);
        return require(userId, id);
    }

    @Transactional
    public void delete(Long userId, long id) {
        int rows = jdbc.update("DELETE FROM job_application WHERE id = ? AND user_id = ?", id, userId);
        if (rows == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "投递记录不存在");
        }
    }

    /** 面试复盘模块用它把 applicationId 解析成公司/岗位名。 */
    @Transactional(readOnly = true)
    public ApplicationDtos.View require(Long userId, long id) {
        List<ApplicationDtos.View> rows = jdbc.query("""
                SELECT id, job_position_id, title, company_name, status, status_log, note,
                       applied_at, updated_at, created_at
                  FROM job_application WHERE id = ? AND user_id = ?
                """, (rs, i) -> new ApplicationDtos.View(
                rs.getLong("id"), (Long) rs.getObject("job_position_id"), rs.getString("title"),
                rs.getString("company_name"), rs.getString("status"),
                readLog(rs.getString("status_log")), rs.getString("note"),
                Times.toInstant(rs, "applied_at"), Times.toInstant(rs, "updated_at"),
                Times.toInstant(rs, "created_at")), id, userId);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "投递记录不存在");
        }
        return rows.get(0);
    }

    // ---------------------------------------------------------------- 内部

    private String writeLog(List<ApplicationDtos.StatusChange> log) {
        List<Map<String, Object>> rows = log.stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("from", item.from());
            row.put("to", item.to());
            row.put("at", item.at());
            return row;
        }).toList();
        try {
            return json.writeValueAsString(rows);
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "状态历史序列化失败");
        }
    }

    private List<ApplicationDtos.StatusChange> readLog(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = json.readValue(raw, new TypeReference<List<Map<String, Object>>>() {
            });
            List<ApplicationDtos.StatusChange> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                result.add(new ApplicationDtos.StatusChange(
                        row.get("from") == null ? null : String.valueOf(row.get("from")),
                        row.get("to") == null ? null : String.valueOf(row.get("to")),
                        parseInstant(row.get("at"))));
            }
            return result;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Instant parseInstant(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private String blankToNull(String value) {
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
}
