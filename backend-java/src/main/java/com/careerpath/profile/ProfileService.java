package com.careerpath.profile;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.careerpath.profile.dto.ProfileResponse;
import com.careerpath.profile.dto.ProfileUpdateRequest;
import com.careerpath.user.UserProfile;
import com.careerpath.user.mapper.UserProfileMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 个人档案读写。
 *
 * <p>UPSERT 而不是「注册时建空档案」：注册只写账号表，档案在首次保存时才落库。
 *
 * <p>越权防护：user_id 一律取自令牌，不接收请求体里的 userId。这是整条链上唯一的归属判定点，
 * 漏了它，改个 id 就能改别人的档案。
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final UserProfileMapper profileMapper;

    public ProfileService(UserProfileMapper profileMapper) {
        this.profileMapper = profileMapper;
    }

    @Transactional(readOnly = true)
    public ProfileResponse get(Long userId) {
        return ProfileResponse.of(profileMapper.selectById(userId));
    }

    @Transactional
    public ProfileResponse save(Long userId, ProfileUpdateRequest request) {
        UserProfile existing = profileMapper.selectById(userId);
        boolean created = existing == null;
        UserProfile entity = created ? new UserProfile() : existing;
        entity.setUserId(userId);

        // PUT 是整体替换：请求里没给的字段一律写成 null，不做「保留旧值」的合并
        entity.setRealName(trimToNull(request.realName()));
        entity.setSchool(trimToNull(request.school()));
        entity.setMajor(trimToNull(request.major()));
        entity.setGrade(trimToNull(request.grade()));
        entity.setTargetJobType(trimToNull(request.targetJobType()));
        entity.setTargetCity(trimToNull(request.targetCity()));
        entity.setPhone(trimToNull(request.phone()));
        entity.setEmail(trimToNull(request.email()));
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));

        if (created) {
            profileMapper.insert(entity);
        } else {
            // 不能用 updateById：MyBatis-Plus 默认 FieldStrategy.NOT_NULL，null 字段不进 SET 子句，
            // 「只传 school」时 realName 会保留旧值，PUT 悄悄退化成 PATCH。
            // 这里显式 set 每一个字段，null 才会真的写成 NULL。
            profileMapper.update(null, Wrappers.<UserProfile>lambdaUpdate()
                    .eq(UserProfile::getUserId, userId)
                    .set(UserProfile::getRealName, entity.getRealName())
                    .set(UserProfile::getSchool, entity.getSchool())
                    .set(UserProfile::getMajor, entity.getMajor())
                    .set(UserProfile::getGrade, entity.getGrade())
                    .set(UserProfile::getTargetJobType, entity.getTargetJobType())
                    .set(UserProfile::getTargetCity, entity.getTargetCity())
                    .set(UserProfile::getPhone, entity.getPhone())
                    .set(UserProfile::getEmail, entity.getEmail())
                    .set(UserProfile::getUpdatedAt, entity.getUpdatedAt()));
        }
        log.debug("档案{} userId={}", created ? "新建" : "更新", userId);
        return ProfileResponse.of(entity);
    }

    /** 空串与纯空格统一存 null：避免「学校填了个空格」在匹配时被当成有效信息。 */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
