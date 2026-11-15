package com.careerpath.skill;

import com.careerpath.common.Items;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 技能图谱接口（契约 3.2）。
 *
 * <p>三个接口都需登录：技能词典本身不敏感，但它是「岗位要求」和「个人自评」的共同引用源，
 * 免鉴权会让未登录用户看到一份与账号无关的完整能力模型，没有意义。
 */
@RestController
@RequestMapping("/api/skills")
public class SkillController {

    private final SkillQueryService service;

    public SkillController(SkillQueryService service) {
        this.service = service;
    }

    @GetMapping("/categories")
    public Items<SkillQueryService.CategoryView> categories() {
        return service.categories();
    }

    @GetMapping
    public Items<SkillQueryService.SkillView> skills(
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String keyword) {
        return service.skills(categoryId, keyword);
    }

    @GetMapping("/relations")
    public Items<SkillQueryService.RelationView> relations() {
        return service.relations();
    }
}
