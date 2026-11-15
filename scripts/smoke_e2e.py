"""全链路冒烟：注册 → 岗位 → 解析 → 要求 → 自评 → 证据 → 匹配 → 任务 → 完成重算 → 报告 → 投递 → 面试 → 看板。

前置：`bash scripts/dev-up.sh` 已把三进程起好（Java 8080 / 分析服务 8000 / 前端 5173）。
本脚本只打 HTTP，不写库结构、不改配置；失败会打印服务端返回的错误体并非零退出。

为什么单独一个脚本而不是一篇文档：评审要能**自己跑一遍**。文档写「实测通过」，
对面只能选择信或不信；脚本把每一步的断言写死，跑不通就是跑不通。

用法：
    python scripts/smoke_e2e.py
    python scripts/smoke_e2e.py --base http://127.0.0.1:8080

跑完会在库里留下一个 `smoke_<时间戳>` 账号及其数据，清理：
    DELETE FROM `user` WHERE username LIKE 'smoke_%';   -- 级联删档案/岗位/任务等
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request

PASS = 0
FAIL = 0

JD = (
    "任职要求：1. 熟悉 Java 基础与集合框架，了解多线程；2. 熟悉 Spring Boot 与 MyBatis；"
    "3. 熟悉 MySQL 与 SQL 优化；4. 了解 Redis 缓存与 Docker；5. 熟悉 Git 工作流。"
)


def call(method: str, url: str, token: str | None = None, body: dict | None = None):
    """发一个请求，返回 (状态码, 解析后的响应体)。4xx/5xx 不抛异常，交给断言判断。"""
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    request.add_header("X-Trace-Id", f"smoke-{int(time.time() * 1000)}")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            raw = response.read().decode("utf-8")
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8")
        try:
            return error.code, json.loads(raw) if raw else None
        except json.JSONDecodeError:
            return error.code, {"raw": raw}
    except urllib.error.URLError as error:
        # 连不上服务是「前置没做好」，直接给出可执行的提示，不要堆栈
        print(f"[smoke] 连不上 {url}：{error.reason}")
        print("[smoke] 先执行 bash scripts/dev-up.sh")
        sys.exit(2)


def check(name: str, condition: bool, detail: str = "") -> None:
    global PASS, FAIL
    if condition:
        PASS += 1
        print(f"  ✓ {name}{('：' + detail) if detail else ''}")
    else:
        FAIL += 1
        print(f"  ✗ {name}{('：' + detail) if detail else ''}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8080", help="Java 服务地址")
    args = parser.parse_args()
    base = args.base.rstrip("/")
    username = f"smoke_{int(time.time())}"

    print("[smoke] 1/9 健康检查")
    status, health = call("GET", f"{base}/api/health")
    check("GET /api/health 200", status == 200, f"HTTP {status}")
    check(
        "数据库与分析服务双探活",
        bool(health) and health.get("database", {}).get("status") == "up"
        and health.get("analysis", {}).get("status") == "ok",
        f"db={health.get('database', {}).get('status')} analysis={health.get('analysis', {}).get('status')}"
        if health else "无响应体",
    )

    print("[smoke] 2/9 注册与档案")
    status, auth = call("POST", f"{base}/api/auth/register",
                        body={"username": username, "password": "Passw0rd!2026"})
    token = (auth or {}).get("accessToken")
    check("注册 201 且拿到令牌", status == 201 and bool(token), f"HTTP {status}")
    if not token:
        print("[smoke] 拿不到令牌，后续无法进行")
        return 1
    check("注册响应 role=student", (auth or {}).get("user", {}).get("role") == "student")

    status, _ = call("POST", f"{base}/api/auth/register",
                     body={"username": username, "password": "Passw0rd!2026"})
    check("重复注册 409", status == 409, f"HTTP {status}")

    status, profile = call("PUT", f"{base}/api/profile", token, {
        "realName": "李雷", "school": "示例大学", "major": "计算机科学与技术",
        "grade": "大三", "targetJobType": "Java 后端", "targetCity": "杭州",
    })
    check("档案写入 200 且中文回读一致", status == 200 and profile.get("realName") == "李雷")

    status, overview = call("GET", f"{base}/api/stats/overview", token)
    check("档案完善后 overview.profileCompleted=true", status == 200 and overview.get("profileCompleted") is True)

    print("[smoke] 3/9 岗位与解析")
    status, job = call("POST", f"{base}/api/jobs", token, {
        "title": "Java 后端开发实习生", "companyName": "示例科技", "city": "杭州",
        "salaryMin": 200, "salaryMax": 300, "source": "手动粘贴", "rawDescription": JD,
    })
    job_id = (job or {}).get("id")
    check("创建岗位 201", status == 201 and job_id is not None, f"jobId={job_id}")

    status, parsed = call("POST", f"{base}/api/jobs/{job_id}/parse", token)
    suggestions = (parsed or {}).get("requirements", [])
    check("解析 200 且命中技能", status == 200 and len(suggestions) > 0, f"{len(suggestions)} 项")
    check("每条建议带原文定位", all("sourceStart" in item and "evidenceHint" in item for item in suggestions))

    items = [
        {"skillId": item["skillId"], "reqType": item["reqType"], "weight": item["weight"],
         "targetLevel": item["targetLevel"], "anyOf": [],
         "evidenceHint": item.get("evidenceHint"),
         "sourceSpan": f'{item["sourceStart"]}-{item["sourceEnd"]}'}
        for item in suggestions[:3]
    ]
    status, saved = call("PUT", f"{base}/api/jobs/{job_id}/requirements", token, {"items": items})
    check("保存要求 200（整体覆盖）", status == 200 and len(saved.get("items", [])) == 3)

    status, detail = call("GET", f"{base}/api/jobs/{job_id}", token)
    check("保存后 parseStatus=confirmed", status == 200 and detail.get("parseStatus") == "confirmed")

    print("[smoke] 4/9 自评与证据")
    status, mine = call("PUT", f"{base}/api/user-skills", token, {
        "items": [{"skillId": items[0]["skillId"], "level": 1},
                  {"skillId": items[1]["skillId"], "level": 2},
                  {"skillId": items[2]["skillId"], "level": 1}],
    })
    check("批量自评 200", status == 200 and len(mine.get("items", [])) == 3)

    status, evidence = call("POST", f"{base}/api/evidence", token, {
        "evidenceType": "repo", "title": "校园二手交易平台", "url": "https://github.com/demo/trade",
        "description": "独立完成后端接口", "completeness": 70,
        "skills": [{"skillId": items[0]["skillId"], "relevance": 85}],
    })
    check("新增证据 201 且默认未核验", status == 201 and evidence.get("verifyStatus") == "unverified")

    status, coverage = call("GET", f"{base}/api/evidence/coverage", token)
    check("证据覆盖面可算（有岗位后不再是 null）",
          status == 200 and coverage.get("requiredTotal") == 3 and coverage.get("coverage") is not None,
          f"coverage={coverage.get('coverage')}")

    print("[smoke] 5/9 匹配")
    status, match = call("POST", f"{base}/api/match/{job_id}", token)
    score_before = (match or {}).get("requiredScore")
    check("匹配 200 且 status=ok", status == 200 and match.get("status") == "ok",
          f"status={match.get('status')}")
    check("必备分与加分分分开（无加分要求时为 null）",
          score_before is not None and match.get("bonusScore") is None,
          f"required={score_before} bonus={match.get('bonusScore')}")
    check("雷达恒 6 轴", len(match.get("radar", {}).get("axes", [])) == 6)
    check("差距清单非空且按优先级排序",
          len(match.get("gaps", [])) > 0
          and all(match["gaps"][i]["priority"] >= match["gaps"][i + 1]["priority"]
                  for i in range(len(match["gaps"]) - 1)))

    print("[smoke] 6/9 任务闭环")
    status, created = call("POST", f"{base}/api/tasks/from-gap/{job_id}", token, {"topN": 2})
    check("按差距生成任务 201", status == 201 and created.get("created", 0) > 0,
          f"created={created.get('created')}")
    task = (created.get("items") or [None])[0]
    if task:
        status, done = call("POST", f"{base}/api/tasks/{task['id']}/complete", token,
                            {"updateSkillLevel": True, "level": 3})
        check("完成任务并重算", status == 200 and done.get("recalculated") is True,
              f"recalculated={done.get('recalculated')}")

        status, reports = call("GET", f"{base}/api/reports?jobId={job_id}", token)
        latest = (reports.get("items") or [None])[0]
        check("重算后分数不降（等级从 L1/L2 提到 L3）",
              status == 200 and latest is not None and latest["requiredScore"] >= score_before,
              f"{score_before} → {latest.get('requiredScore') if latest else '-'}")
    else:
        check("完成任务并重算", False, "没有生成任务")

    status, report = call("POST", f"{base}/api/reports/{job_id}", token)
    check("存档报告 201 且带 id", status == 201 and report.get("id") is not None)

    print("[smoke] 7/9 投递清单（二期）")
    status, application = call("POST", f"{base}/api/applications", token,
                               {"jobPositionId": job_id, "note": "内推"})
    app_id = (application or {}).get("id")
    check("投递 201 且自动带出岗位名", status == 201 and application.get("title") == "Java 后端开发实习生")
    status, _ = call("POST", f"{base}/api/applications", token, {"jobPositionId": job_id})
    check("同一岗位重复投递 409", status == 409, f"HTTP {status}")

    status, moved = call("PATCH", f"{base}/api/applications/{app_id}", token, {"status": "applied"})
    check("状态切换留下时间线", status == 200 and len(moved.get("statusLog", [])) == 1
          and moved["statusLog"][0]["to"] == "applied")

    print("[smoke] 8/9 面试复盘（二期）")
    status, interview = call("POST", f"{base}/api/interviews", token, {
        "applicationId": app_id, "interviewDate": "2026-11-20", "round": "一面", "format": "video",
        "questions": [{"question": "讲讲 Spring 事务传播", "selfRating": 3, "note": "没答全"}],
        "reflection": "八股要背，项目细节要能量化", "relatedSkills": [items[0]["skillId"]],
    })
    check("面试记录 201 且继承投递信息", status == 201 and interview.get("companyName") == "示例科技")
    check("题目与关联技能都落库", len(interview.get("questions", [])) == 1
          and len(interview.get("relatedSkills", [])) == 1)

    print("[smoke] 9/9 数据看板")
    status, insights = call("GET", f"{base}/api/stats/insights", token)
    check("看板 200 且包含四块聚合", status == 200
          and {"applicationStatus", "interviewResult", "matchTrend", "topGaps", "taskCompletion"}
          <= set(insights or {}))
    check("任务完成率含真实分母", (insights or {}).get("taskCompletion", {}).get("total", 0) >= 1,
          f"{insights.get('taskCompletion') if insights else '-'}")

    print()
    print(f"[smoke] 通过 {PASS} 项 / 失败 {FAIL} 项")
    print(f"[smoke] 本次账号：{username}（清理：DELETE FROM `user` WHERE username LIKE 'smoke_%';）")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
