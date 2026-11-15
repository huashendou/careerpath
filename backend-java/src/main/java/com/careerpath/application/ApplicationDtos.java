package com.careerpath.application;

import java.time.Instant;
import java.util.List;

/** 投递清单（模块 2 简化版）。状态只有四档，历史存 JSON。 */
public final class ApplicationDtos {

    private ApplicationDtos() {
    }

    public record StatusChange(String from, String to, Instant at) {
    }

    public record View(long id, Long jobPositionId, String title, String companyName, String status,
                       List<StatusChange> statusLog, String note, Instant appliedAt,
                       Instant updatedAt, Instant createdAt) {
    }

    public record Create(Long jobPositionId, String title, String companyName, String note) {
    }

    public record Patch(String status, String note) {
    }
}
