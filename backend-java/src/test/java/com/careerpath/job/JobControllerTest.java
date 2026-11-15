package com.careerpath.job;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * 岗位接口测试。
 *
 * <p>解析接口要连 Python，不在单元测试里跑；这里覆盖的是**归属、校验与覆盖语义**——
 * 这三件事最容易在重构中被破坏，而且坏了不会立刻报错，只会悄悄写坏数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class JobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("新建岗位返回 201，并可按 id 读回 JD 原文")
    void createThenGet() throws Exception {
        String token = register("t_job_create");

        String body = mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Java 后端开发实习生","companyName":"示例科技","city":"杭州",
                                 "rawDescription":"熟悉 Java 与 Spring Boot"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Java 后端开发实习生"))
                .andExpect(jsonPath("$.parseStatus").value("pending"))
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(body).get("id").asLong();
        mockMvc.perform(get("/api/jobs/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawDescription").value("熟悉 Java 与 Spring Boot"));
    }

    @Test
    @DisplayName("保存技能要求：重复技能返回 409，未知技能返回 422")
    void requirementsValidation() throws Exception {
        String token = register("t_job_req");
        long id = createJob(token);

        mockMvc.perform(put("/api/jobs/" + id + "/requirements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"skillId":"java-basic","reqType":"required","weight":2,"targetLevel":3,"anyOf":[]},
                                          {"skillId":"java-basic","reqType":"bonus","weight":1,"targetLevel":2,"anyOf":[]}]}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail.code").value("DUPLICATE_RESOURCE"));

        mockMvc.perform(put("/api/jobs/" + id + "/requirements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"skillId":"not-a-skill","reqType":"required","weight":1,"targetLevel":3,"anyOf":[]}]}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail.code").value("UNKNOWN_SKILL"));
    }

    @Test
    @DisplayName("保存要求是整体覆盖：第二次提交后只剩一条")
    void requirementsReplace() throws Exception {
        String token = register("t_job_replace");
        long id = createJob(token);

        mockMvc.perform(put("/api/jobs/" + id + "/requirements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"skillId":"java-basic","reqType":"required","weight":2,"targetLevel":3,"anyOf":[]},
                                          {"skillId":"spring-boot","reqType":"required","weight":3,"targetLevel":3,"anyOf":[]}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        mockMvc.perform(put("/api/jobs/" + id + "/requirements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"skillId":"redis","reqType":"bonus","weight":1,"targetLevel":2,"anyOf":[]}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].skillId").value("redis"));

        mockMvc.perform(get("/api/jobs/" + id).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.parseStatus").value("confirmed"));
    }

    @Test
    @DisplayName("别人的岗位一律 404，不泄露存在性")
    void cannotReadOthersJob() throws Exception {
        String owner = register("t_job_owner");
        String attacker = register("t_job_attacker");
        long id = createJob(owner);

        mockMvc.perform(get("/api/jobs/" + id).header("Authorization", "Bearer " + attacker))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail.code").value("JOB_NOT_FOUND"));

        mockMvc.perform(delete("/api/jobs/" + id).header("Authorization", "Bearer " + attacker))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("空标题返回 422")
    void titleRequired() throws Exception {
        String token = register("t_job_title");
        mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail.code").value("VALIDATION_ERROR"));
    }

    private long createJob(String token) throws Exception {
        String body = mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"测试岗位\",\"rawDescription\":\"熟悉 Java\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
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
