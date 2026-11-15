"""灌一套可点遍所有功能的演示数据。

前置：`bash scripts/dev-up.sh` 已起三进程。
用法：
    python scripts/seed_demo.py                       # 默认 demo_student / Demo!2026
    python scripts/seed_demo.py --username demo2 --password 'Demo!2026'

幂等策略：账号已存在就直接登录，不重复注册；岗位按标题去重，已有同名岗位则跳过创建。
重复执行只会补齐缺失的部分，不会把数据翻倍。
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request

JD_BACKEND = (
    "岗位职责：负责后端接口开发与数据库设计，参与需求评审。\n"
    "任职要求：1. 熟悉 Java 基础与集合框架，了解多线程与并发；2. 熟悉 Spring Boot 与 MyBatis；"
    "3. 熟悉 MySQL，能编写 SQL 并做索引优化；4. 熟悉 Redis 缓存；5. 熟悉 Git 与 Linux 常用命令。\n"
    "加分项：有 Docker 部署经验者优先；了解消息队列（Kafka / RabbitMQ）者优先。"
)

JD_FRONTEND = (
    "岗位职责：负责 Web 前端页面与组件开发。\n"
    "任职要求：1. 熟悉 JavaScript 核心语法与 ES6；2. 熟悉 Vue 框架与组件化开发；"
    "3. 熟悉 HTML 与 CSS 布局；4. 了解前端构建工具；5. 熟悉 Git。"
)


def request(method: str, url: str, token: str | None = None, body: dict | None = None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8")
        try:
            return error.code, json.loads(raw) if raw else None
        except json.JSONDecodeError:
            return error.code, {"raw": raw}
    except urllib.error.URLError as error:
        print(f"连不上 {url}：{error.reason}\n先执行 bash scripts/dev-up.sh")
        sys.exit(2)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    parser.add_argument("--username", default="demo_student")
    parser.add_argument("--password", default="Demo!2026")
    args = parser.parse_args()
    base = args.base.rstrip("/")
    user, pwd = args.username, args.password

    status, auth = request("POST", f"{base}/api/auth/register", body={"username": user, "password": pwd})
    if status == 409:
        status, auth = request("POST", f"{base}/api/auth/login", body={"username": user, "password": pwd})
    if status not in (200, 201) or not auth:
        print(f"注册/登录失败：HTTP {status} {auth}")
        return 1
    token = auth["accessToken"]
    print(f"账号就绪：{user} / {pwd}")

    # 档案：学校与专业都填，概览才会显示「已完善」
    request("PUT", f"{base}/api/profile", token, {
        "realName": "李雷", "school": "示例大学", "major": "计算机科学与技术", "grade": "大三",
        "targetJobType": "Java 后端 / Web 前端", "targetCity": "杭州",
        "phone": "13800138000", "email": "lilei@example.com",
    })

    def find_job(title: str) -> int | None:
        _, page = request("GET", f"{base}/api/jobs?page=1&size=50", token)
        for item in (page or {}).get("items", []):
            if item["title"] == title:
                return item["id"]
        return None

    def create_job(title: str, city: str, company: str, jd: str, salary: tuple[int, int]) -> int:
        existing = find_job(title)
        if existing:
            return existing
        _, job = request("POST", f"{base}/api/jobs", token, {
            "title": title, "companyName": company, "city": city,
            "salaryMin": salary[0], "salaryMax": salary[1], "source": "手动粘贴", "rawDescription": jd,
        })
        job_id = job["id"]
        _, parsed = request("POST", f"{base}/api/jobs/{job_id}/parse", token)
        items = [
            {"skillId": r["skillId"], "reqType": r["reqType"], "weight": r["weight"],
             "targetLevel": r["targetLevel"], "anyOf": [], "evidenceHint": r.get("evidenceHint"),
             "sourceSpan": f'{r["sourceStart"]}-{r["sourceEnd"]}'}
            for r in parsed.get("requirements", [])
        ]
        request("PUT", f"{base}/api/jobs/{job_id}/requirements", token, {"items": items})
        return job_id

    backend_id = create_job("Java 后端开发实习生", "杭州", "示例科技", JD_BACKEND, (200, 300))
    frontend_id = create_job("Web 前端开发实习生", "杭州", "示例网络", JD_FRONTEND, (180, 260))

    # 前端岗：把 vue 改成「vue 或 react」二选一，用来演示 anyOf 取高者计分
    _, current = request("GET", f"{base}/api/jobs/{frontend_id}/requirements", token)
    items = []
    for item in current.get("items", []):
        row = {"skillId": item["skillId"], "reqType": item["reqType"], "weight": item["weight"],
               "targetLevel": item["targetLevel"], "anyOf": item.get("anyOf") or [],
               "evidenceHint": item.get("evidenceHint"), "sourceSpan": item.get("sourceSpan")}
        if item["skillId"] == "vue":
            row["anyOf"] = ["react"]
        items.append(row)
    request("PUT", f"{base}/api/jobs/{frontend_id}/requirements", token, {"items": items})

    # 自评：既有「达标」也有「偏弱 / 缺失」，差距清单才有多态
    request("PUT", f"{base}/api/user-skills", token, {"items": [
        {"skillId": "java-basic", "level": 4, "learnedAt": "2025-09-01", "lastUsedAt": "2026-10-01"},
        {"skillId": "spring-boot", "level": 2},
        {"skillId": "sql-query", "level": 3},
        {"skillId": "db-mysql", "level": 2},
        {"skillId": "redis", "level": 1},
        {"skillId": "git", "level": 4},
        {"skillId": "js-core", "level": 3},
        {"skillId": "react", "level": 1},
        {"skillId": "html-css", "level": 2},
    ]})

    # 证据：两份，一份只覆盖后端、一份覆盖数据，覆盖面不会满
    _, evidences = request("GET", f"{base}/api/evidence", token)
    if not evidences.get("items"):
        request("POST", f"{base}/api/evidence", token, {
            "evidenceType": "repo", "title": "校园二手交易平台", "url": "https://github.com/demo/trade",
            "description": "独立完成后端接口与数据库设计", "completeness": 75,
            "skills": [{"skillId": "spring-boot", "relevance": 85, "contributionNote": "独立完成后端"},
                       {"skillId": "java-basic", "relevance": 80}],
        })
        request("POST", f"{base}/api/evidence", token, {
            "evidenceType": "course", "title": "数据库系统课程设计",
            "description": "完成 ER 设计与 12 张表的建表脚本", "completeness": 60,
            "skills": [{"skillId": "sql-query", "relevance": 70}, {"skillId": "db-mysql", "relevance": 60}],
        })

    # 项目：一份带技能关联，一份不带（演示空态与有态）
    _, projects = request("GET", f"{base}/api/projects", token)
    if not projects.get("items"):
        _, project = request("POST", f"{base}/api/projects", token, {
            "name": "校园二手交易平台", "role": "后端负责人", "techStack": ["Java", "Spring Boot", "MySQL"],
            "description": "负责商品与订单模块，接口 QPS 压测到 300", "repoUrl": "https://github.com/demo/trade",
            "startDate": "2026-03-01", "endDate": "2026-06-30",
        })
        request("POST", f"{base}/api/projects/{project['id']}/skills", token, {"items": [
            {"skillId": "spring-boot", "contributionNote": "接口与鉴权"},
            {"skillId": "db-mysql", "contributionNote": "订单表设计"},
        ]})
        request("POST", f"{base}/api/projects", token, {
            "name": "个人博客前端", "role": "独立开发", "techStack": ["Vue", "Vite"],
            "description": "Markdown 渲染与暗色主题", "startDate": "2026-07-01",
        })

    # 匹配：两个岗位各算一次，概览才有最近匹配与趋势
    _, match_backend = request("POST", f"{base}/api/match/{backend_id}", token)
    request("POST", f"{base}/api/match/{frontend_id}", token)

    # 任务：来自差距 + 手动；完成一条并提等级，触发重算
    _, tasks = request("GET", f"{base}/api/tasks", token)
    if not tasks.get("items"):
        _, created = request("POST", f"{base}/api/tasks/from-gap/{backend_id}", token, {"topN": 3})
        request("POST", f"{base}/api/tasks", token, {
            "title": "整理项目 README 与接口文档", "source": "manual", "priority": 1,
            "dueDate": "2026-12-01",
        })
        first = (created.get("items") or [None])[0]
        if first and first.get("skillId"):
            request("POST", f"{base}/api/tasks/{first['id']}/complete", token,
                    {"updateSkillLevel": True, "level": 3})

    # 投递：一条走到面试中，一条还在待投
    _, apps = request("GET", f"{base}/api/applications", token)
    if not apps.get("items"):
        _, app = request("POST", f"{base}/api/applications", token,
                         {"jobPositionId": backend_id, "note": "学长内推，HR 说两周内出结果"})
        request("PATCH", f"{base}/api/applications/{app['id']}", token, {"status": "applied"})
        request("PATCH", f"{base}/api/applications/{app['id']}", token, {"status": "interview"})
        request("POST", f"{base}/api/applications", token,
                {"jobPositionId": frontend_id, "note": "官网投递"})
        app_id = app["id"]
    else:
        app_id = apps["items"][0]["id"]

    # 面试：一条关联投递（有题目与复盘），一条独立
    _, interviews = request("GET", f"{base}/api/interviews", token)
    if not interviews.get("items"):
        request("POST", f"{base}/api/interviews", token, {
            "applicationId": app_id, "interviewDate": "2026-11-18", "round": "一面", "format": "video",
            "questions": [
                {"question": "Spring 事务的传播行为有哪些？", "selfRating": 3, "note": "REQUIRED 说清了，NESTED 没答上来"},
                {"question": "Redis 缓存击穿怎么处理？", "selfRating": 2, "note": "只说了互斥锁"},
            ],
            "reflection": "八股要背熟；项目里的 QPS 数字要能解释是怎么压出来的。",
            "result": "pass", "relatedSkills": ["spring-boot", "redis"],
        })
        request("POST", f"{base}/api/interviews", token, {
            "companyName": "示例网络", "jobTitle": "Web 前端开发实习生",
            "interviewDate": "2026-11-22", "round": "二面", "format": "onsite",
            "questions": [{"question": "Vue 响应式原理讲一下", "selfRating": 3, "note": "Proxy 说了，依赖收集没说透"}],
            "reflection": "等结果，准备手写题。", "result": "pending",
            "relatedSkills": ["js-core", "react"],
        })

    _, overview = request("GET", f"{base}/api/stats/overview", token)
    _, insights = request("GET", f"{base}/api/stats/insights", token)
    print(f"后端岗 jobId={backend_id}（必备分 {match_backend.get('requiredScore')}，"
          f"加分分 {match_backend.get('bonusScore')}，差距 {len(match_backend.get('gaps', []))} 项）")
    print(f"前端岗 jobId={frontend_id}")
    print(f"概览：{json.dumps(overview, ensure_ascii=False)}")
    print(f"看板：投递 {len((insights.get('applicationStatus') or []))} 类、"
          f"趋势 {len(insights.get('matchTrend') or [])} 点、高频差距 {len(insights.get('topGaps') or [])} 条、"
          f"任务完成率 {insights.get('taskCompletion')}")
    print(f"\n用 {user} / {pwd} 登录 http://localhost:5173 即可开始点。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
