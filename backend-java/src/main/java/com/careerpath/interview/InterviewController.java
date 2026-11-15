package com.careerpath.interview;

import com.careerpath.common.Items;
import com.careerpath.common.Principals;
import com.careerpath.security.AuthPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 面试复盘（二期模块 8，契约 c3.5）。 */
@RestController
@RequestMapping("/api/interviews")
public class InterviewController {

    private final InterviewService service;

    public InterviewController(InterviewService service) {
        this.service = service;
    }

    @GetMapping
    public Items<InterviewDtos.View> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return Items.of(service.list(Principals.id(principal)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InterviewDtos.View create(@AuthenticationPrincipal AuthPrincipal principal,
                                     @RequestBody InterviewDtos.Create request) {
        return service.create(Principals.id(principal), request);
    }

    @PatchMapping("/{id}")
    public InterviewDtos.View patch(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable long id,
                                    @RequestBody InterviewDtos.Patch request) {
        return service.patch(Principals.id(principal), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable long id) {
        service.delete(Principals.id(principal), id);
    }
}
