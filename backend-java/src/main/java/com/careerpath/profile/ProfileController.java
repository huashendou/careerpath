package com.careerpath.profile;

import com.careerpath.common.ApiException;
import com.careerpath.common.ErrorCode;
import com.careerpath.profile.dto.ProfileResponse;
import com.careerpath.profile.dto.ProfileUpdateRequest;
import com.careerpath.security.AuthPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人档案接口。契约 docs/协作看板.md 三节 3.2。
 *
 * <p>只有「自己的档案」这一种资源，所以路径里不带 userId：带上反而要写一遍
 * 「id 与令牌是否一致」的校验，而那是唯一会写错的地方。
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public ProfileResponse get(@AuthenticationPrincipal AuthPrincipal principal) {
        return profileService.get(requirePrincipal(principal).id());
    }

    @PutMapping
    public ProfileResponse save(@AuthenticationPrincipal AuthPrincipal principal,
                                @Valid @RequestBody ProfileUpdateRequest request) {
        return profileService.save(requirePrincipal(principal).id(), request);
    }

    private AuthPrincipal requirePrincipal(AuthPrincipal principal) {
        if (principal == null) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        return principal;
    }
}
