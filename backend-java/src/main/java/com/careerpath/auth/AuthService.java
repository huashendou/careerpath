package com.careerpath.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.careerpath.auth.dto.AuthResponse;
import com.careerpath.auth.dto.LoginRequest;
import com.careerpath.auth.dto.RegisterRequest;
import com.careerpath.common.ApiException;
import com.careerpath.common.ErrorCode;
import com.careerpath.security.AuthPrincipal;
import com.careerpath.security.JwtService;
import com.careerpath.user.User;
import com.careerpath.user.mapper.UserMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注册、登录与当前用户查询。
 *
 * <p>三条安全口径：
 * <ol>
 *   <li>登录失败不区分「用户不存在」与「密码错误」，都抛 INVALID_CREDENTIALS。
 *       否则用户名是否存在可被枚举，而这正是撞库的第一步。</li>
 *   <li>用户名重复用 409：注册必须能告诉用户「换个名字」，这个信息泄露是可接受的——
 *       注册表单本身就要即时反馈重名，这是产品需求而非漏洞。</li>
 *   <li>密码只以 BCrypt 哈希落库，任何响应体都不含哈希。</li>
 * </ol>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 首版只有学生角色。企业侧不做。 */
    private static final String DEFAULT_ROLE = "student";

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * 用户不存在时拿去比对的假哈希。
     *
     * <p>启动时现算而不是写死常量：写死的哈希一旦被人看出来是「假密码」，时序防护就白做了。
     */
    private final String dummyHash;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode("careerpath-timing-guard");
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = request.username().trim();
        if (findByUsername(username) != null) {
            throw new ApiException(ErrorCode.USERNAME_TAKEN);
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(DEFAULT_ROLE);
        // 显式写 UTC 时间戳，不用列的 DEFAULT CURRENT_TIMESTAMP：那个默认值取的是 MySQL
        // 服务器本地时间（UTC+8），而读取时按 UTC 解释，createdAt 会凭空多出 8 小时。
        // 库里的 DATETIME 一律当 UTC 存，这是全项目唯一的时间口径。
        user.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).withNano(0));
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            // 先查过，但两个请求同时注册同名时仍会撞到唯一索引；靠约束兜底而不是靠先查
            throw new ApiException(ErrorCode.USERNAME_TAKEN);
        }
        log.info("新用户注册 id={} username={}", user.getId(), user.getUsername());
        return issue(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = findByUsername(request.username().trim());
        // 用户不存在时也跑一次 matches，避免「存在则慢 60ms、不存在则秒回」的时序差
        String hash = user == null ? dummyHash : user.getPasswordHash();
        boolean matched = passwordEncoder.matches(request.password(), hash);
        if (user == null || !matched) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        return issue(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse.UserBrief me(AuthPrincipal principal) {
        User user = userMapper.selectById(principal.id());
        if (user == null) {
            // 令牌有效但账号已被删：按未登录处理，让前端回登录页，而不是抛 404 让前端卡住
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        return brief(user);
    }

    private AuthResponse issue(User user) {
        AuthPrincipal principal = new AuthPrincipal(user.getId(), user.getUsername(), user.getRole());
        return AuthResponse.bearer(jwtService.issue(principal), jwtService.expireSeconds(), brief(user));
    }

    private AuthResponse.UserBrief brief(User user) {
        return new AuthResponse.UserBrief(user.getId(), user.getUsername(), user.getRole(),
                toInstant(user.getCreatedAt()));
    }

    private User findByUsername(String username) {
        return userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
    }

    /** 库里存 UTC（JDBC 连接串带 serverTimezone=UTC），补上 Z 才能被前端正确解析。 */
    private Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
