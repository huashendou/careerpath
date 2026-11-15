package com.careerpath.portfolio;

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
 * 技能自评与证据链测试。
 *
 * <p>核心口径：自评是**批量 upsert**（未提交的不动）、等级 clamp 到 0–5、
 * 证据只影响覆盖面不影响等级。这三条一旦被改成「全量替换」或「证据加权」，
 * 匹配分数的含义就变了，而接口看起来仍然正常。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PortfolioControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("批量 upsert：第二次只提交一项，第一项保持不变")
    void upsertKeepsUntouchedSkills() throws Exception {
        String token = register("t_pf_upsert");

        mockMvc.perform(put("/api/user-skills")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"skillId\":\"java-basic\",\"level\":3},{\"skillId\":\"spring-boot\",\"level\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        mockMvc.perform(put("/api/user-skills")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"skillId\":\"java-basic\",\"level\":4}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    @DisplayName("等级越界按 0–5 截断，不报错")
    void levelClamped() throws Exception {
        String token = register("t_pf_clamp");
        mockMvc.perform(put("/api/user-skills")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"skillId\":\"java-basic\",\"level\":9}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].level").value(5));
    }

    @Test
    @DisplayName("未知技能返回 422 UNKNOWN_SKILL")
    void unknownSkillRejected() throws Exception {
        String token = register("t_pf_unknown");
        mockMvc.perform(put("/api/user-skills")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"skillId\":\"ghost-skill\",\"level\":3}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail.code").value("UNKNOWN_SKILL"));
    }

    @Test
    @DisplayName("创建证据并关联技能，覆盖技能后旧关联被替换")
    void evidenceSkillsReplaced() throws Exception {
        String token = register("t_pf_evidence");

        String body = mockMvc.perform(post("/api/evidence")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"evidenceType":"repo","title":"课程项目","completeness":70,
                                 "skills":[{"skillId":"java-basic","relevance":80},{"skillId":"spring-boot","relevance":70}]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.skills.length()").value(2))
                .andExpect(jsonPath("$.verifyStatus").value("unverified"))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(post("/api/evidence/" + id + "/skills")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"skillId\":\"redis\",\"relevance\":60}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].skillId").value("redis"));
    }

    @Test
    @DisplayName("没有岗位时覆盖面为 null（不适用），不是 0%")
    void coverageIsNullWithoutJobs() throws Exception {
        String token = register("t_pf_coverage");
        mockMvc.perform(get("/api/evidence/coverage").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredTotal").value(0))
                .andExpect(jsonPath("$.coveredCount").value(0))
                .andExpect(jsonPath("$.coverage").doesNotExist());
    }

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
