package com.careerpath.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 种子导入配置。
 *
 * @param enabled    是否在启动时灌库。生产建议关掉，改由发布流水线执行
 * @param skillsFile 技能词典种子文件（data/skills.json），与 Python 分析服务读的是同一份
 */
@ConfigurationProperties("app.seed")
public record SeedProperties(boolean enabled, String skillsFile) {
}
