# T0-R 评审

> 本文件由**评审方（Trae）**填写。实现方（WorkBuddy）不得修改本文件的结论段。
> 只填这一个文件即可，不要改动 `docs/T0-自检报告.md`、`docs/schema.sql` 与任何代码文件。

## 一、基本信息

| 项 | 值 |
|---|---|
| 轮次 | T0-R |
| 被审分支 / 提交 | `dev` @ `13f39d0`；代码快照等同 `8b00c15`（其后两笔仅追加 `docs/reviews/` 与本文件规则，无代码改动） |
| 被审范围 | T0 骨架与契约（Spring Boot 骨架、15 表、7 页路由、三进程脚本、内部三接口） |
| 评审时间 | 2026-11-15 20:05–20:20（Asia/Shanghai） |
| 评审人 | Trae |

补充核对：`git diff --stat 8b00c15..HEAD -- backend-java analysis-service frontend scripts data` **输出为空**，确认「代码快照等同 8b00c15」这一说法成立；评审时 HEAD 为 `73b3b0a`，其后提交只动 `docs/`。

## 二、结论

- [ ] **通过** —— 可开工 T1
- [x] **有条件通过** —— 修复项：`T0-R-P1-1`，清零并复核前不得开工 T1
- [ ] **不通过** —— 阻塞项：（列编号），退回重做

一句话总评：骨架、建表、鉴权闸门、算分口径与三服务连通都是真的，测试与构建均独立复跑通过；唯一 P1 是「缺 `.env` 也能用弱默认值启动」的安全兜底，属 T1 平台化之前必须堵的口子。

## 三、通过项

| 组 | 内容（与看板 6.2 严格对应） | 结论 | 依据 |
|---|---|---|---|
| A | 契约一致性：错误体、camelCase、`anyOf` 数组、`null` 语义、无总分字段 | ✅ 通过（1 处命令口径瑕疵见 P2-2） | A1 实跑 401 + `{"detail":{"code":"UNAUTHORIZED","message":"未登录或登录已失效"}}`；A2 按**键名**核对无 snake_case；`/internal/match` 顶层键无 `totalScore`/`matchScore`；空自评 `requiredScore`/`bonusScore` 同为 `null` + `EMPTY_PROFILE` |
| B | 计分正确性（T4 重点）：本轮只需确认 `/internal/match` 存在且分数无「总分」 | ✅ 通过 | `spring-boot` weight=2 / target=3 / level=1 → `requiredScore=33.3333`，与手算 `2×1/(2×3)` 一致；`anyOf:["vue"]` + `vue=4` 的加分项 `level` 取 4 且只出一条 |
| C | 数据与 SQL（**T0-R 重点**）：15 表、唯一约束、外键级联、种子幂等 | ✅ 通过 | 15 表 / 7 组唯一约束 / 20 条外键 / 61 技能 / 6 分类；库为 `utf8mb4` + `utf8mb4_0900_ai_ci`；`init_db.py` 再跑一次后 15 / 61 / 6 完全不变 |
| D | 安全（**T0-R 重点**）：密码哈希、JWT、内部令牌、`.env` 不入库 | ✅ 通过（默认值兜底见 P1-1） | D1 `带令牌 200` / `无令牌 401`；D2 `git ls-files` 中 `.env` 计 0 且 `git check-ignore` 命中 `.gitignore:12`；JWT 为 HS256、含 `exp`、密钥长度强校验（<32 字节启动失败）；T0 无注册接口，bcrypt 无法验，如实记为 N/A |
| E | 可运行性：三进程、两个 health、`mvn`/`npm`/`pytest` | ✅ 通过 | `dev-up.sh` 实测三行就绪（java 3s / analysis 1s / vite 1s）；`mvn -DskipTests package` **BUILD SUCCESS 7.36s**；`npm run build` **✓ 14.83s**；`pytest` **83 passed / 2.48s**；jar 否定校验 `PASS：无 auth/profile/user/stats 包` |
| F | 前端质量（T3 起重点）：本轮只需确认 7 页可打开、不白屏 | 🟡 部分验证 | 构建产物含 7 个视图 chunk；7 个 `.vue` 标题齐备（概览/岗位解析/技能图谱/技能画像/岗位匹配/学习计划 + 登录）；**未做浏览器逐页渲染**，该结论只到「静态 + 构建」层级，不代表逐页可视验收 |
| G | 提交与文档：前缀规范、一个提交一件事、看板与实际一致 | ✅ 通过 | `git status --short` 为空；近 6 笔均为 `docs:`/`fix:` 前缀、单一主题；看板 T0 置 🟡、T1 置 ⬜ 与事实一致；`P0-重构方案.md` 已无「20 张表」表述，与 c3.3 的 15 表口径一致 |

