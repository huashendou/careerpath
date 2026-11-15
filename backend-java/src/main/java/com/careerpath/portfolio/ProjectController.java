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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 项目（模块 10 并入模块 5，契约 3.4 末尾两行）。不单设页面，挂在技能画像页里。 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final PortfolioService service;

    public ProjectController(PortfolioService service) {
        this.service = service;
    }

    @GetMapping
    public Items<PortfolioDtos.ProjectView> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return Items.of(service.projects(Principals.id(principal)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PortfolioDtos.ProjectView create(@AuthenticationPrincipal AuthPrincipal principal,
                                            @RequestBody PortfolioDtos.ProjectInput request) {
        return service.createProject(Principals.id(principal), request);
    }

    @PutMapping("/{id}")
    public PortfolioDtos.ProjectView update(@AuthenticationPrincipal AuthPrincipal principal,
                                            @PathVariable long id,
                                            @RequestBody PortfolioDtos.ProjectInput request) {
        return service.updateProject(Principals.id(principal), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable long id) {
        service.deleteProject(Principals.id(principal), id);
    }

    @PostMapping("/{id}/skills")
    public Items<PortfolioDtos.ProjectSkill> saveSkills(@AuthenticationPrincipal AuthPrincipal principal,
                                                        @PathVariable long id,
                                                        @RequestBody PortfolioDtos.ProjectSkillsSave request) {
        List<PortfolioDtos.ProjectSkillInput> items = request == null ? List.of() : request.items();
        return Items.of(service.saveProjectSkills(Principals.id(principal), id, items));
    }
}
