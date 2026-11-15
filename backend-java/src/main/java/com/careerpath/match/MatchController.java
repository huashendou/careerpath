package com.careerpath.match;

import com.careerpath.common.ApiException;
import com.careerpath.common.ErrorCode;
import com.careerpath.common.Items;
import com.careerpath.common.Principals;
import com.careerpath.security.AuthPrincipal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 匹配与报告（契约 3.5）。
 *
 * <p>{@code /api/match} 与 {@code /api/reports} 是两个入口、一份实现：前者给「现在看看差多少」，
 * 后者给「归档成一份可回看的报告」。两者都会落快照，区别只在响应里带不带 {@code id}。
 */
@RestController
public class MatchController {

    private final MatchService service;

    public MatchController(MatchService service) {
        this.service = service;
    }

    @PostMapping("/api/match/{jobId}")
    public Map<String, Object> match(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable long jobId) {
        return service.match(Principals.id(principal), jobId).body();
    }

    @PostMapping("/api/reports/{jobId}")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createReport(@AuthenticationPrincipal AuthPrincipal principal,
                                            @PathVariable long jobId) {
        MatchService.Outcome outcome = service.match(Principals.id(principal), jobId);
        Map<String, Object> body = new LinkedHashMap<>(outcome.body());
        body.put("id", outcome.reportId());
        body.put("createdAt", outcome.createdAt());
        return body;
    }

    @GetMapping("/api/reports")
    public Items<Map<String, Object>> reports(@AuthenticationPrincipal AuthPrincipal principal,
                                              @RequestParam(required = false) Long jobId) {
        return Items.of(service.reports(Principals.id(principal), jobId));
    }

    @GetMapping("/api/reports/{id}")
    public Map<String, Object> report(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable long id) {
        if (id <= 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "报告不存在");
        }
        return service.report(Principals.id(principal), id);
    }
}
