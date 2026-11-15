package com.careerpath.profile.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 档案整体替换入参（PUT 语义：没传的字段视为清空，而不是「保持不变」）。
 *
 * <p>允许全空提交：档案本来就是可选的，强行要求「学校必填」会把一个只想看看匹配度的用户挡在门外。
 *
 * <p>手机号只做格式校验，不做短信验证——首版没有发短信的预算与合规前提，
 * 校验格式只是防手滑，不代表号码已核验。
 */
public record ProfileUpdateRequest(
        @Size(max = 32, message = "姓名不能超过 32 字") String realName,
        @Size(max = 64, message = "学校不能超过 64 字") String school,
        @Size(max = 64, message = "专业不能超过 64 字") String major,
        @Size(max = 16, message = "年级不能超过 16 字") String grade,
        @Size(max = 64, message = "目标岗位不能超过 64 字") String targetJobType,
        @Size(max = 32, message = "目标城市不能超过 32 字") String targetCity,

        @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @Email(message = "邮箱格式不正确")
        @Size(max = 64, message = "邮箱不能超过 64 字")
        String email
) {
}
