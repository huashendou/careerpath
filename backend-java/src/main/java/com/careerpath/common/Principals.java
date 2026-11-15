package com.careerpath.common;

import com.careerpath.security.AuthPrincipal;

/**
 * 从已认证主体里取用户 id 的唯一入口。
 *
 * <p>所有业务控制器都只从这里拿 id，**不从路径参数或请求体里取 userId**：
 * 越权漏洞几乎都是「顺手信任了客户端传来的 id」造成的。取不到就 401，
 * 与契约「越权访问他人资源返回 404」配套使用。
 */
public final class Principals {

    private Principals() {
    }

    public static Long id(AuthPrincipal principal) {
        if (principal == null || principal.id() == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        return principal.id();
    }
}
