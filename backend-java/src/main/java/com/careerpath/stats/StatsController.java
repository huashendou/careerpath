package com.careerpath.stats;

import com.careerpath.common.ApiException;
import com.careerpath.common.ErrorCode;
import com.careerpath.security.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 概览统计接口。契约 docs/协作看板.md 三节 3.7。 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/overview")
    public OverviewResponse overview(@AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        return statsService.overview(principal.id());
    }

    /** 数据看板（二期模块 9）。不单开页面，概览页直接消费。 */
    @GetMapping("/insights")
    public StatsService.StatsInsights insights(@AuthenticationPrincipal AuthPrincipal principal) {
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        return statsService.insights(principal.id());
    }
}
