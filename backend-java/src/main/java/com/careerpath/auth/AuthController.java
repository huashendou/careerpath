package com.careerpath.auth;

import com.careerpath.auth.dto.AuthResponse;
import com.careerpath.auth.dto.LoginRequest;
import com.careerpath.auth.dto.RegisterRequest;
import com.careerpath.common.ApiException;
import com.careerpath.common.ErrorCode;
import com.careerpath.security.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。契约 docs/协作看板.md 三节 3.1。
 *
 * <p>register/login 在 SecurityConfig 中列入白名单，其余接口未带令牌一律由安全链拦下；
 * 传进来的 principal 若为 null，说明安全配置漏了某条路径，抛 401 让它显形。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public AuthResponse.UserBrief me(@AuthenticationPrincipal AuthPrincipal principal) {
        return authService.me(requirePrincipal(principal));
    }

    /** 安全链漏配时的兜底：宁可 401，也不要 NPE 变成 500。 */
    private AuthPrincipal requirePrincipal(AuthPrincipal principal) {
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        return principal;
    }
}
