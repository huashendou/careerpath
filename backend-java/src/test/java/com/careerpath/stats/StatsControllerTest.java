package com.careerpath.stats;

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
 * 概览统计测试。
 *
 * <p>重点只有一个：{@code profileCompleted} 的口径。曾经它是「user_profile 有行就算完成」，
 * PUT 是整体替换，用户只填手机号也会落一行，概览页就会写「已完善 · 学校专业已记录」。
 * 现在按学校与专业都非空判定，这组用例把三种情形钉住：空档案、只填联系方式、学校专业齐全。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("新账号概览：计数全 0、lastMatch 为 null、档案未完善")
    void overviewIsEmptyForNewUser() throws Exception {
        String token = register("t_stats_empty");

        mockMvc.perform(get("/api/stats/overview").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileCompleted").value(false))
                .andExpect(jsonPath("$.jobCount").value(0))
                .andExpect(jsonPath("$.taskOpenCount").value(0))
                .andExpect(jsonPath("$.evidenceMissingCount").value(0))
                .andExpect(jsonPath("$.lastMatch").doesNotExist())
                .andExpect(jsonPath("$.generatedAt").exists());
    }

    @Test
    @DisplayName("只填联系方式不算档案完善")
    void phoneOnlyIsNotCompleted() throws Exception {
        String token = register("t_stats_phone");

        mockMvc.perform(put("/api/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800138000\",\"email\":\"a@example.com\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/stats/overview").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileCompleted").value(false));
    }

    @Test
    @DisplayName("学校与专业都有才算出档案完善")
    void schoolAndMajorMeansCompleted() throws Exception {
        String token = register("t_stats_full");

        mockMvc.perform(put("/api/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"school\":\"示例大学\",\"major\":\"计算机科学与技术\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/stats/overview").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileCompleted").value(true));
    }

    @Test
    @DisplayName("未登录访问概览返回 401")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/stats/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail.code").value("UNAUTHORIZED"));
    }

    /**
     * 注册一个一次性账号并返回令牌。用户名带 {@code t_} 前缀 + 短随机后缀，
     * 避免固定用户名在上次事务未回滚时让整套测试在注册这步拿 409。
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
