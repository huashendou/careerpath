package com.careerpath.user;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 个人档案，与账号一对一（主键就是 user_id）。
 *
 * <p>与 User 分开：登录只需要账号表，档案字段全是可空的，合并会让人以为「注册时必须填学校专业」。
 *
 * <p>字段按最小必要收集：不存身份证、住址、紧急联系人；手机号与邮箱仅本人回看，不上传第三方。
 */
@TableName("user_profile")
public class UserProfile {

    @TableId(type = IdType.INPUT)
    private Long userId;
    private String realName;
    private String school;
    private String major;
    private String grade;
    private String targetJobType;
    private String targetCity;
    private String phone;
    private String email;
    private LocalDateTime updatedAt;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getSchool() {
        return school;
    }

    public void setSchool(String school) {
        this.school = school;
    }

    public String getMajor() {
        return major;
    }

    public void setMajor(String major) {
        this.major = major;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public String getTargetJobType() {
        return targetJobType;
    }

    public void setTargetJobType(String targetJobType) {
        this.targetJobType = targetJobType;
    }

    public String getTargetCity() {
        return targetCity;
    }

    public void setTargetCity(String targetCity) {
        this.targetCity = targetCity;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
