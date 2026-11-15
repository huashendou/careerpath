package com.careerpath.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.careerpath.user.User;
import org.apache.ibatis.annotations.Mapper;

/** 账号表访问。业务查询一律走 LambdaQueryWrapper，不在这里拼 SQL。 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