## 四、问题清单

| 编号 | 级别 | 位置（文件:行） | 现象 | 期望 | 修复状态 |
|---|---|---|---|---|---|
| T0-R-P1-1 | P1 | `backend-java/src/main/resources/application.yml:16-17,47-48` | `.env` 缺失时仍能用兜底值启动：`DB_USERNAME:root`、`DB_PASSWORD:` 为空、`JWT_SECRET` 为固定开发串。误部署或忘配 `.env` 时不会失败，而是以弱密钥、root 空口令运行 | 非 `dev` profile 下三者无默认值、缺失即启动失败；`dev` profile 保留兜底但启动打印 WARN 并点名「使用了开发默认密钥」 | 待修 |
| T0-R-P2-1 | P2 | `backend-java/pom.xml:50,57` | `mybatis-plus-jsqlparser` 被声明两次，Maven 报 `building the effective model ... malformed projects` 警告 | 删除其一（保留带注释那条即可），让 `mvn` 无该警告 | 待修 |
| T0-R-P2-2 | P2 | `docs/reviews/T0-R-验证命令.md:27-29,31-39` | A2 命令 `grep '"[a-z]*_[a-z]*"'` 会命中值 `career_path`，产生假阳性；A3 期望 `/internal/parse/jd` 返回 `anyOf`，但看板 3.3 的解析响应契约本就没有该字段（`anyOf` 属 `PUT/GET /api/jobs/{id}/requirements`） | A2 改为只匹配键名（`"..."\s*:`）；A3 改到 requirements 接口验，或删掉本轮的 A3 | 待修 |
| T0-R-P2-3 | P2 | `scripts/dev-up.sh`、`scripts/dev-down.sh` | 只有 bash 脚本。本次评审在非交互 shell 中执行，脚本退出后三个子进程被回收（与验证命令「已知项 3」一致），只能改用 `Start-Process` 挂起才完成后续验收 | 补 `dev-up.ps1`/`dev-down.ps1`，或脚本用 `nohup`/`Start-Process` 脱离进程组；至少在本文件与 README 注明「必须在交互终端执行」 | 待修 |
| T0-R-P2-4 | P2 | `analysis-service` `/internal/parse/jd` | 入参 `text` 无长度上限，超长文本会整段进正则，属可被外部触发的耗时点（T3 接线后即走这条路） | 加合理上限（如 20 KB）并返回 `422 VALIDATION_ERROR` | 待修 |

## 五、证据

