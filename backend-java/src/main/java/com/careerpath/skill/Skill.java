package com.careerpath.skill;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import java.util.List;

/**
 * 技能词典项。
 *
 * <p>主键用业务 id（如 {@code js-core}）而不是自增号：种子可幂等 upsert，
 * Python 分析服务返回的 skillId 也能直接对照，省掉一次映射查询。
 *
 * <p>{@code aliases} 是 JSON 数组，只在 JD/简历解析时使用，列表接口不必返回全部别名。
 */
@TableName(value = "skill", autoResultMap = true)
public class Skill {

    @TableId(type = IdType.INPUT)
    private String id;
    private String categoryId;
    private String name;
    private String nameEn;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> aliases;

    private String description;
    private Integer sortOrder;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNameEn() {
        return nameEn;
    }

    public void setNameEn(String nameEn) {
        this.nameEn = nameEn;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public void setAliases(List<String> aliases) {
        this.aliases = aliases;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
