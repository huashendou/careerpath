package com.careerpath.profile.dto;

import com.careerpath.user.UserProfile;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 个人档案响应。契约：GET /api/profile 未填写时字段为 null、不返回 404。
 *
 * <p>不直接回实体：实体带 userId 与 updatedAt 两个内部字段，回实体等于把内部主键写进契约。
 */
public record ProfileResponse(
        String realName,
        String school,
        String major,
        String grade,
        String targetJobType,
        String targetCity,
        String phone,
        String email,
        Instant updatedAt
) {

    /** 从未填写过档案时返回它，前端据此渲染「完善档案」引导。 */
    public static ProfileResponse empty() {
        return new ProfileResponse(null, null, null, null, null, null, null, null, null);
    }

    public static ProfileResponse of(UserProfile entity) {
        if (entity == null) {
            return empty();
        }
        return new ProfileResponse(
                entity.getRealName(),
                entity.getSchool(),
                entity.getMajor(),
                entity.getGrade(),
                entity.getTargetJobType(),
                entity.getTargetCity(),
                entity.getPhone(),
                entity.getEmail(),
                entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }
}