```text
# 服务：dev-up.sh（bash）
[dev-up] java 就绪（约 3s）
[dev-up] analysis 就绪（约 1s）
[dev-up] vite 就绪（约 1s）

# A1 未登录
{"detail":{"code":"UNAUTHORIZED","message":"未登录或登录已失效"}}
HTTP 401

# A2 键名核对（值里的 career_path 不算泄漏）
health bytes: 409
键名无 snake_case（值里出现 career_path 属正常）

# A3 当前期望项与契约不符（详见 P2-2）
requirements: 4   reqType: ['bonus','required']   matchedCount: 4
条目字段：skillId/skillName/dim/reqType/weight/targetLevel/matchedAlias/sourceStart/sourceEnd/sourceText/evidenceHint/confidence
→ 与看板 3.3:159-162 逐字段一致；无 anyOf（契约本就无）

# A6 / B 算分（POST /internal/match）
requiredScore: 33.3333   bonusScore: None   status: ok
含 totalScore/matchScore: []
空自评: requiredScore=None bonusScore=None status=EMPTY_PROFILE
anyOf 场景: bonusItems=[react weight=1 target=2 level=4 status=mastered]  bonusScore=100.0
radar: dict，axes 6 条，空维度 target/current=null、hasCore=false、coreWeight=0

# C 数据库
表数量: 15 （期望 15）
唯一约束组数: 7  → evidence_skill_relation / job_skill_requirement / project_skill_relation /
                    skill_gap_report / skill_relation / user / user_skill
外键总数: 20 （期望 20）
skill_category: 6   skill: 61   skill_relation: 0
库字符集: ('utf8mb4', 'utf8mb4_0900_ai_ci')
init_db.py 再跑一次后：skill 61 / skill_category 6 / 表数量 15（与首轮一致）

# D 安全
带令牌 200
无令牌 401
tracked .env 数: 0
.gitignore:12:.env	.env

# E 构建与测试
mvn -B -DskipTests package → BUILD SUCCESS（Total time: 7.360 s；附带 P2-1 的 malformed model 警告）
npm run build → ✓ built in 14.83s（警告：element-plus chunk 968.05 kB > 900 kB）
pytest → 83 passed, 1 warning in 2.48s
jar 否定校验 → PASS：无 auth/profile/user/stats 包

# G 提交
73b3b0a docs: 修正 T0-R 模板分组对齐 6.2，补可照抄的验证命令
df26f14 docs: T0-R 被审提交指向 13f39d0，注明代码快照等同 8b00c15
13f39d0 docs: 评审产物改为每轮独立文件，夹紧评审方可改范围
8b00c15 docs: T0 自检报告（A/C/D/E/G 逐条附证据）+ 看板与 AGENTS 状态修正
git status --short → 空
```

未执行项（不写「已验」）：F 组的浏览器逐页渲染；`/api/auth/*`、`/api/stats/*` 的 404（属 T1 范围，未列入问题）。

## 六、遗留与建议

1. **闸门**：`T0-R-P1-1` 修完并复核前不得开工 T1。P2 可与 T1 并行修，但 T1 交付前必须清零。
2. **`skill_relation` 种子 0 行**：本轮接受，但它是 T3「技能图谱」的必需数据；T3 验收会查「图谱非空」，届时无数据即判不通过。
3. **`anyOf` 的验点要挪位**：解析响应本不含 `anyOf`，它属于 `PUT/GET /api/jobs/{id}/requirements`。T3 验收时按看板 6.2 第 517 行验「只计一次 + 成员不独立出现」，不要再用解析接口验。
4. **`sourceStart/sourceEnd` 与 `sourceSpan` 并存**：解析响应用偏移量数字，requirements 接口用 `"10-25"` 字符串。两处语义相同、格式不同，建议在契约里写明映射，避免前端写两套高亮逻辑。
5. **前端体积**：`element-plus` 单 chunk 968 kB（gzip 298 kB），T0 可接受；T4 图表页前建议按需引入 + 路由懒加载，否则首屏会明显变慢。
6. **`wip/t1-trae-1950`**：4 笔 T1 提交是评审侧越界产物，不建议在 T1 阶段直接 merge 充当交付；由 WorkBuddy 重做或逐笔接管并在 T1-R 重新验收，避免「未评审代码进主干」。
7. **服务存活方式**：验收期间确认 `dev-up.sh` 在非交互 shell 下退出即回收子进程。后续自动化验收（CI、脚本化冒烟）必须用 `Start-Process`/`nohup` 或 `.ps1`，否则会得到「服务挂了」的假象。
