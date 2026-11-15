package com.careerpath.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证接口测试。
 *
 * <p>用 MockMvc 而不是 TestRestTemplate：MockMvc 在测试线程内执行，@Transactional 能真正把写操作
 * 回滚，不会在 career_path 里留测试账号。换成随机端口的真实 HTTP 请求后，请求跑在容器线程、
 * 独立连接上，回滚就不生效了——这是本文件最容易踩的坑。
 *
 * <p>仍然连真实 MySQL：user 表是保留字、password_hash 长度、唯一索引行为都是「换 H2 就测不出来」
 * 的东西，用 H2 兼容模式跑绿了反而更危险。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthControllerTest {

    private static final String PASSWORD = "Passw0rd!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("注册成功返回 201 与可用令牌")
    void registerReturns201AndToken() throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("t_register_ok", PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("bearer"))
                .andExpect(jsonPath("$.expiresIn").value(86400))
                .andExpect(jsonPath("$.user.username").value("t_register_ok"))
                .andExpect(jsonPath("$.user.role").value("student"))
                // 密码哈希绝不能出现在响应体里
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String token = objectMapper.readTree(body).get("accessToken").asText();
        assertThat(token).isNotBlank();

        // 拿刚签发的令牌反问 /me，证明令牌真的能被过滤器解析，而不只是「字符串非空」
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("t_register_ok"));
    }

    @Test
    @DisplayName("用户名重复返回 409 USERNAME_TAKEN")
    void registerRejectsDuplicateUsername() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("t_dup_user", PASSWORD)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("t_dup_user", PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail.code").value("USERNAME_TAKEN"));
    }

    @Test
    @DisplayName("用户名或密码格式不合法返回 422")
    void registerRejectsInvalidPayload() throws Exception {
        // 中文用户名：允许会让全角/半角同形字混进来，登录名混淆
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("张三", PASSWORD)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail.code").value("VALIDATION_ERROR"));

        // 7 位密码，低于 8 位下限
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("t_short_pwd", "1234567")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("登录成功返回 200，密码错误与用户不存在同为 401")
    void loginBehaviour() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("t_login_ok", PASSWORD)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("t_login_ok", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.username").value("t_login_ok"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("t_login_ok", "WrongPass1")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail.code").value("INVALID_CREDENTIALS"));

        // 用户不存在必须与密码错误返回同一个码，否则用户名可被枚举
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("no_such_user_xyz", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("未带令牌访问受保护接口返回 401")
    void protectedEndpointRequiresToken() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail.code").value("UNAUTHORIZED"));
    }

    private String json(String username, String password) throws Exception {
        JsonNode node = objectMapper.createObjectNode()
                .put("username", username)
                .put("password", password);
        return objectMapper.writeValueAsString(node);
    }
}
