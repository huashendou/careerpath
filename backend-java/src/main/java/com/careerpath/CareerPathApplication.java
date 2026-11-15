package com.careerpath;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 职航 CareerPath 主业务服务入口。
 *
 * <p>职责边界（AGENTS.md 第四节）：用户、公司、岗位、投递、技能、证据、任务、面试、报告、统计
 * 全部业务数据与鉴权都在这里；Python 分析服务只做解析与算分，不持有用户会话。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.careerpath.**.mapper")
public class CareerPathApplication {

    public static void main(String[] args) {
        SpringApplication.run(CareerPathApplication.class, args);
    }
}
