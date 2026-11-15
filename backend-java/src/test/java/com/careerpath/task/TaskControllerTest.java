package com.careerpath.task;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * 学习任务测试。
 *
 * <p>「完成任务 → 更新技能等级」是闭环的最后一环。没有岗位引用该技能时
 * {@code recalculated} 必须是 false：它表示「真的重算过」，不是「大概可以重算」。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("手动任务：创建 201、列表能查到、可按状态过滤")
    void createAndFilter() throws Exception {
        String token = register("t_task_create");

        mockMvc.perform(post("/api/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"整理项目 README\",\"source\":\"manual\",\"priority\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("todo"))
                .andExpect(jsonPath("$.skillId").doesNotExist());

        mockMvc.perform(get("/api/tasks").param("status", "todo").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));

        mockMvc.perform(get("/api/tasks").param("status", "done").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @DisplayName("PATCH 置 done 会写 completed_at，改回 todo 会清空")
    void patchStatus() throws Exception {
        String token = register("t_task_patch");
        long id = createTask(token);

        mockMvc.perform(patch("/api/tasks/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"done\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.completedAt").exists());

        mockMvc.perform(patch("/api/tasks/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"todo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedAt").doesNotExist());
    }

    @Test
    @DisplayName("完成任务时更新技能等级；没有岗位引用该技能则不重算")
    void completeUpdatesLevel() throws Exception {
        String token = register("t_task_complete");
        long id = createTaskWithSkill(token, "java-basic");

        mockMvc.perform(post("/api/tasks/" + id + "/complete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"updateSkillLevel\":true,\"level\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"))
                .andExpect(jsonPath("$.recalculated").value(false))
                .andExpect(jsonPath("$.matchedJobId").doesNotExist());

        mockMvc.perform(get("/api/user-skills").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].skillId").value("java-basic"))
                .andExpect(jsonPath("$.items[0].level").value(4));
    }

    @Test
    @DisplayName("别人的任务读不到、改不了、删不掉")
    void taskIsolation() throws Exception {
        String owner = register("t_task_owner");
        String attacker = register("t_task_attacker");
        long id = createTask(owner);

        mockMvc.perform(patch("/api/tasks/" + id)
                        .header("Authorization", "Bearer " + attacker)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"done\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/tasks/" + id).header("Authorization", "Bearer " + attacker))
                .andExpect(status().isNotFound());
    }

    private long createTask(String token) throws Exception {
        String body = mockMvc.perform(post("/api/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"测试任务\",\"source\":\"manual\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private long createTaskWithSkill(String token, String skillId) throws Exception {
        String body = mockMvc.perform(post("/api/tasks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"补齐 Java 基础\",\"source\":\"manual\",\"skillId\":\"" + skillId + "\"}"))
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
