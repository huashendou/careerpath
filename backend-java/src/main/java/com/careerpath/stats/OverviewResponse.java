package com.careerpath.stats;

import java.time.Instant;

/**
 * 概览页统计。契约 docs/协作看板.md 三节 3.7。
 *
 * <p>所有计数都是真实库统计，没有数据就是 0，前端据此显示空态；
 * 绝不用示例数字填充——答辩时被问「这个 3 是哪来的」答不上来，比显示 0 难看得多。
 *
 * <p>lastMatch 为 null 表示还没做过匹配，前端应引导去做第一次匹配，而不是展示 0 分。
 */
public record OverviewResponse(
        boolean profileCompleted,
        int jobCount,
        int taskOpenCount,
        int evidenceMissingCount,
        LastMatch lastMatch,
        Instant generatedAt
) {

    public record LastMatch(String jobTitle, Double requiredScore, Double bonusScore, Instant createdAt) {
    }
}
