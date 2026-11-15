package com.careerpath.security;

/**
 * 已认证用户的最小身份信息。
 *
 * <p>刻意不带 passwordHash：控制器层拿不到口令哈希，就不存在「顺手返回给前端」的可能。
 *
 * @param id       用户 id
 * @param username 用户名
 * @param role     角色，v3 首版只有 student
 */
public record AuthPrincipal(Long id, String username, String role) {
}
