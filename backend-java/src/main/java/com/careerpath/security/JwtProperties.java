package com.careerpath.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置。
 *
 * @param secret        签名密钥，长度必须至少 32 字节（HS256 要求 256 位）
 * @param expireMinutes 令牌有效期（分钟）
 */
@ConfigurationProperties("app.jwt")
public record JwtProperties(String secret, long expireMinutes) {
}
