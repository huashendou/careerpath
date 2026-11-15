-- 职航 CareerPath · 建库建表（15 张）
-- 依据：docs/P0-重构方案.md 第四节
-- 执行：mysql -u root -p < docs/schema.sql
-- 说明：模块 2（求职工作台）与模块 8（面试复盘）按 2026-11-15 范围裁决不做，
--       对应 company / application_* / interview_* / question_skill_relation 六张表已裁掉。

CREATE DATABASE IF NOT EXISTS career_path
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE career_path;

-- ---------------------------------------------------------------- 用户

CREATE TABLE IF NOT EXISTS `user` (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  username      VARCHAR(32)  NOT NULL COMMENT '登录名，唯一',
  password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt，绝不返回给前端',
  role          VARCHAR(16)  NOT NULL DEFAULT 'USER',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '账号';

CREATE TABLE IF NOT EXISTS user_profile (
  user_id         BIGINT      NOT NULL,
  real_name       VARCHAR(32)  DEFAULT NULL,
  school          VARCHAR(64)  DEFAULT NULL,
  major           VARCHAR(64)  DEFAULT NULL,
  grade           VARCHAR(16)  DEFAULT NULL COMMENT '如 2023级 / 大三',
  target_job_type VARCHAR(64)  DEFAULT NULL,
  target_city     VARCHAR(32)  DEFAULT NULL,
  phone           VARCHAR(20)  DEFAULT NULL COMMENT '最小必要，不向第三方上传',
  email           VARCHAR(64)  DEFAULT NULL,
  updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id),
  CONSTRAINT fk_profile_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '个人档案（与账号一对一）';

-- ---------------------------------------------------------------- 技能词典

CREATE TABLE IF NOT EXISTS skill_category (
  id          VARCHAR(32)  NOT NULL,
  name        VARCHAR(32)  NOT NULL,
  short       VARCHAR(16)  NOT NULL DEFAULT '' COMMENT '雷达图轴标签，如「基础」',
  parent_id   VARCHAR(32)  DEFAULT NULL COMMENT '预留二级分类，首版为空',
  description VARCHAR(255) DEFAULT NULL,
  why         VARCHAR(255) DEFAULT NULL COMMENT '为什么这个维度对实习重要',
  sort_order  INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_category_parent (parent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '技能分类（6 个能力维度）';

CREATE TABLE IF NOT EXISTS skill (
  id          VARCHAR(32)  NOT NULL COMMENT '业务 id，如 js-core；种子可幂等 upsert',
  category_id VARCHAR(32)  NOT NULL,
  name        VARCHAR(64)  NOT NULL,
  name_en     VARCHAR(64)  DEFAULT NULL,
  aliases     JSON         DEFAULT NULL COMMENT '同义词数组，仅解析时使用',
  description VARCHAR(255) DEFAULT NULL,
  sort_order  INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_skill_category (category_id),
  CONSTRAINT fk_skill_category FOREIGN KEY (category_id) REFERENCES skill_category (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '技能词典（61 项）';

CREATE TABLE IF NOT EXISTS skill_relation (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  from_skill_id  VARCHAR(32) NOT NULL,
  to_skill_id    VARCHAR(32) NOT NULL,
  relation_type  VARCHAR(16) NOT NULL COMMENT 'prerequisite / related / ecosystem',
  PRIMARY KEY (id),
  UNIQUE KEY uk_relation (from_skill_id, to_skill_id, relation_type),
  CONSTRAINT fk_relation_from FOREIGN KEY (from_skill_id) REFERENCES skill (id) ON DELETE CASCADE,
  CONSTRAINT fk_relation_to FOREIGN KEY (to_skill_id) REFERENCES skill (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '技能关系（Java↔Spring↔MyBatis 之类）';

-- ---------------------------------------------------------------- 岗位

CREATE TABLE IF NOT EXISTS job_position (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  user_id         BIGINT       DEFAULT NULL COMMENT '演示岗位为 NULL（is_demo=1），不属于任何用户',
  company_name    VARCHAR(64)  DEFAULT NULL COMMENT '模块 2 不做，公司只存文本不做实体',
  title           VARCHAR(64)  NOT NULL,
  salary_min      INT          DEFAULT NULL,
  salary_max      INT          DEFAULT NULL,
  city            VARCHAR(32)  DEFAULT NULL,
  source          VARCHAR(32)  DEFAULT NULL COMMENT 'boss直聘 / 手动粘贴 …',
  url             VARCHAR(512) DEFAULT NULL,
  raw_description MEDIUMTEXT   DEFAULT NULL COMMENT 'JD 原文，逐字保留，解析结果必须可回溯到这里',
  parse_status    VARCHAR(16)  NOT NULL DEFAULT 'pending' COMMENT 'pending / parsed / confirmed',
  is_demo         TINYINT(1)   NOT NULL DEFAULT 0,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_job_user (user_id),
  KEY idx_job_demo (is_demo),
  CONSTRAINT fk_job_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '具体岗位（用户录入的一条真实 JD）';

CREATE TABLE IF NOT EXISTS job_skill_requirement (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  job_position_id BIGINT      NOT NULL,
  skill_id        VARCHAR(32) NOT NULL,
  req_type        VARCHAR(16) NOT NULL COMMENT 'required / bonus，分开算分，不合并',
  weight          INT         NOT NULL DEFAULT 1,
  target_level    TINYINT     NOT NULL COMMENT '1–5',
  any_of          JSON        DEFAULT NULL COMMENT '可替代技能 id 数组，如 ["react"]；AGENTS 七要求 anyOf 取高者计分，故必须可持久化',
  evidence_hint   VARCHAR(255) DEFAULT NULL COMMENT 'JD 原文里对应的句子片段',
  source_span     VARCHAR(64) DEFAULT NULL COMMENT '原文偏移，形如 12:24',
  PRIMARY KEY (id),
  UNIQUE KEY uk_job_skill (job_position_id, skill_id),
  KEY idx_req_skill (skill_id),
  CONSTRAINT fk_req_job FOREIGN KEY (job_position_id) REFERENCES job_position (id) ON DELETE CASCADE,
  CONSTRAINT fk_req_skill FOREIGN KEY (skill_id) REFERENCES skill (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '岗位技能要求';

-- ---------------------------------------------------------------- 个人技能与证据

CREATE TABLE IF NOT EXISTS user_skill (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  user_id      BIGINT      NOT NULL,
  skill_id     VARCHAR(32) NOT NULL,
  level        TINYINT     NOT NULL DEFAULT 0 COMMENT '0–5，行为锚定，不是形容词自评',
  learned_at   DATE        DEFAULT NULL,
  last_used_at DATE        DEFAULT NULL,
  source       VARCHAR(16) NOT NULL DEFAULT 'self' COMMENT 'self / parsed',
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_skill (user_id, skill_id),
  CONSTRAINT fk_us_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
  CONSTRAINT fk_us_skill FOREIGN KEY (skill_id) REFERENCES skill (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '个人技能掌握度';

CREATE TABLE IF NOT EXISTS skill_evidence (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  user_id       BIGINT       NOT NULL,
  evidence_type VARCHAR(16)  NOT NULL COMMENT 'course / project / repo / doc / screenshot / certificate',
  title         VARCHAR(128) NOT NULL,
  url           VARCHAR(512) DEFAULT NULL,
  description   TEXT         DEFAULT NULL,
  completeness  TINYINT      NOT NULL DEFAULT 0 COMMENT '材料完整度 0–100',
  verify_status VARCHAR(16)  NOT NULL DEFAULT 'self' COMMENT 'self / reviewed',
  submitted_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_evidence_user (user_id),
  CONSTRAINT fk_evidence_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '技能证据';

CREATE TABLE IF NOT EXISTS evidence_skill_relation (
  id                BIGINT      NOT NULL AUTO_INCREMENT,
  evidence_id       BIGINT      NOT NULL,
  skill_id          VARCHAR(32) NOT NULL,
  relevance         TINYINT     NOT NULL DEFAULT 0 COMMENT '该证据对这项技能的相关度 0–100',
  contribution_note VARCHAR(255) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_evidence_skill (evidence_id, skill_id),
  CONSTRAINT fk_es_evidence FOREIGN KEY (evidence_id) REFERENCES skill_evidence (id) ON DELETE CASCADE,
  CONSTRAINT fk_es_skill FOREIGN KEY (skill_id) REFERENCES skill (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '证据与技能的多对多关联';

CREATE TABLE IF NOT EXISTS user_project (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  user_id     BIGINT       NOT NULL,
  name        VARCHAR(128) NOT NULL,
  role        VARCHAR(64)  DEFAULT NULL,
  tech_stack  JSON         DEFAULT NULL COMMENT '技术栈字符串数组',
  description TEXT         DEFAULT NULL,
  repo_url    VARCHAR(512) DEFAULT NULL,
  start_date  DATE         DEFAULT NULL,
  end_date    DATE         DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_project_user (user_id),
  CONSTRAINT fk_project_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '个人项目（证据的项目级来源）';

CREATE TABLE IF NOT EXISTS project_skill_relation (
  id                BIGINT      NOT NULL AUTO_INCREMENT,
  project_id        BIGINT      NOT NULL,
  skill_id          VARCHAR(32) NOT NULL,
  contribution_note VARCHAR(255) DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_project_skill (project_id, skill_id),
  CONSTRAINT fk_ps_project FOREIGN KEY (project_id) REFERENCES user_project (id) ON DELETE CASCADE,
  CONSTRAINT fk_ps_skill FOREIGN KEY (skill_id) REFERENCES skill (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '项目与技能的关联';

-- ---------------------------------------------------------------- 学习任务

CREATE TABLE IF NOT EXISTS learning_task (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  user_id      BIGINT       NOT NULL,
  skill_id     VARCHAR(32)  DEFAULT NULL COMMENT '来自差距的任务会带上技能',
  source       VARCHAR(16)  NOT NULL DEFAULT 'gap' COMMENT 'gap / manual',
  title        VARCHAR(128) NOT NULL,
  priority     INT          NOT NULL DEFAULT 0 COMMENT '= weight × gap，越大越先做',
  due_date     DATE         DEFAULT NULL,
  status       VARCHAR(16)  NOT NULL DEFAULT 'todo' COMMENT 'todo / doing / done / paused',
  completed_at DATETIME     DEFAULT NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_task_user_status (user_id, status),
  CONSTRAINT fk_task_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
  CONSTRAINT fk_task_skill FOREIGN KEY (skill_id) REFERENCES skill (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '学习任务';

-- ---------------------------------------------------------------- 匹配结果（快照）

CREATE TABLE IF NOT EXISTS skill_match_result (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  user_id            BIGINT       NOT NULL,
  job_position_id    BIGINT       DEFAULT NULL,
  required_score     DECIMAL(6, 4) DEFAULT NULL COMMENT '无法算分时为 NULL，不是 0',
  bonus_score        DECIMAL(6, 4) DEFAULT NULL,
  evidence_coverage  DECIMAL(6, 2) DEFAULT NULL COMMENT '证据覆盖面，独立展示，不进 required_score',
  algorithm_version  VARCHAR(32)  NOT NULL,
  detail             JSON         DEFAULT NULL COMMENT '算分入参与完整结果快照，便于复查',
  created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_result_user (user_id, created_at),
  CONSTRAINT fk_result_user FOREIGN KEY (user_id) REFERENCES `user` (id) ON DELETE CASCADE,
  CONSTRAINT fk_result_job FOREIGN KEY (job_position_id) REFERENCES job_position (id) ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '匹配结果快照（岗位改名或删除后仍可读）';

CREATE TABLE IF NOT EXISTS skill_gap_report (
  id              BIGINT NOT NULL AUTO_INCREMENT,
  match_result_id BIGINT NOT NULL,
  gap_items       JSON   DEFAULT NULL,
  top_priorities  JSON   DEFAULT NULL,
  explanation     JSON   DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_gap_result (match_result_id),
  CONSTRAINT fk_gap_result FOREIGN KEY (match_result_id) REFERENCES skill_match_result (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '差距报告（与匹配结果一对一）';
