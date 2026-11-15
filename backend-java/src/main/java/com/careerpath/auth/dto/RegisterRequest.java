package com.careerpath.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册入参。
 *
 * <p>用户名限 4-32 位字母数字下划线：允许中文会带来全角/半角与同形字问题，登录名混淆的代价
 * 远大于「用户名不够个性」；昵称走 user_profile.realName。
 *
 * <p>密码下限 8 位：6 位在离线爆破面前基本等于没有。
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[A-Za-z0-9_]{4,32}$", message = "用户名需为 4-32 位字母、数字或下划线")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度需为 8-64 位")
        String password
) {
}
