package com.careerpath.skill;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 技能分类（能力维度）。
 *
 * <p>表 {@code skill_category} 对应《重构方案》第四节的 6 个维度：编程基础、技术栈、数据与存储、
 * 工程能力、业务与协作、学习与成长。`parent_id` 预留给二级分类，首版为空。
 */
@TableName("skill_category")
public class SkillCategory {

    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    /** 雷达图轴标签（「基础」「技术栈」…）。缺了它前端只能显示完整维度名，六个轴必然挤在一起。 */
    @TableField("short")
    private String shortName;
    private String parentId;
    private String description;
    private String why;
    private Integer sortOrder;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getShortName() {
        return shortName;
    }

    public void setShortName(String shortName) {
        this.shortName = shortName;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getWhy() {
        return why;
    }

    public void setWhy(String why) {
        this.why = why;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
