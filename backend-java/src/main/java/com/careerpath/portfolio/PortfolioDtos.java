package com.careerpath.portfolio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 技能画像、证据链与项目（契约 3.4）。
 *
 * <p>{@code anyOf} 与 {@code techStack} 这类数组字段**恒为数组**：前端拿到 null 还要写一次
 * 兜底分支，而「没有成员」和「没这个字段」在业务上是同一件事。
 */
public final class PortfolioDtos {

    private PortfolioDtos() {
    }

    public record UserSkillView(String skillId, String skillName, String categoryId, int level,
                               LocalDate learnedAt, LocalDate lastUsedAt, String source, int evidenceCount) {
    }

    public record UserSkillInput(String skillId, Integer level, LocalDate learnedAt, LocalDate lastUsedAt) {
    }

    public record UserSkillSave(List<UserSkillInput> items) {
    }

    /** 证据里的技能关联：带 skillName，前端列表不用再查一次词典。 */
    public record EvidenceSkill(String skillId, String skillName, int relevance, String contributionNote) {
    }

    public record EvidenceSkillInput(String skillId, Integer relevance, String contributionNote) {
    }

    public record EvidenceView(long id, String evidenceType, String title, String url, String description,
                               int completeness, String verifyStatus, Instant submittedAt,
                               List<EvidenceSkill> skills) {
    }

    public record EvidenceInput(String evidenceType, String title, String url, String description,
                                Integer completeness, String verifyStatus, List<EvidenceSkillInput> skills) {
    }

    public record EvidenceSkillsSave(List<EvidenceSkillInput> items) {
    }

    public record MissingEvidence(String skillId, String skillName, int level) {
    }

    public record CoverageView(Double coverage, int requiredTotal, int coveredCount,
                               List<MissingEvidence> missingEvidence) {
    }

    public record ProjectSkill(String skillId, String skillName, String contributionNote) {
    }

    public record ProjectSkillInput(String skillId, String contributionNote) {
    }

    public record ProjectView(long id, String name, String role, List<String> techStack, String description,
                              String repoUrl, LocalDate startDate, LocalDate endDate,
                              List<ProjectSkill> skills) {
    }

    public record ProjectInput(String name, String role, List<String> techStack, String description,
                               String repoUrl, LocalDate startDate, LocalDate endDate) {
    }

    public record ProjectSkillsSave(List<ProjectSkillInput> items) {
    }
}
