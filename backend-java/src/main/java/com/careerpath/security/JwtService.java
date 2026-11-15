package com.careerpath.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * JWT 签发与校验（HS256）。
 *
 * <p>两条硬规则：
 * <ol>
 *   <li>任何解析失败都返回 {@link Optional#empty()}，由过滤器统一判 401——不区分「过期」与「伪造」，
 *       否则等于告诉攻击者哪些令牌曾经有效。</li>
 *   <li>令牌里只放 id / username / role，不放学校、手机号这类个人信息：JWT 是明文可解码的。</li>
 * </ol>
 */
@Component
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final long expireMinutes;

    public JwtService(JwtProperties properties) {
        String secret = properties.secret() == null ? "" : properties.secret();
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            // 起不来比起到一半再报错好：密钥太短会让 HS256 直接抛 WeakKeyException
            throw new IllegalStateException(
                    "app.jwt.secret 至少需要 " + MIN_SECRET_BYTES + " 字节，当前 "
                            + secret.getBytes(StandardCharsets.UTF_8).length + " 字节");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireMinutes = properties.expireMinutes() > 0 ? properties.expireMinutes() : 1440;
    }

    public long expireSeconds() {
        return expireMinutes * 60;
    }

    public String issue(AuthPrincipal principal) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(principal.id()))
                .claim("username", principal.username())
                .claim("role", principal.role())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireSeconds())))
                .signWith(key)
                .compact();
    }

    public Optional<AuthPrincipal> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long id = Long.valueOf(claims.getSubject());
            String username = claims.get("username", String.class);
            String role = claims.get("role", String.class);
            return Optional.of(new AuthPrincipal(id, username, role == null ? "student" : role));
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("令牌校验失败：{}", ex.getMessage());
            return Optional.empty();
        }
    }
}
