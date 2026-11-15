package com.careerpath.job;

import java.time.Instant;
import java.util.List;

/**
 * 岗位模块的对外形状（契约 3.3）。
 *
 * <p>集中在一个容器类里而不是每个 record 一个文件：这些 DTO 只在岗位相关的两三个类里出现，
 * 拆成十几个文件后反而难看出「谁和谁是一组」。
 */
public final class JobDtos {

    private JobDtos() {
    }

    /** 列表项。不含 rawDescription：一页 20 条 JD 原文会把响应撑到几百 KB。 */
    public record Summary(long id, String title, String companyName, String city,
                          Integer salaryMin, Integer salaryMax, String parseStatus, boolean isDemo,
                          int requiredCount, int bonusCount, Instant createdAt) {
    }

    public record Detail(long id, String title, String companyName, String city,
                         Integer salaryMin, Integer salaryMax, String source, String url,
                         String rawDescription, String parseStatus, boolean isDemo,
                         Instant createdAt, Instant updatedAt) {
    }

    /** 新建/更新请求。更新时字段为 null 表示「不改」，不是「清空」。 */
    public record Upsert(String title, String companyName, String city,
                         Integer salaryMin, Integer salaryMax,
                         String source, String url, String rawDescription) {
    }

    /** 解析响应：只出建议，不落库，用户确认后走 requirements 覆盖接口。 */
    public record ParseResponse(long jobPositionId, List<Suggestion> requirements, Conditions conditions,
                                int matchedCount, int requiredCount, int bonusCount,
                                String algorithmVersion, String dictionaryVersion, String disclaimer) {
    }

    public record Suggestion(String skillId, String skillName, String dim, String reqType,
                             int weight, int targetLevel, String matchedAlias,
                             int sourceStart, int sourceEnd, String sourceText,
                             String evidenceHint, double confidence) {
    }

    public record Conditions(String education, String experience) {
    }

    public record RequirementItem(long id, String skillId, String skillName, String dim, String reqType,
                                  int weight, int targetLevel, List<String> anyOf,
                                  String evidenceHint, String sourceSpan) {
    }

    public record RequirementInput(String skillId, String reqType, Integer weight, Integer targetLevel,
                                   List<String> anyOf, String evidenceHint, String sourceSpan) {
    }

    public record RequirementsSave(List<RequirementInput> items) {
    }
}
