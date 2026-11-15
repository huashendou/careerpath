package com.careerpath.auth.dto;

import java.time.Instant;

/**
 * 登录/注册成功的响应体。契约见 docs/协作看板.md 三节：register 返回 201，login 返回 200，体相同。
 *
 * <p>createdAt 用 Instant 而不是 LocalDateTime：契约样例是 "2026-11-15T08:30:00Z"，带时区标记；
 * Jackson 对 LocalDateTime 默认不带 Z，前端 new Date(...) 会按本地时区解析，差出 8 小时。
 */
public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UserBrief user
) {

    /** 账号摘要。不含密码哈希，也不含学校手机号——令牌接口不需要这些。 */
    public record UserBrief(Long id, String username, String role, Instant createdAt) {
    }

    public static AuthResponse bearer(String token, long expiresIn, UserBrief user) {
        return new AuthResponse(token, "bearer", expiresIn, user);
    }
}
