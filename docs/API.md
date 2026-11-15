# 后端实现说明

> **接口契约只有一份：`docs/协作看板.md` 第三节（当前 c2.1）。**
> 本文不定义字段。它回答的是另一个问题——契约里那些字段，在后端**是怎么算出来、从哪张表取的**。
> 两份文档各干一件事：看板管「前端会收到什么」，本文管「后端凭什么这么返回」。

---

## 一、为什么有这份文档（历史说明）

P1 阶段我（WorkBuddy）在没读看板第三节的情况下另写了一份契约 `docs/API.md v1.0`，
和看板 c2.0 有 22 处冲突：响应包、错误体、接口路径、字段名、精度、画像模型全都不同。
按看板「接口契约冻结，任何一方不得单方面修改」的约定，那份文件越权了。

处理结果：**以看板为准**，后端全部按看板重写；看板升 c2.1 吸纳了 4 处改进建议。
本文降级为「实现说明」，不再与看板并列。

---

## 二、字段来源对照

| 接口 | 处理位置 | 数据来源 |
|---|---|---|
| `GET /api/skills/dimensions` | `api/catalog.py::get_catalog` | `dimensions` 全表 + `app_meta`（levels / disclaimer / dataVersion / scoringVersion） |
| `GET /api/skills` | `api/catalog.py::list_skills` | `skills` 全表，`dim` 参数过滤 `dim_id` |
| `GET /api/jobs` | `api/catalog.py::list_jobs` | `jobs` + `job_requirements` 现算权重和，**不落库** |
| `GET /api/jobs/{id}` | `api/catalog.py::get_job` | `job_requirements` join `skills` 取 `skillName` / `dim` |
| `POST /api/auth/*` | `api/auth.py` | `users`；`password_hash` 用 bcrypt，任何响应都不含该字段 |
| `GET/POST/PUT/DELETE /api/profiles` | `api/profiles.py` | `profiles` + `profile_skills`（子表，非 JSON） |
| `POST /api/analysis/match` | `api/analysis.py` → `services/runner.py` → `services/matching.py` | 见下节 |
| `POST/GET /api/reports` | `api/reports.py` | `reports.payload` 存整份 MatchResult 快照 |
| `POST /api/resume/parse` | `api/resume.py` | `skills.aliases` + 正则遮蔽 |

**算分不查库**：`matching.py` 是纯函数，输入岗位要求 + 自评，输出结果字典。
`runner.py` 负责查库组装。分开是为了让 pytest 能不起数据库验完全部口径（90 项测试里 60 项走这条路）。

---

## 三、`status` 三态与 `matchScore`

| status | 触发条件 | matchScore | 前端应显示 |
|---|---|---|---|
| `NO_CORE_REQUIREMENT` | 岗位 core 的 `Σ(w×T)` 为 0 | `null` | 「该岗位暂无核心要求」 |
| `EMPTY_PROFILE` | 自评记录为空（一条都没有） | `null` | 「尚未完成自评」 |
| `ok` | 其余情况 | 数值 | 正常展示百分比 |

判定顺序固定：先 `NO_CORE_REQUIREMENT` 再 `EMPTY_PROFILE`。岗位本身不可算比用户没填更根本，
换任何画像结论都一样。

**「评了但全是 0 级」不等于 `EMPTY_PROFILE`**：那是真实结论 0 分，必须显示 0%。
两者混在一起会让「真的什么都不会」的用户以为自己没提交成功。

---

## 四、精度与舍入

| 字段 | 精度 | 说明 |
|---|---|---|
| `matchScore` | 4 位小数 | `ROUND_HALF_UP`，不用 Python 默认的银行家舍入 |
| `radar.axes[].target` / `current` | 2 位小数 | 展示用，不参与后续计算 |
| `numerator` / `denominator` | 整数 | 暴露出来是为了能直接验算「分母 255 不是 100」 |

`matchScore` 是 `float`，`41.1765` 这种值在前端 `toFixed(1)` 得 `41.2`。
**别用 JS 的 `Math.round` 复核后端结果**——银行家舍入会对不上。

---

## 五、算分公式（与 AGENTS.md 第八节一致）

```
denominator = Σ_core(w × T)                       // 255 / 294 / 292
numerator   = Σ_core(w × min(L_eff, T))
matchScore  = numerator / denominator × 100

L_eff = max(自评[skillId], 自评[anyOf 成员])        // anyOf 只计一次
gap_i      = max(T − L_eff, 0)                     // 仅 core
priority_i = w × gap_i                             // 仅 core，降序取 Top 3

radar.axes[k].target  = Σ_core_k(w × T) / Σ_core_k(w)
radar.axes[k].current = Σ_core_k(w × L_eff) / Σ_core_k(w)
```

三条红线，改代码前先看这里：

1. plus 不进 `numerator`，也不进 `denominator`。
2. `anyOf` 在匹配度与 `gaps` 中都只算一次（React 等级只通过 Vue 那条要求生效，不单独出现在 `gaps`）。
3. 无 core 的维度返回 `null`，**不得返回 0**——0 会被读成「要求为零，随便怎样都行」。

---

## 六、错误码实现位置

`app/core/errors.py`。每个类三个类属性：`status`（HTTP 码）、`code`（字符串枚举）、`message`（中文提示）。

| code | HTTP | 类 |
|---|---|---|
| `VALIDATION_ERROR` | 422 | `ValidationError` / FastAPI 参数校验 |
| `UNAUTHORIZED` | 401 | `Unauthorized` |
| `INVALID_CREDENTIALS` | 401 | `InvalidCredentials` |
| `JOB_NOT_FOUND` | 404 | `JobNotFound` |
| `PROFILE_NOT_FOUND` | 404 | `ProfileNotFound` |
| `REPORT_NOT_FOUND` | 404 | `ReportNotFound` |
| `USERNAME_TAKEN` | 409 | `UsernameTaken` |
| `PROFILE_LIMIT` | 409 | `ProfileLimit` |
| `UNKNOWN_SKILL` | 422 | `UnknownSkill` |
| `INTERNAL_ERROR` | 500 | 兜底 handler |

**越权一律返回 404，不用 403**：403 等于告诉对方「这个 id 存在，只是不属于你」，
可以被用来枚举他人画像。

---

## 七、可跑的验证

```powershell
cd backend
py -m pytest -q          # 90 项，不依赖 MySQL
py seed.py               # 建表 + 导入（幂等，跑两次行数不变）
uvicorn app.main:app --reload --port 8000
```
