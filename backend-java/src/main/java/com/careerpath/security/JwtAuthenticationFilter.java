package com.careerpath.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 从 {@code Authorization: Bearer <token>} 中恢复登录态。
 *
 * <p>令牌无效时**不直接返回 401**，而是留空 SecurityContext 继续往下走：
 * 这样白名单接口（如 {@code /api/health}）带着过期令牌访问仍然可用，
 * 需要鉴权的接口则由 SecurityConfig 统一拒绝，401 只在一处产生。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)) {
            Optional<AuthPrincipal> principal = jwtService.parse(header.substring(PREFIX.length()).trim());
            principal.ifPresent(p -> {
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + p.role().toUpperCase()));
                var authentication = new UsernamePasswordAuthenticationToken(p, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }
        filterChain.doFilter(request, response);
    }
}
