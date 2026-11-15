package com.careerpath.profile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * 个人档案接口测试。
 *
 * <p>重点覆盖三件事：未填写时是 200 而非 404、PUT 是整体替换而非合并、
 * 归属只认令牌（请求体里塞 userId 改不了别人的档案）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("从未填写档案时返回 200 且字段为 null，不返回 404")
    void getReturnsEmptyProfileNot404() throws Exception {
        String token = register("t_profile_empty");

        mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.school").doesNotExist())
                .andExpect(jsonPath("$.realName").doesNotExist());
    }

    @Test
    @DisplayName("PUT 保存后 GET 能读回，且未传字段被清空")
    void putThenGet() throws Exception {
        String token = register("t_profile_save");

        mockMvc.perform(put("/api/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"realName":"李雷","school":"示例大学","major":"计算机科学与技术",
                                 "grade":"大三","targetJobType":"Java 后端","targetCity":"杭州",
                                 "phone":"13800138000","email":"lilei@example.com"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.school").value("示例大学"))
                .andExpect(jsonPath("$.targetJobType").value("Java 后端"));

        mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.realName").value("李雷"))
                .andExpect(jsonPath("$.targetCity").value("杭州"));

        // PUT 是整体替换：这次只传 school，其余字段应被清空
        mockMvc.perform(put("/api/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"school\":\"另一所大学\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.school").value("另一所大学"));

        mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.school").value("另一所大学"))
                .andExpect(jsonPath("$.realName").doesNotExist());
    }

    @Test
    @DisplayName("手机号与邮箱格式不合法返回 422")
    void putRejectsInvalidContact() throws Exception {
        String token = register("t_profile_invalid");

        mockMvc.perform(put("/api/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"12345\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail.code").value("VALIDATION_ERROR"));

        mockMvc.perform(put("/api/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("请求体里伪造 userId 不会写到他人档案里")
    void userIdInBodyIsIgnored() throws Exception {
        String owner = register("t_profile_owner");
        String attacker = register("t_profile_attacker");

        mockMvc.perform(put("/api/profile")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"school\":\"原主的学校\"}"))
                .andExpect(status().isOk());

        // 档案主键取自令牌，请求体里多出来的字段会被 Jackson 忽略（record 没有该分量）
        mockMvc.perform(put("/api/profile")
                        .header("Authorization", "Bearer " + attacker)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":1,\"school\":\"攻击者的学校\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + owner))
                .andExpect(jsonPath("$.school").value("原主的学校"));

        mockMvc.perform(get("/api/profile").header("Authorization", "Bearer " + attacker))
                .andExpect(jsonPath("$.school").value("攻击者的学校"));
    }

    @Test
    @DisplayName("未登录访问档案接口返回 401")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail.code").value("UNAUTHORIZED"));
    }

    /**
     * 注册一个一次性账号并返回令牌。
     *
     * <p>用户名带 {@code t_} 前缀便于在库里认残留，再加短随机后缀——固定用户名一旦因为
     * 事务没来得及回滚（例如测试 JVM 被强杀）而残留，之后每次跑都会在注册这一步拿到
     * 409 USERNAME_TAKEN，表现为「整套测试莫名其妙全红」。
     */
    private String register(String prefix) throws Exception {
        String username = prefix + "_" + Long.toString(System.nanoTime() & 0xFFFFF, 36);
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                objectMapper.createObjectNode()
                                        .put("username", username)
                                        .put("password", "Passw0rd!"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("accessToken").asText();
    }
}
