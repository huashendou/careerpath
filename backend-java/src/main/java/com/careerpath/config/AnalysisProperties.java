package com.careerpath.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Python 分析服务的连接配置。
 *
 * @param baseUrl   分析服务地址，只允许内网/本机
 * @param token     内部共享密钥，写进请求头 {@code X-Internal-Token}
 * @param timeoutMs 调用超时（毫秒）。健康检查要快失败，不能把 /api/health 拖成 30 秒
 */
@ConfigurationProperties("app.internal")
public record AnalysisProperties(String baseUrl, String token, int timeoutMs) {
}
