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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 技能自评（契约 3.4 的 UserSkill 部分）。批量 upsert，不做全量删除。 */
@RestController
@RequestMapping("/api/user-skills")
public class UserSkillController {

    private final PortfolioService service;

    public UserSkillController(PortfolioService service) {
        this.service = service;
    }

    @GetMapping
    public Items<PortfolioDtos.UserSkillView> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return Items.of(service.userSkills(Principals.id(principal)));
    }

    @PutMapping
    public Items<PortfolioDtos.UserSkillView> save(@AuthenticationPrincipal AuthPrincipal principal,
                                                   @RequestBody PortfolioDtos.UserSkillSave request) {
        List<PortfolioDtos.UserSkillInput> items = request == null ? List.of() : request.items();
        return Items.of(service.saveUserSkills(Principals.id(principal), items));
    }

    @DeleteMapping("/{skillId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable String skillId) {
        service.deleteUserSkill(Principals.id(principal), skillId);
    }
}
