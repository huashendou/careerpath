# 职航 CareerPath

> 面向在校大学生的岗位能力画像与智能求职适配平台

学生录入目标实习岗位 → 系统解析 JD 得到结构化技能要求 → 对照个人技能档案与项目证据 → 输出可解释的匹配度与能力差距 → 生成学习任务与面试复盘，形成求职闭环。

## 核心痛点

- 岗位信息分散，JD 黑话难懂
- 不知道自己差在哪、差多少
- 技能缺乏实践证明，学习与求职脱节

## 技术栈

| 层次 | 技术选型 |
|---|---|
| 前端 | Vue 3 + Vite + Element Plus + Pinia + Axios + ECharts |
| 业务后端 | Java 25 + Spring Boot 3.5.16 + MyBatis-Plus 3.5.18.1 + Spring Security + JWT |
| 智能分析服务 | Python + FastAPI + Pandas + NumPy（内部服务，不对外） |
| 数据库 | MySQL 8（`utf8mb4`），17 张表 |

## 架构

```
浏览器 ──► 前端 Vue 3 (:5173)
              │  /api 代理
              ▼
        Java 业务后端 (:8080)  ── 鉴权、业务数据、全部落库
              │  /internal + X-Internal-Token
              ▼
        Python 分析服务 (:8000) ── 解析、标准化、打分、差距、聚合
              │
              ▼
            MySQL (career_path)
```

设计原则：

- 前端只访问 Java，**不直连** Python
- Python 只做计算，**不持有**用户会话
- 分析结果由 Java 落库，保存原始 JD 与 `algorithm_version`，便于复查
- 模块化单体 + 一个独立分析服务，不拆微服务

## 目录结构

```
.
├── AGENTS.md                  # 项目总提示词（目标、边界、约定）
├── data/                      # 种子数据：61 技能 / 6 类 / 3 个演示岗位
├── docs/                      # 重构方案、协作看板、数据模型、评审记录
├── backend-java/              # Spring Boot 主业务（com.careerpath），端口 8080
├── analysis-service/          # Python 智能分析服务，端口 8000
├── frontend/                  # Vue 3 + Element Plus，端口 5173
└── scripts/                   # 一键起停、建库、种子、端到端冒烟
```

## 快速开始

### 1. 准备环境

- JDK 25、Maven 3.9+、Node.js 18+、Python 3.11+、MySQL 8

### 2. 配置环境变量

```bash
cp .env.example .env
```

填入数据库口令、`JWT_SECRET`、`INTERNAL_TOKEN`（后两者 Java 与分析服务必须一致）。

### 3. 初始化数据库

```bash
python scripts/init_db.py    # 建库建表（17 张）
python scripts/seed_demo.py  # 灌入种子与演示数据
```

### 4. 一键启动三进程

```bash
bash scripts/dev-up.sh    # 启动 Java / 分析服务 / 前端
bash scripts/dev-down.sh  # 停止并清理端口
```

启动后访问 http://localhost:5173

### 5. 端到端冒烟

```bash
python scripts/smoke_e2e.py
```

## 功能范围

**一期（已交付）**：用户中心、求职工作台、岗位解析、技能图谱、技能画像与证据链、岗位匹配与差距、学习任务 —— 15 张表 / 7 个页面

**二期（已交付）**：投递工作台、面试复盘、数据看板 —— +2 张表 / 2 个页面

**暂不做**：招聘网站自动爬取、自动投递、AI 语音模拟面试、大规模推荐算法、企业招聘后台

## 评分说明

必备与加分**分别计算**，不合并成单一总分：

```
required_score = Σ_required(W × min(L,T)) / Σ_required(W × T) × 100
bonus_score    = Σ_bonus(W × min(L,T))    / Σ_bonus(W × T)    × 100
```

- 技能掌握度 0–5 级，采用**行为锚定**（做过什么），不用「熟练/精通」类自评
- 证据**不进分数**，只作独立展示（`evidence_coverage`），避免「没上传材料就扣分」
- 分数只是辅助分析，**不表述为录用概率**

## 数据来源与免责

内置技能词典、权重与目标等级为**示例数据**，依据公开招聘信息的常见技能要求归纳，**不是任何企业官方标准**，也不代表行业标准。

JD 解析第一版采用「技能词典 + 同义词映射 + 正则规则」，不依赖大模型；结果仅作预填，支持人工修正。

## 分支约定

- `dev`：开发分支，日常提交在此
- `main`：对外展示主干，当前与 `dev` 保持同步

> 注：项目内部原约定为「`main` 只收评审通过代码」。为便于公开展示，本仓库将 `main` 与 `dev` 对齐；后续若恢复评审闸门，可在 Settings 中把默认分支切回 `dev`。

## 授权

本项目为学习与作品展示用途。如需引用其中的数据模型或评分口径，请注明来源。
