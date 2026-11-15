package com.careerpath.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.careerpath.user.UserProfile;
import org.apache.ibatis.annotations.Mapper;

/** 个人档案表访问。 */
@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {
}
