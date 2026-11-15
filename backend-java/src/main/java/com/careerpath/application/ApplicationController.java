package com.careerpath.application;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 投递清单（二期模块 2，契约 c3.5）。 */
@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private final ApplicationService service;

    public ApplicationController(ApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public Items<ApplicationDtos.View> list(@AuthenticationPrincipal AuthPrincipal principal,
                                            @RequestParam(required = false) String status) {
        return Items.of(service.list(Principals.id(principal), status));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApplicationDtos.View create(@AuthenticationPrincipal AuthPrincipal principal,
                                       @RequestBody ApplicationDtos.Create request) {
        return service.create(Principals.id(principal), request);
    }

    @PatchMapping("/{id}")
    public ApplicationDtos.View patch(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable long id,
                                      @RequestBody ApplicationDtos.Patch request) {
        return service.patch(Principals.id(principal), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthPrincipal principal, @PathVariable long id) {
        service.delete(Principals.id(principal), id);
    }
}
