package com.careerpath.task;

import com.careerpath.common.Items;
import com.careerpath.common.Principals;
import com.careerpath.security.AuthPrincipal;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 学习任务接口（契约 3.6）。 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService service;
    private final ObjectMapper json;

    public TaskController(TaskService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @GetMapping
    public Items<TaskDtos.TaskView> list(@AuthenticationPrincipal AuthPrincipal principal,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String source) {
        return Items.of(service.list(Principals.id(principal), status, source));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDtos.TaskView create(@AuthenticationPrincipal AuthPrincipal principal,
                                    @RequestBody TaskDtos.Create request) {
        return service.create(Principals.id(principal), request);
    }

    @PostMapping("/from-gap/{jobId}")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> fromGap(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable long jobId,
                                       @RequestBody(required = false) TaskDtos.FromGap request) {
        Integer topN = request == null ? null : request.topN();
        return service.fromGap(Principals.id(principal), jobId, topN);
    }

    @PatchMapping("/{id}")
    public TaskDtos.TaskView patch(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable long id,
                                   @RequestBody TaskDtos.Patch request) {
        return service.patch(Principals.id(principal), id, request);
    }

    /**
     * 完成 + 可选更新技能等级 + 重算。
     *
     * <p>响应是「任务字段 + recalculated + matchedJobId」的平铺结构：前端拿到的就是一个任务，
     * 多两个字段告诉它「分数已经重算过，可以刷新看新结果」。
     */
    @PostMapping("/{id}/complete")
    public Map<String, Object> complete(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable long id,
                                        @RequestBody(required = false) TaskDtos.Complete request) {
        TaskService.CompleteResult result = service.complete(Principals.id(principal), id,
                request == null ? new TaskDtos.Complete(null, null) : request);
        Map<String, Object> body = new LinkedHashMap<>(
                json.convertValue(result.task(), new TypeReference<Map<String, Object>>() {
                }));
        body.put("recalculated", result.recalculated());
        body.put("matchedJobId", result.matchedJobId());
        return body;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable long id) {
        service.delete(Principals.id(principal), id);
    }
}
