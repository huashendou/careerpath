package com.careerpath.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录入参。
 *
 * <p>这里不做格式校验：登录只判断「对不对」，回一句「用户名格式不合法」等于告诉攻击者
 * 这个账号不可能存在。
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password
) {
}
