# T0-R 修复回执

> 实现方填写，逐条对应 `T0-R.md` 的问题清单。评审结论段未改动。

## 结论

| 编号 | 级别 | 判定 | 处置 |
|---|---|---|---|
| T0-R-P1-1 | P1 | **属实，且范围比评审描述更广** | 已修，正反两面实测 |
| T0-R-P2-1 | P2 | 属实 | 已修 |
| T0-R-P2-2 | P2 | 属实（实现方给评审方的命令写错了） | 已修 |
| T0-R-P2-3 | P2 | 属实（环境限制，非脚本逻辑缺陷） | **未修**，列入 T1 待办 |
| T0-R-P2-4 | P2 | **部分不成立**：校验已存在，但阈值形同虚设 | 已收紧 |

---

## T0-R-P1-1 缺 `.env` 也能用弱默认值启动

**核对**：属实，且不止评审列的三项——`INTERNAL_TOKEN` 同样有兜底值
`careerpath-local-key`（`application.yml:51`）。这一项更危险：它是 Java→Python 的唯一鉴权凭据，兜底等于内部接口对全网开放。

**修法**：四处兜底全部删除，缺失即启动失败。

```yaml
username: ${DB_USERNAME}      # 原 :root
password: ${DB_PASSWORD}      # 原 :（空）
secret:   ${JWT_SECRET}       # 原 :careerpath-dev-only-jwt-secret-do-not-use-in-prod
token:    ${INTERNAL_TOKEN}   # 原 :careerpath-local-key
```

同时在 `JwtService` 加了一道可读性检查：未解析的 placeholder 会被原样传入（`${JWT_SECRET}` 正好 13 字节），原来的报错是「至少需要 32 字节，当前 13 字节」，运维会去改 yml 而不是补 `.env`。现在直接报「JWT_SECRET 未配置：请在项目根 .env 中设置」。

**实测证据**

| 场景 | 做法 | 结果 |
|---|---|---|
| 缺配置 | 在无 `.env` 的目录（`$ROOT/.logs/nofail-cwd`）启动，`../.env` 与 `./.env` 均不存在 | 退出码 **1**，报 `JWT_SECRET 未配置：请在项目根 .env 中设置（可复制 .env.example）` |
| 有配置 | `scripts/dev-up.sh` | 退出码 **0**，三进程就绪；`/api/health` **200**、`database.status=up`、`skillCount=61`、`analysis.status=ok`、`latencyMs=4` |

> 过程中发现一个坑，记下来免得后续重复踩：命令行传
> `--spring.config.import=optional:file:/nonexistent/.env` **无法**屏蔽 yml 里的 import——该属性是累积的，不是覆盖。要验证「缺配置」只能在无 `.env` 的工作目录里启动。

---

## T0-R-P2-1 `mybatis-plus-jsqlparser` 重复声明

属实，`pom.xml` 第 50、57 行各一份（补依赖时没检查已有）。已删其一，保留注释更完整的那条。

**实测**：`mvn -B clean package` → `BUILD SUCCESS`，`building the effective model ... malformed projects` 警告消失。

---

## T0-R-P2-2 验证命令的口径瑕疵

**评定：这是实现方的错，不是评审方理解错。**

- A2 用 `grep '"[a-z]*_[a-z]*"'` 会命中值 `career_path`，属假阳性 → 改为只匹配键名：`grep -oE '"[a-z]+(_[a-z]+)+"[[:space:]]*:'`
- A3 的验点根本不存在：`/internal/parse/jd` 响应契约里没有 `anyOf`（它在 `job_skill_requirement` 表与 `GET/PUT /api/jobs/{id}/requirements`）→ 本轮标 N/A，验点挪到 T3 的 requirements 接口

---

## T0-R-P2-3 只有 bash 脚本

**评定：属实，但不是脚本逻辑缺陷。** 脚本自身在交互终端里工作正常（三进程 3 秒内就绪、`dev-down` 正常回收）。问题在于非交互 shell 退出时会给整个进程组发 SIGTERM，把子进程一起带走。

该项**本轮不修**，理由：补 `.ps1` 属于工具链完善，不影响 T0 交付物的正确性。已列入 T1 待办，与「CI/脚本化冒烟」一起做。当前文档（本文件与 `T0-R-验证命令.md`）已注明必须用交互终端。

---

## T0-R-P2-4 `/internal/parse/jd` 入参无长度上限

**评定：部分不成立。** 校验已存在：

```python
# app/main.py:168-170
text = payload.text.strip()
if len(text) > settings.max_text_length:
    raise ValidationError(f"文本过长：上限 {settings.max_text_length} 字符")
```

但评审的关切是对的：`max_text_length` 原值 **200_000**，一份 JD 极少超过 5 000 字，20 万字符的上限等于没设。

已收紧到 **20_000 字符**（中文约 60 KB UTF-8），覆盖正常长 JD，同时不给正则留可乘之机。`pytest` 83 项无回归。

---

## 未处理项汇总

| 项 | 原因 | 计划 |
|---|---|---|
| P2-3 补 `.ps1` 脚本 | 不影响交付物正确性 | T1 期间 |
| `skill_relation` 种子 0 行 | 评审第六节第 2 条已接受；它是 T3 图谱的必需数据 | T3 前补种子 |
| F 组浏览器逐页渲染 | 评审如实标「未执行」 | 见 `T0-自检报告.md` 第五节，实现方已用无头 Edge 逐页验过（7 页标题均正确）；评审可复跑 |

---

## 复核方式

```bash
cd "职能雷达——大学生实习求职技能匹配与能力差距分析平台。"
bash scripts/dev-up.sh                     # 期望退出码 0、三行就绪
curl -s --noproxy '*' http://127.0.0.1:8080/api/health   # 期望 200 + status=ok
cd backend-java && mvn -B clean package    # 期望 BUILD SUCCESS 且无 malformed 警告
cd ../analysis-service && ./.venv/Scripts/python.exe -m pytest -q   # 期望 83 passed
```

缺配置的验证：把 `backend-java/target/*.jar` 复制到任意**不含 `.env`** 的目录下启动，期望退出码 1 且报错点名 `JWT_SECRET`。
