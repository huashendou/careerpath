# T0-R 验证命令（可直接照抄）

> 本文件由实现方提供，只给**怎么验**，不含任何结论。评审方跑完把结果填进 `T0-R.md`。
> 所有 `curl` 必须带 `--noproxy '*'`：本机有 HTTP 代理，不带会得到 502，看起来像服务挂了。

## 前置

```bash
cd "职能雷达——大学生实习求职技能匹配与能力差距分析平台。"
# 终端 1：起三进程，等这三行出现
bash scripts/dev-up.sh
# 终端 2：跑下面的命令
```

## A 契约一致性

**A1 错误体格式**（未登录访问受保护路由）

```bash
curl -s --noproxy '*' -w '\nHTTP %{http_code}\n' http://127.0.0.1:8080/api/profile
```
期望：`401` + `{"detail":{"code":"UNAUTHORIZED","message":"未登录或登录已失效"}}`

**A2 无 snake_case 泄漏**

```bash
curl -s --noproxy '*' http://127.0.0.1:8080/api/health | grep -o '"[a-z]*_[a-z]*"' | head
```
期望：无输出。

**A3 `anyOf` 恒为数组**

```bash
TOKEN=$(grep '^INTERNAL_TOKEN=' .env | cut -d= -f2- | tr -d '\r')
curl -s --noproxy '*' -H "X-Internal-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"text":"熟悉 Java 与 Spring Boot，了解 Redis 者优先","jobTitle":"后端实习"}' \
  http://127.0.0.1:8000/internal/parse/jd | python -c "import json,sys;d=json.load(sys.stdin);print([r['anyOf'] for r in d['requirements']])"
```
期望：每个元素都是 `[]`，不是 `None`。

**A5 分页** —— 本轮 N/A（T0 无分页接口，T3 起验）。
**A4 时间格式 / A6 无总分字段** —— 见 `T0-自检报告.md` 第五节，T0 无业务时间字段；`/internal/match` 返回中只有 `requiredScore` 与 `bonusScore`，无 `totalScore` / `matchScore`。

## C 数据与 SQL（本轮重点）

```bash
PYTHONIOENCODING=utf-8 analysis-service/.venv/Scripts/python.exe - <<'PY'
import pathlib, pymysql
env = {}
for line in pathlib.Path(".env").read_text(encoding="utf-8").splitlines():
    line = line.strip()
    if line and not line.startswith("#") and "=" in line:
        k, v = line.split("=", 1); env[k.strip()] = v.strip()
c = pymysql.connect(host=env.get("DB_HOST","127.0.0.1"), port=int(env.get("DB_PORT",3306)),
    user=env.get("DB_USER","root"), password=env.get("DB_PASSWORD",""),
    database=env.get("DB_NAME","career_path"), charset="utf8mb4")
cur = c.cursor(); db = env.get("DB_NAME","career_path")

cur.execute("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=%s", (db,))
print("表数量:", cur.fetchone()[0], "（期望 15）")

cur.execute("""SELECT TABLE_NAME, INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)
FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=%s AND NON_UNIQUE=0 AND INDEX_NAME<>'PRIMARY'
GROUP BY TABLE_NAME, INDEX_NAME ORDER BY TABLE_NAME""", (db,))
print("\n唯一约束（期望 7 组）:")
for r in cur.fetchall(): print("  ", r[0], r[1], r[2])

cur.execute("""SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE k
JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON r.CONSTRAINT_NAME=k.CONSTRAINT_NAME
WHERE k.TABLE_SCHEMA=%s AND k.REFERENCED_TABLE_NAME IS NOT NULL""", (db,))
print("\n外键总数:", cur.fetchone()[0], "（期望 20）")

for t in ("skill_category","skill","skill_relation"):
    cur.execute(f"SELECT COUNT(*) FROM {t}"); print(f"{t}: {cur.fetchone()[0]}")
c.close()
PY
```
再跑一次 `python scripts/init_db.py`，然后重复上面的查询：表数、`skill`、`skill_category` 三项必须**完全不变**（幂等）。

## D 安全（本轮重点）

**D1 内部令牌闸门**

```bash
TOKEN=$(grep '^INTERNAL_TOKEN=' .env | cut -d= -f2- | tr -d '\r')
curl -s --noproxy '*' -o /dev/null -w '带令牌 %{http_code}\n' -H "X-Internal-Token: $TOKEN" http://127.0.0.1:8000/internal/health
curl -s --noproxy '*' -o /dev/null -w '无令牌 %{http_code}\n' http://127.0.0.1:8000/internal/health
```
期望：`200` / `401`。

**D2 `.env` 不入库**

```bash
git ls-files | grep -c '^\.env$'   # 期望 0
git check-ignore -v .env            # 期望命中 .gitignore
```

**D3 密码哈希** —— 本轮 N/A（T0 无注册接口，T1 验）。

## E 可运行性

```bash
cd backend-java && /c/Users/24693/.local/share/maven/apache-maven-3.9.16/bin/mvn -B -DskipTests package | tail -3
cd ../frontend && npm run build 2>&1 | tail -4
cd ../analysis-service && ./.venv/Scripts/python.exe -m pytest -q 2>&1 | tail -2
```
期望：`BUILD SUCCESS` / 构建无报错 / `83 passed`。

**jar 否定校验**（确认 T0 快照不含 T1 代码）

```bash
cd .. && "$JAVA_HOME/bin/jar" tf backend-java/target/careerpath-api-0.1.0-SNAPSHOT.jar \
  | grep -E 'com/careerpath/(auth|profile|user|stats)/' || echo "PASS：无越界类"
```
期望：`PASS`。

## F 前端

7 页逐个打开，标题应为：`/login` 登录表单、`/dashboard` 概览、`/jobs` 岗位解析、`/skills` 技能图谱、`/profile` 技能画像、`/match/1` 岗位匹配、`/tasks` 学习计划。

## G 提交与文档

```bash
git log --oneline -6
git status --short          # 期望空
```
检查提交前缀（`feat` / `fix` / `docs` / `chore`）与「一个提交一件事」。

---

## 已知不属缺陷的三项（避免误判）

1. **`/api/auth/*` 与 `/api/stats/*` 返回 404** —— 属 T1 范围，T0 未实现，正确。
2. **`mvn` 不在 PATH** —— 装在 `~/.local/share/maven/apache-maven-3.9.16/bin`，用全路径或走 `scripts/dev-up.sh`（已做三级解析）。
3. **`dev-up.sh` 起的子进程在命令结束后消失** —— 某些终端/CI 会给整个进程组发 SIGTERM。请在一贯的交互终端里跑，或分两个终端。
