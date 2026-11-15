package com.careerpath.interview;

import com.careerpath.application.ApplicationDtos;
import com.careerpath.application.ApplicationService;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 面试记录与复盘（模块 8 简化版）。
 *
 * <p>不做题库、不做面试日程、不做题目难度统计：这一版只回答一个问题——
 * 「这场面试我哪里没答上来，下次怎么补」。因此 {@code questions} 与 {@code relatedSkills}
 * 允许为空，但 {@code reflection} 有内容时前端会给它更大的版面。
 */
@Service
public class InterviewService {

    private static final Set<String> FORMAT = Set.of("phone", "video", "onsite");
    private static final Set<String> RESULT = Set.of("pending", "pass", "reject");

    private final JdbcTemplate jdbc;
    private final ApplicationService applications;
    private final JobService jobs;
    private final ObjectMapper json;

    public InterviewService(JdbcTemplate jdbc, ApplicationService applications, JobService jobs, ObjectMapper json) {
        this.jdbc = jdbc;
        this.applications = applications;
        this.jobs = jobs;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<InterviewDtos.View> list(Long userId) {
        return jdbc.query("""
                SELECT id, application_id, job_position_id, company_name, job_title, interview_date,
                       round, format, questions, reflection, result, related_skills, created_at, updated_at
                  FROM interview_record
                 WHERE user_id = ?
                 ORDER BY COALESCE(interview_date, DATE(created_at)) DESC, id DESC
                """, (rs, i) -> new InterviewDtos.View(
                rs.getLong("id"), (Long) rs.getObject("application_id"), (Long) rs.getObject("job_position_id"),
                rs.getString("company_name"), rs.getString("job_title"),
                rs.getObject("interview_date", LocalDate.class), rs.getString("round"), rs.getString("format"),
                readQuestions(rs.getString("questions")), rs.getString("reflection"), rs.getString("result"),
                readSkills(rs.getString("related_skills")),
                Times.toInstant(rs, "created_at"), Times.toInstant(rs, "updated_at")), userId);
    }

    @Transactional
    public InterviewDtos.View create(Long userId, InterviewDtos.Create input) {
        String format = blankToNull(input.format()) == null ? null : requireEnum(input.format(), FORMAT, "format");
        String result = blankToNull(input.result()) == null ? "pending" : requireEnum(input.result(), RESULT, "result");
        String round = truncate(blankToNull(input.round()), 32);
        List<InterviewDtos.Question> questions = sanitizeQuestions(input.questions());
        List<String> skills = sanitizeSkills(input.relatedSkills());

        String company = blankToNull(input.companyName());
        String title = blankToNull(input.jobTitle());

        // 关联投递记录 / 岗位时，把公司名和岗位名补齐：用户不必重复输入
        if (input.applicationId() != null) {
            ApplicationDtos.View app = applications.require(userId, input.applicationId());
            company = company == null ? app.companyName() : company;
            title = title == null ? app.title() : title;
        }
        if (input.jobPositionId() != null) {
            JobDtos.Detail job = jobs.get(userId, input.jobPositionId());
            company = company == null ? job.companyName() : company;
            title = title == null ? job.title() : title;
        }

        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        String finalCompany = company;
        String finalTitle = title;
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO interview_record
                        (user_id, application_id, job_position_id, company_name, job_title, interview_date,
                         round, format, questions, reflection, result, related_skills, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userId);
            ps.setObject(2, input.applicationId());
            ps.setObject(3, input.jobPositionId());
            ps.setString(4, finalCompany);
            ps.setString(5, finalTitle);
            ps.setObject(6, input.interviewDate());
            ps.setString(7, round);
            ps.setString(8, format);
            ps.setString(9, writeQuestions(questions));
            ps.setString(10, blankToNull(input.reflection()));
            ps.setString(11, result);
            ps.setString(12, writeSkills(skills));
            ps.setObject(13, Times.nowUtc());
            ps.setObject(14, Times.nowUtc());
            return ps;
        }, keys);
        long id = keys.getKey() == null ? 0 : keys.getKey().longValue();
        return require(userId, id);
    }

    @Transactional
    public InterviewDtos.View patch(Long userId, long id, InterviewDtos.Patch input) {
        require(userId, id);
        String format = blankToNull(input.format()) == null ? null : requireEnum(input.format(), FORMAT, "format");
        String result = blankToNull(input.result()) == null ? null : requireEnum(input.result(), RESULT, "result");

        jdbc.update("""
                UPDATE interview_record
                   SET company_name = COALESCE(?, company_name),
                       job_title = COALESCE(?, job_title),
                       interview_date = COALESCE(?, interview_date),
                       round = COALESCE(?, round),
                       format = COALESCE(?, format),
                       questions = COALESCE(?, questions),
                       reflection = COALESCE(?, reflection),
                       result = COALESCE(?, result),
                       related_skills = COALESCE(?, related_skills),
                       updated_at = ?
                 WHERE id = ? AND user_id = ?
                """,
                truncate(blankToNull(input.companyName()), 64), truncate(blankToNull(input.jobTitle()), 64),
                input.interviewDate(), truncate(blankToNull(input.round()), 32), format,
                input.questions() == null ? null : writeQuestions(sanitizeQuestions(input.questions())),
                blankToNull(input.reflection()), result,
                input.relatedSkills() == null ? null : writeSkills(sanitizeSkills(input.relatedSkills())),
                Times.nowUtc(), id, userId);
        return require(userId, id);
    }

    @Transactional
    public void delete(Long userId, long id) {
        int rows = jdbc.update("DELETE FROM interview_record WHERE id = ? AND user_id = ?", id, userId);
        if (rows == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "面试记录不存在");
        }
    }

    // ---------------------------------------------------------------- 内部

    private InterviewDtos.View require(Long userId, long id) {
        List<InterviewDtos.View> rows = jdbc.query("""
                SELECT id, application_id, job_position_id, company_name, job_title, interview_date,
                       round, format, questions, reflection, result, related_skills, created_at, updated_at
                  FROM interview_record WHERE id = ? AND user_id = ?
                """, (rs, i) -> new InterviewDtos.View(
                rs.getLong("id"), (Long) rs.getObject("application_id"), (Long) rs.getObject("job_position_id"),
                rs.getString("company_name"), rs.getString("job_title"),
                rs.getObject("interview_date", LocalDate.class), rs.getString("round"), rs.getString("format"),
                readQuestions(rs.getString("questions")), rs.getString("reflection"), rs.getString("result"),
                readSkills(rs.getString("related_skills")),
                Times.toInstant(rs, "created_at"), Times.toInstant(rs, "updated_at")), id, userId);
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "面试记录不存在");
        }
        return rows.get(0);
    }

    private List<InterviewDtos.Question> sanitizeQuestions(List<InterviewDtos.Question> input) {
        if (input == null) {
            return List.of();
        }
        if (input.size() > 30) {
            throw ApiException.validation("单场面试的题目不能超过 30 条");
        }
        List<InterviewDtos.Question> result = new ArrayList<>();
        for (InterviewDtos.Question item : input) {
            String question = blankToNull(item.question());
            if (question == null) {
                continue; // 空行直接忽略：前端表格里留了空行是常态
            }
            Integer rating = item.selfRating() == null ? null : Math.max(1, Math.min(5, item.selfRating()));
            result.add(new InterviewDtos.Question(truncate(question, 255), rating,
                    truncate(blankToNull(item.note()), 255)));
        }
        return result;
    }

    private List<String> sanitizeSkills(List<String> input) {
        if (input == null || input.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String raw : input) {
            String id = blankToNull(raw);
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        Set<String> found = new HashSet<>(jdbc.queryForList(
                "SELECT id FROM skill WHERE id IN (" + placeholders(ids.size()) + ")",
                String.class, ids.toArray()));
        for (String id : ids) {
            if (!found.contains(id)) {
                throw new ApiException(ErrorCode.UNKNOWN_SKILL, "技能词典中没有：" + id);
            }
        }
        return new ArrayList<>(ids);
    }

    private String writeQuestions(List<InterviewDtos.Question> list) {
        try {
            return json.writeValueAsString(list);
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "题目序列化失败");
        }
    }

    private List<InterviewDtos.Question> readQuestions(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<InterviewDtos.Question>>() {
            });
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String writeSkills(List<String> list) {
        try {
            return json.writeValueAsString(list);
        } catch (Exception ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "技能数组序列化失败");
        }
    }

    private List<String> readSkills(String raw) {
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

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String placeholders(int n) {
        return n == 0 ? "NULL" : String.join(",", java.util.Collections.nCopies(n, "?"));
    }
}
