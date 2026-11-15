package com.careerpath.task;

import java.time.Instant;
import java.time.LocalDate;

/** 学习任务（契约 3.6）。 */
public final class TaskDtos {

    private TaskDtos() {
    }

    public record TaskView(long id, String skillId, String skillName, String source, String title,
                           int priority, LocalDate dueDate, String status, Instant completedAt,
                           Instant createdAt) {
    }

    public record Create(String skillId, String source, String title, Integer priority, LocalDate dueDate) {
    }

    public record Patch(String status, String title, Integer priority, LocalDate dueDate) {
    }

    public record FromGap(Integer topN) {
    }

    public record Complete(Boolean updateSkillLevel, Integer level) {
    }
}
