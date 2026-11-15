package com.careerpath.skill;

import com.careerpath.common.Items;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 技能词典查询（模块 4）。
 *
 * <p>词典是只读数据，由启动时的 {@link SkillSeedRunner} 幂等灌入，因此这里没有写接口。
 * 用 JdbcTemplate 而不是 MyBatis-Plus 实体：三个查询都是投影（不要 aliases、不要审计字段），
 * 走实体反而要把整行读出来再裁掉一半字段。
 */
@Service
public class SkillQueryService {

    private final JdbcTemplate jdbc;

    public SkillQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 6 个能力维度，按 sort_order 排。雷达图的轴顺序就取自这里。 */
    @Transactional(readOnly = true)
    public Items<CategoryView> categories() {
        List<CategoryView> rows = jdbc.query("""
                SELECT id, name, `short`, description, why
                  FROM skill_category
                 ORDER BY sort_order, id
                """, (rs, i) -> new CategoryView(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("short"),
                rs.getString("description"),
                rs.getString("why")));
        return Items.of(rows);
    }

    /**
     * 技能列表，可按维度过滤、按名称模糊搜。
     *
     * <p>keyword 同时匹配中文名、英文名与别名（JSON 列用 CAST 成文本再 LIKE）：
     * 学生记得住 "spring" 但记不住「Spring 框架与依赖注入」的全称。
     */
    @Transactional(readOnly = true)
    public Items<SkillView> skills(String categoryId, String keyword) {
        String cat = blankToNull(categoryId);
        String kw = blankToNull(keyword);
        String like = kw == null ? null : "%" + kw + "%";
        List<SkillView> rows = jdbc.query("""
                SELECT id, name, name_en, category_id, description
                  FROM skill
                 WHERE (? IS NULL OR category_id = ?)
                   AND (? IS NULL OR name LIKE ? OR name_en LIKE ? OR CAST(aliases AS CHAR) LIKE ?)
                 ORDER BY category_id, sort_order, id
                """, (rs, i) -> new SkillView(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("name_en"),
                rs.getString("category_id"),
                rs.getString("description")),
                cat, cat, like, like, like, like);
        return Items.of(rows);
    }

    /** 技能关系：用于「学这项之前先学什么」的提示，前端按 from 分组即可。 */
    @Transactional(readOnly = true)
    public Items<RelationView> relations() {
        List<RelationView> rows = jdbc.query("""
                SELECT from_skill_id, to_skill_id, relation_type
                  FROM skill_relation
                 ORDER BY from_skill_id, relation_type, to_skill_id
                """, (rs, i) -> new RelationView(
                rs.getString("from_skill_id"),
                rs.getString("to_skill_id"),
                rs.getString("relation_type")));
        return Items.of(rows);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** {@code short} 是 Java 关键字，字段只能叫 shortName，序列化时用注解答回契约名。 */
    public record CategoryView(String id, String name,
                               @JsonProperty("short") String shortName,
                               String description, String why) {
    }

    public record SkillView(String id, String name, String nameEn, String categoryId, String description) {
    }

    public record RelationView(String fromSkillId, String toSkillId, String relationType) {
    }
}
