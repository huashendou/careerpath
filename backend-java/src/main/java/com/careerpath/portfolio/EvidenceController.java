package com.careerpath.portfolio;

import com.careerpath.common.Items;
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
 * 证据链接口（契约 3.4）。
 *
 * <p>{@code /coverage} 必须声明在 {@code /{id}} 之前才安全——Spring 的精确路径优先级本来就高于
 * 变量路径，但显式写在前面能让读代码的人一眼看出「coverage 不是某个证据的 id」。
 */
@RestController
@RequestMapping("/api/evidence")
public class EvidenceController {

    private final PortfolioService service;

    public EvidenceController(PortfolioService service) {
        this.service = service;
    }

    @GetMapping("/coverage")
    public PortfolioDtos.CoverageView coverage(@AuthenticationPrincipal AuthPrincipal principal) {
        return service.coverage(Principals.id(principal));
    }

    @GetMapping
    public Items<PortfolioDtos.EvidenceView> list(@AuthenticationPrincipal AuthPrincipal principal,
                                                  @RequestParam(required = false) String skillId) {
        return Items.of(service.evidence(Principals.id(principal), skillId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PortfolioDtos.EvidenceView create(@AuthenticationPrincipal AuthPrincipal principal,
                                             @RequestBody PortfolioDtos.EvidenceInput request) {
        return service.createEvidence(Principals.id(principal), request);
    }

    @PutMapping("/{id}")
    public PortfolioDtos.EvidenceView update(@AuthenticationPrincipal AuthPrincipal principal,
                                             @PathVariable long id,
                                             @RequestBody PortfolioDtos.EvidenceInput request) {
        return service.updateEvidence(Principals.id(principal), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable long id) {
        service.deleteEvidence(Principals.id(principal), id);
    }

    /** 全量覆盖该证据的技能关联。 */
    @PostMapping("/{id}/skills")
    public Items<PortfolioDtos.EvidenceSkill> saveSkills(@AuthenticationPrincipal AuthPrincipal principal,
                                                         @PathVariable long id,
                                                         @RequestBody PortfolioDtos.EvidenceSkillsSave request) {
        List<PortfolioDtos.EvidenceSkillInput> items = request == null ? List.of() : request.items();
        return Items.of(service.saveEvidenceSkills(Principals.id(principal), id, items));
    }
}
