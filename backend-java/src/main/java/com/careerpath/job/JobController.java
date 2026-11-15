package com.careerpath.job;

import com.careerpath.common.Items;
import com.careerpath.common.PageResult;
import com.careerpath.common.Principals;
import com.careerpath.security.AuthPrincipal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 岗位与解析接口（契约 3.3）。
 *
 * <p>路径统一在 {@code /api/jobs} 下，{@code parse} 与 {@code requirements} 是岗位的子资源：
 * 它们都脱离岗位没有意义，单独放顶层会让「这个技能要求属于哪个岗位」变得靠参数猜。
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService service;

    public JobController(JobService service) {
        this.service = service;
    }

    @GetMapping
    public PageResult<JobDtos.Summary> list(@AuthenticationPrincipal AuthPrincipal principal,
                                            @RequestParam(defaultValue = "1") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(required = false) String keyword) {
        return service.list(Principals.id(principal), page, size, keyword);
    }

    @GetMapping("/{id}")
    public JobDtos.Detail get(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable long id) {
        return service.get(Principals.id(principal), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobDtos.Detail create(@AuthenticationPrincipal AuthPrincipal principal,
                                 @RequestBody JobDtos.Upsert request) {
        return service.create(Principals.id(principal), request);
    }

    @PutMapping("/{id}")
    public JobDtos.Detail update(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable long id,
                                 @RequestBody JobDtos.Upsert request) {
        return service.update(Principals.id(principal), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable long id) {
        service.delete(Principals.id(principal), id);
    }

    /** 只返回建议，不落库；确认后调 {@code PUT /{id}/requirements}。 */
    @PostMapping("/{id}/parse")
    public JobDtos.ParseResponse parse(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable long id) {
        return service.parse(Principals.id(principal), id);
    }

    @GetMapping("/{id}/requirements")
    public Items<JobDtos.RequirementItem> requirements(@AuthenticationPrincipal AuthPrincipal principal,
                                                      @PathVariable long id) {
        return Items.of(service.requirements(Principals.id(principal), id));
    }

    @PutMapping("/{id}/requirements")
    public Items<JobDtos.RequirementItem> saveRequirements(@AuthenticationPrincipal AuthPrincipal principal,
                                                           @PathVariable long id,
                                                           @RequestBody JobDtos.RequirementsSave request) {
        List<JobDtos.RequirementInput> items = request == null ? List.of() : request.items();
        return Items.of(service.saveRequirements(Principals.id(principal), id, items));
    }
}
