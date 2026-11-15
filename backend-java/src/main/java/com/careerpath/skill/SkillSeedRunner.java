package com.careerpath.skill;

import com.careerpath.config.SeedProperties;
import com.careerpath.skill.mapper.SkillCategoryMapper;
import com.careerpath.skill.mapper.SkillMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 技能词典种子导入（{@code data/skills.json} → {@code skill_category} + {@code skill}）。
 *
 * <p>**幂等**：按业务 id upsert，连续执行两次不会产生重复行，也不会把 id 换成新自增号。
 * 这一点 v1 是靠 seed 脚本里 `DELETE + INSERT` 硬保证的，代价是每次重启都重建主键；
 * 现在改成逐行 upsert，技能 id 稳定，`user_skill.skill_id` 外键不会被连带破坏。
 *
 * <p>为什么不放在 Python 侧：写业务表是 Java 的职责（AGENTS.md 第四节），
 * 分析服务只读词典 JSON，不碰数据库；两边读同一个文件，不存在第二份真相。
 */
@Component
public class SkillSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SkillSeedRunner.class);

    private final SeedProperties properties;
    private final SkillCategoryMapper categoryMapper;
    private final SkillMapper skillMapper;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbc;

    public SkillSeedRunner(SeedProperties properties, SkillCategoryMapper categoryMapper,
                           SkillMapper skillMapper, ObjectMapper objectMapper, JdbcTemplate jdbc) {
        this.properties = properties;
        this.categoryMapper = categoryMapper;
        this.skillMapper = skillMapper;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            log.info("技能种子导入已关闭（app.seed.enabled=false）");
            return;
        }
        Path path = resolveSeedFile(properties.skillsFile());
        if (path == null) {
            // 不直接抛异常：种子缺失不该让整个服务起不来，健康检查里 skillCount=0 会暴露它
            log.error("技能种子文件不存在：{}（已尝试 ./ 与 ../ 两级），跳过导入", properties.skillsFile());
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(path.toFile());
            int categories = seedCategories(root.path("dimensions"));
            int skills = seedSkills(root.path("skills"));
            int relations = seedRelations(path);
            log.info("技能种子导入完成：{} 类 / {} 技能 / {} 条关系（{}）", categories, skills, relations, path);
        } catch (IOException ex) {
            log.error("读取技能种子失败：{}", path, ex);
        }
    }

    /**
     * 兼容两种工作目录：在 {@code backend-java} 下启动（IDE 默认）与在项目根下启动（脚本/容器）。
     * 环境变量里写的是「相对项目根」的路径，这里负责把它落到真实文件。
     */
    private Path resolveSeedFile(String configured) {
        Path raw = Path.of(configured);
        if (raw.isAbsolute()) {
            return Files.isRegularFile(raw) ? raw : null;
        }
        for (Path base : new Path[]{Path.of(""), Path.of("..")}) {
            Path candidate = base.resolve(raw).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private int seedCategories(JsonNode dimensions) {
        int index = 0;
        int count = 0;
        for (JsonNode node : dimensions) {
            SkillCategory category = new SkillCategory();
            category.setId(node.path("id").asText());
            category.setName(node.path("name").asText());
            category.setShortName(node.path("short").asText(""));
            category.setDescription(node.path("desc").asText(""));
            category.setWhy(node.path("why").asText(""));
            category.setSortOrder(index++);
            upsertCategory(category);
            count++;
        }
        return count;
    }

    private void upsertCategory(SkillCategory category) {
        if (categoryMapper.selectById(category.getId()) == null) {
            categoryMapper.insert(category);
        } else {
            categoryMapper.updateById(category);
        }
    }

    private int seedSkills(JsonNode skills) {
        Set<String> knownCategories = new HashSet<>();
        categoryMapper.selectList(null).forEach(item -> knownCategories.add(item.getId()));

        List<Skill> rows = new ArrayList<>();
        int index = 0;
        for (JsonNode node : skills) {
            String dim = node.path("dim").asText();
            if (!knownCategories.contains(dim)) {
                // 静默跳过会让「少了一条技能」在结果里看不出来，必须留日志
                log.warn("跳过技能 {}：分类 {} 不存在", node.path("id").asText(), dim);
                continue;
            }
            Skill skill = new Skill();
            skill.setId(node.path("id").asText());
            skill.setCategoryId(dim);
            skill.setName(node.path("name").asText());
            skill.setNameEn(node.path("nameEn").asText(null));
            skill.setDescription(node.path("note").asText(""));
            skill.setSortOrder(index++);

            List<String> aliases = new ArrayList<>();
            node.path("aliases").forEach(alias -> aliases.add(alias.asText()));
            skill.setAliases(aliases);
            rows.add(skill);
        }

        int inserted = 0;
        int updated = 0;
        for (Skill skill : rows) {
            if (skillMapper.selectById(skill.getId()) == null) {
                skillMapper.insert(skill);
                inserted++;
            } else {
                skillMapper.updateById(skill);
                updated++;
            }
        }
        log.debug("技能 upsert：新增 {} / 更新 {}", inserted, updated);
        return rows.size();
    }

    /**
     * 技能关系种子（{@code data/skill-relations.json}，与 skills.json 同目录）。
     *
     * <p>单独一个文件而不是塞进 skills.json：Python 侧只读技能清单，多一个并列数组会让
     * 「词典结构」和「关系图」两件事挤在同一个 schema 里，改一边要重新校验另一边。
     *
     * <p>幂等靠唯一索引 + {@code INSERT IGNORE}；端点两端都先在内存里校验存在性——
     * 外键错误不会被 IGNORE 吞掉，一条坏数据会让整个启动失败。
     */
    private int seedRelations(Path skillsPath) {
        Path parent = skillsPath.getParent();
        Path file = parent == null ? Path.of("skill-relations.json") : parent.resolve("skill-relations.json");
        if (!Files.isRegularFile(file)) {
            log.warn("技能关系种子不存在：{}，跳过（关系图为空不影响解析）", file);
            return 0;
        }
        try {
            JsonNode root = objectMapper.readTree(file.toFile());
            Set<String> known = new HashSet<>(jdbc.queryForList("SELECT id FROM skill", String.class));
            int added = 0;
            int skipped = 0;
            for (JsonNode node : root.path("relations")) {
                String from = node.path("from").asText();
                String to = node.path("to").asText();
                String type = node.path("type").asText();
                if (!known.contains(from) || !known.contains(to)
                        || !Set.of("prerequisite", "related", "ecosystem").contains(type)) {
                    log.warn("跳过非法技能关系：{} -[{}]-> {}", from, type, to);
                    skipped++;
                    continue;
                }
                added += jdbc.update("""
                        INSERT IGNORE INTO skill_relation (from_skill_id, to_skill_id, relation_type)
                        VALUES (?, ?, ?)
                        """, from, to, type);
            }
            if (skipped > 0) {
                log.warn("技能关系：{} 条非法已跳过", skipped);
            }
            return added;
        } catch (IOException ex) {
            log.error("读取技能关系种子失败：{}", file, ex);
            return 0;
        }
    }
}
