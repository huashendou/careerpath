package com.careerpath.stats;

import java.time.Instant;

/**
 * 概览页统计。契约 docs/协作看板.md 三节 3.7。
 *
 * <p>所有计数都是真实库统计，没有数据就是 0，前端据此显示空态；
 * 绝不用示例数字填充——答辩时被问「这个 3 是哪来的」答不上来，比显示 0 难看得多。
 *
 * <p>lastMatch 为 null 表示还没做过匹配，前端应引导去做第一次匹配，而不是展示 0 分。
 *
 * <p>lastMatch 里带 jobPositionId：概览要能「一键回到上次那份报告」，只给标题做不到，
 * 前端只能让用户自己去岗位列表里找，白跑一趟。
 *
 * <p>selfRatedCount 与 parsedJobCount 是给概览页四步进度条用的：
 * 前者答「自评过几项」（level &gt; 0 才算，落库了 0 分的不算），后者答「几个岗位真有技能要求」。
 * 少了 parsedJobCount，进度条会把「录入了岗位但没解析」当成第三步已完成，
 * 用户走到第四步匹配时才被告诉「该岗位没有技能要求」——错在进度条骗了他。
 */
public record OverviewResponse(
        boolean profileCompleted,
        int selfRatedCount,
        int jobCount,
        int parsedJobCount,
        int taskOpenCount,
        int evidenceMissingCount,
        LastMatch lastMatch,
        Instant generatedAt
) {

    public record LastMatch(Long jobPositionId, String jobTitle, Double requiredScore,
                            Double bonusScore, Instant createdAt) {
    }
}
