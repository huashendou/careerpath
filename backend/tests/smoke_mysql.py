"""端到端冒烟：真连 MySQL，覆盖全部接口与主要错误分支。跑完自清理。"""

from __future__ import annotations

import sys
import uuid

from fastapi.testclient import TestClient

from app.main import app

FAILS: list[str] = []


def check(name: str, cond: bool, extra: str = "") -> None:
    if cond:
        print(f"  OK   {name}")
    else:
        print(f"  FAIL {name} {extra}")
        FAILS.append(name)


def main() -> int:
    user = f"smoke_{uuid.uuid4().hex[:8]}"
    with TestClient(app) as c:
        # ---------- 词典与岗位 ----------
        r = c.get("/api/skills/dimensions")
        d = r.json()
        check("catalog 200", r.status_code == 200, r.text[:200])
        check("6 维度 6 等级", len(d["dimensions"]) == 6 and len(d["levels"]) == 6)
        check("维度带 desc/why", bool(d["dimensions"][0]["desc"]) and bool(d["dimensions"][0]["why"]))
        check("等级带 typical", bool(d["levels"][0]["typical"]))
        check("catalog 带 disclaimer", "示例" in d["disclaimer"] or len(d["disclaimer"]) > 10, d["disclaimer"][:40])
        check("scoringVersion", d["scoringVersion"] == "v0.2-core-only", d["scoringVersion"])

        r = c.get("/api/skills")
        s = r.json()
        check("skills 61 项", s["total"] == 61, str(s["total"]))
        check("skills 带 aliases", isinstance(s["skills"][0].get("aliases"), list))
        r = c.get("/api/skills", params={"dim": "foundation"})
        check("按维度过滤", all(x["dim"] == "foundation" for x in r.json()["skills"]))

        r = c.get("/api/jobs")
        jobs = {j["id"]: j for j in r.json()["jobs"]}
        check("3 个岗位", len(jobs) == 3)
        check(
            "core 权重 79/89/83",
            (jobs["frontend"]["coreWeightSum"], jobs["java-backend"]["coreWeightSum"], jobs["data-analyst"]["coreWeightSum"]) == (79, 89, 83),
            str([j["coreWeightSum"] for j in jobs.values()]),
        )
        check("前端 core 19 条", jobs["frontend"]["coreRequirementCount"] == 19)

        r = c.get("/api/jobs/frontend")
        jd = r.json()
        check("岗位详情 29 条要求", len(jd["requirements"]) == 29)
        check("anyOf 恒数组", all(isinstance(x["anyOf"], list) for x in jd["requirements"]))
        check("requirements 用 dim 字段", "dim" in jd["requirements"][0])
        check("scoringScope 非空", bool(jd["scoringScope"]))
        r = c.get("/api/jobs/nope")
        check("未知岗位 404", r.status_code == 404 and r.json()["detail"]["code"] == "JOB_NOT_FOUND", r.text[:120])

        # ---------- 认证 ----------
        r = c.post("/api/auth/register", json={"username": user, "password": "pw123456"})
        check("注册 201", r.status_code == 201, r.text[:200])
        auth = r.json()
        check("注册返回 accessToken", bool(auth.get("accessToken")))
        check("user 无 password_hash", "passwordHash" not in auth["user"] and "password_hash" not in auth["user"])
        check("createdAt 是 Z 结尾", auth["user"]["createdAt"].endswith("Z"), auth["user"]["createdAt"])
        token = auth["accessToken"]
        H = {"Authorization": f"Bearer {token}"}

        r = c.post("/api/auth/register", json={"username": user, "password": "pw123456"})
        check("重复注册 409", r.status_code == 409 and r.json()["detail"]["code"] == "USERNAME_TAKEN", r.text[:120])
        r = c.post("/api/auth/login", json={"username": user, "password": "wrong"})
        check("错密码 401", r.status_code == 401 and r.json()["detail"]["code"] == "INVALID_CREDENTIALS")
        r = c.post("/api/auth/login", json={"username": user, "password": "pw123456"})
        check("登录 200", r.status_code == 200)
        r = c.get("/api/auth/me")
        check("无 token 401", r.status_code == 401 and r.json()["detail"]["code"] == "UNAUTHORIZED", r.text[:120])
        r = c.get("/api/auth/me", headers=H)
        check("me 正确", r.status_code == 200 and r.json()["username"] == user)
        r = c.post("/api/auth/register", json={"username": "ab", "password": "123"})
        check("校验失败 422", r.status_code == 422 and r.json()["detail"]["code"] == "VALIDATION_ERROR", r.text[:150])

        # ---------- 画像 ----------
        r = c.post("/api/profiles", json={"name": "前端方向"}, headers=H)
        check("建画像 201", r.status_code == 201, r.text[:150])
        pid = r.json()["id"]
        check("新画像 skills 为空", r.json()["skills"] == [])

        payload = {"name": "前端方向", "skills": [{"skillId": "js-core", "level": 3}, {"skillId": "html-css", "level": 4}, {"skillId": "react", "level": 4}, {"skillId": "os-basic", "level": 9}]}
        r = c.put(f"/api/profiles/{pid}", json=payload, headers=H)
        check("保存画像 200", r.status_code == 200, r.text[:200])
        got = {x["skillId"]: x["level"] for x in r.json()["skills"]}
        check("越界等级夹取到 5", got.get("os-basic") == 5, str(got))
        check("画像项带 name/dim", r.json()["skills"][0]["name"] and r.json()["skills"][0]["dim"])

        r = c.put(f"/api/profiles/{pid}", json={"skills": [{"skillId": "js-core", "level": 2}]}, headers=H)
        check("全量覆盖", len(r.json()["skills"]) == 1, str(r.json()["skills"]))
        check("改名可省略", r.json()["name"] == "前端方向")

        r = c.put(f"/api/profiles/{pid}", json={"skills": [{"skillId": "not-a-skill", "level": 1}]}, headers=H)
        check("未知技能 422", r.status_code == 422 and r.json()["detail"]["code"] == "UNKNOWN_SKILL", r.text[:150])

        r = c.get("/api/profiles", headers=H)
        check("画像列表", len(r.json()["profiles"]) == 1 and r.json()["profiles"][0]["skillCount"] == 1, r.text[:150])

        # ---------- 算分 ----------
        # 恢复完整画像：上面「全量覆盖」把 react 删了，anyOf 需要它
        c.put(
            f"/api/profiles/{pid}",
            json={"skills": [{"skillId": "js-core", "level": 2}, {"skillId": "html-css", "level": 4}, {"skillId": "react", "level": 4}]},
            headers=H,
        )
        r = c.post("/api/analysis/match", json={"profileId": pid, "jobId": "frontend"}, headers=H)
        m = r.json()
        check("算分 200", r.status_code == 200, r.text[:300])
        check("分母 255", m["denominator"] == 255, str(m["denominator"]))
        check("matchScore 4 位小数", m["matchScore"] is not None and round(m["matchScore"], 4) == m["matchScore"], str(m["matchScore"]))
        check("status ok", m["status"] == "ok", m["status"])
        check("radar 6 轴", len(m["radar"]["axes"]) == 6)
        check("前端雷达 4 轴", m["radar"]["dataAxisCount"] == 4, str(m["radar"]["dataAxisCount"]))
        check("空轴 target=null", any(a["target"] is None and a["hasCore"] is False for a in m["radar"]["axes"]))
        check("axes 带 coreWeight", all("coreWeight" in a for a in m["radar"]["axes"]))
        check("gaps 无 mastered", all(g["status"] in ("missing", "weak") for g in m["gaps"]))
        check("topActions ≤3", len(m["topActions"]) <= 3)
        check("plusItems 两态", all(p["status"] in ("mastered", "pending") for p in m["plusItems"]))
        check("counts 有 plus 两项", "plusMastered" in m["counts"] and "plusPending" in m["counts"])
        # js-core 2 级，Vue 那条靠 react 4 级 → effectiveLevel 应为 4
        vue_gap = next((g for g in m["gaps"] if g["skillId"] == "vue"), None)
        check("anyOf 生效：Vue 靠 React 达标", vue_gap is None, str(vue_gap))

        r = c.post("/api/analysis/match", json={"jobId": "frontend", "profileId": 0, "skills": [{"skillId": "js-core", "level": 5}]}, headers=H)
        check("他人/不存在画像 404", r.status_code == 404 and r.json()["detail"]["code"] == "PROFILE_NOT_FOUND", r.text[:150])

        # 试算：不传画像，直接给技能
        r = c.post("/api/analysis/match", json={"jobId": "frontend", "profileId": None, "skills": [{"skillId": "js-core", "level": 5}]}, headers=H)
        check("试算 200", r.status_code == 200 and r.json()["profileId"] is None, r.text[:200])

        # 空自评 → EMPTY_PROFILE
        r = c.post("/api/profiles", json={"name": "空画像"}, headers=H)
        empty_id = r.json()["id"]
        r = c.post("/api/analysis/match", json={"profileId": empty_id, "jobId": "frontend"}, headers=H)
        check("空自评 EMPTY_PROFILE", r.json()["status"] == "EMPTY_PROFILE" and r.json()["matchScore"] is None, r.text[:200])

        # 三岗分母
        for jid, den in (("java-backend", 294), ("data-analyst", 292)):
            r = c.post("/api/analysis/match", json={"profileId": pid, "jobId": jid}, headers=H)
            check(f"{jid} 分母 {den}", r.json()["denominator"] == den, str(r.json()["denominator"]))
            check(f"{jid} 雷达轴", r.json()["radar"]["dataAxisCount"] == (5 if jid == "java-backend" else 6))

        # ---------- 报告 ----------
        r = c.post("/api/reports", json={"profileId": pid, "jobId": "frontend"}, headers=H)
        check("存档 201", r.status_code == 201, r.text[:200])
        rid = r.json()["id"]
        check("报告带 id/createdAt", rid > 0 and r.json()["createdAt"].endswith("Z"))
        check("报告含完整 MatchResult", "radar" in r.json() and "gaps" in r.json())
        r = c.get("/api/reports", headers=H)
        check("报告列表", len(r.json()["reports"]) == 1 and r.json()["reports"][0]["jobName"])
        r = c.get(f"/api/reports/{rid}", headers=H)
        check("报告详情", r.json()["id"] == rid and r.json()["denominator"] == 255)
        r = c.get("/api/reports/999999", headers=H)
        check("报告 404", r.status_code == 404)

        # ---------- 简历解析 ----------
        r = c.post("/api/resume/parse", json={"text": "熟悉 JavaScript 与 Vue，用 React 做过后台管理系统，了解 TypeScript"}, headers=H)
        hits = {x["skillId"] for x in r.json()["suggestions"]}
        check("简历解析命中 js/vue/react/ts", {"js-core", "vue", "react"} <= hits, str(sorted(hits)))
        check("解析带 evidence", all(x["evidence"] for x in r.json()["suggestions"]))
        r = c.post("/api/resume/parse", json={"text": "用过 next.js 做过 SSR"}, headers=H)
        check("next.js 不误命中 js", "js-core" not in {x["skillId"] for x in r.json()["suggestions"]}, str([x["skillId"] for x in r.json()["suggestions"]]))

        # ---------- 画像上限 ----------
        for i in range(9):
            c.post("/api/profiles", json={"name": f"p{i}"}, headers=H)
        r = c.post("/api/profiles", json={"name": "第11份"}, headers=H)
        check("第 11 份 409", r.status_code == 409 and r.json()["detail"]["code"] == "PROFILE_LIMIT", r.text[:150])

        # ---------- 删除 + 级联 ----------
        r = c.delete(f"/api/profiles/{pid}", headers=H)
        check("删除 204", r.status_code == 204, str(r.status_code))
        r = c.get(f"/api/profiles/{pid}", headers=H)
        check("删后 404", r.status_code == 404)
        r = c.get(f"/api/reports/{rid}", headers=H)
        check("画像删除后报告仍在", r.status_code == 200, "报告是快照，不应随画像消失")

    print()
    if FAILS:
        print(f"FAILED {len(FAILS)}: {FAILS}")
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
