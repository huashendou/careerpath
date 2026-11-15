package com.careerpath.application;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * 二期模块（投递清单 + 面试复盘）测试。
 *
 * <p>两件事必须成立：状态变更要留痕（status_log），面试记录能从投递记录带出公司/岗位——
 * 否则「面试复盘」会变成一份需要手填公司名的表单，用户不会填第三次。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Phase2ControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("从岗位加入投递清单：自动带出岗位名与公司，初始为 todo")
    void createApplicationFromJob() throws Exception {
        String token = register("t_app_create");
        long jobId = createJob(token);

        mockMvc.perform(post("/api/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobPositionId\":" + jobId + ",\"note\":\"内推\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("todo"))
                .andExpect(jsonPath("$.title").value("测试岗位"))
                .andExpect(jsonPath("$.companyName").value("示例科技"))
                .andExpect(jsonPath("$.statusLog.length()").value(0));
    }

    @Test
    @DisplayName("同一岗位重复投递返回 409")
    void duplicateApplicationRejected() throws Exception {
        String token = register("t_app_dup");
        long jobId = createJob(token);
        createApplication(token, jobId);

        mockMvc.perform(post("/api/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobPositionId\":" + jobId + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail.code").value("DUPLICATE_RESOURCE"));
    }

    @Test
    @DisplayName("状态切换写时间线，首次置 applied 会记 appliedAt")
    void statusChangeWritesLog() throws Exception {
        String token = register("t_app_status");
        long jobId = createJob(token);
        long appId = createApplication(token, jobId);

        mockMvc.perform(patch("/api/applications/" + appId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"applied\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("applied"))
                .andExpect(jsonPath("$.statusLog.length()").value(1))
                .andExpect(jsonPath("$.statusLog[0].from").value("todo"))
                .andExpect(jsonPath("$.statusLog[0].to").value("applied"))
                .andExpect(jsonPath("$.appliedAt").exists());
    }

    @Test
    @DisplayName("面试记录从投递记录带出公司岗位，并可改结果")
    void interviewInheritsApplication() throws Exception {
        String token = register("t_interview");
        long jobId = createJob(token);
        long appId = createApplication(token, jobId);

        String body = mockMvc.perform(post("/api/interviews")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"applicationId":%d,"interviewDate":"2026-11-20","round":"一面","format":"video",
                                 "questions":[{"question":"讲讲事务传播","selfRating":3}],
                                 "reflection":"八股要背","relatedSkills":["spring-boot"]}
                                """.formatted(appId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyName").value("示例科技"))
                .andExpect(jsonPath("$.jobTitle").value("测试岗位"))
                .andExpect(jsonPath("$.result").value("pending"))
                .andExpect(jsonPath("$.questions.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        long interviewId = objectMapper.readTree(body).get("id").asLong();

        mockMvc.perform(patch("/api/interviews/" + interviewId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"result\":\"pass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("pass"));

        mockMvc.perform(get("/api/interviews").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    private long createApplication(String token, long jobId) throws Exception {
        String body = mockMvc.perform(post("/api/applications")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobPositionId\":" + jobId + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private long createJob(String token) throws Exception {
        String body = mockMvc.perform(post("/api/jobs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"测试岗位\",\"companyName\":\"示例科技\",\"rawDescription\":\"熟悉 Java\"}"))
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
