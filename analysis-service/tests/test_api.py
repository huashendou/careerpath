"""接口层测试：契约形状、鉴权、越权隔离、错误体。

用 SQLite 跑，不依赖本机 MySQL。MySQL 的联通性由 `python seed.py` 单独验。
这里关心的不是算分对不对（那是 test_matching 的事），而是**前后端之间的那层壳**是否守住了约定。
"""

from __future__ import annotations

import re

import pytest

API = "/api"


# ---------------- 词典与岗位（免鉴权） ----------------


def test_catalog_has_dimensions_and_levels(client):
    body = client.get(f"{API}/skills/dimensions").json()
    assert len(body["dimensions"]) == 6
    assert len(body["levels"]) == 6
    assert body["dimensions"][0]["desc"] and body["dimensions"][0]["why"]
    assert body["levels"][0]["typical"]
    assert body["disclaimer"]
    assert body["scoringVersion"] == "v0.2-core-only"


def test_skills_dictionary(client):
    body = client.get(f"{API}/skills").json()
    assert body["total"] == 61
    assert all(s["aliases"] for s in body["skills"])
    filtered = client.get(f"{API}/skills", params={"dim": "foundation"}).json()
    assert filtered["total"] < 61
    assert {s["dim"] for s in filtered["skills"]} == {"foundation"}


def test_jobs_list_weights(client):
    jobs = {j["id"]: j for j in client.get(f"{API}/jobs").json()["jobs"]}
    assert set(jobs) == {"frontend", "java-backend", "data-analyst"}
    assert [jobs[j]["coreWeightSum"] for j in ("frontend", "java-backend", "data-analyst")] == [
        79,
        89,
        83,
    ]
    assert [jobs[j]["plusWeightSum"] for j in ("frontend", "java-backend", "data-analyst")] == [
        21,
        11,
        17,
    ]
    assert jobs["frontend"]["requirementCount"] == 29
    assert jobs["frontend"]["coreRequirementCount"] == 19
    assert "requirements" not in jobs["frontend"]  # 列表不传 86 条


def test_job_detail_shape(client):
    body = client.get(f"{API}/jobs/frontend").json()
    assert len(body["requirements"]) == 29
    assert body["scoringScope"]
    first = body["requirements"][0]
    assert set(first) >= {"skillId", "skillName", "dim", "weight", "target", "type", "anyOf"}
    assert isinstance(first["anyOf"], list)
    vue = next(r for r in body["requirements"] if r["skillId"] == "vue")
    assert vue["anyOf"] == ["react"] and vue["anyOfNote"]


def test_job_not_found(client):
    resp = client.get(f"{API}/jobs/does-not-exist")
    assert resp.status_code == 404
    assert resp.json() == {"detail": {"code": "JOB_NOT_FOUND", "message": "岗位不存在"}}


# ---------------- 认证 ----------------


def test_register_login_me(client):
    resp = client.post(f"{API}/auth/register", json={"username": "api_user_a", "password": "pw123456"})
    assert resp.status_code == 201
    body = resp.json()
    assert body["tokenType"] == "bearer"
    assert body["user"]["role"] == "student"
    assert re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", body["user"]["createdAt"])
    assert "password" not in str(body).lower() or "passwordHash" not in body["user"]

    token = body["accessToken"]
    me = client.get(f"{API}/auth/me", headers={"Authorization": f"Bearer {token}"})
    assert me.status_code == 200 and me.json()["username"] == "api_user_a"

    login = client.post(f"{API}/auth/login", json={"username": "api_user_a", "password": "pw123456"})
    assert login.status_code == 200 and login.json()["accessToken"]


def test_register_duplicate(client):
    payload = {"username": "api_user_dup", "password": "pw123456"}
    assert client.post(f"{API}/auth/register", json=payload).status_code == 201
    resp = client.post(f"{API}/auth/register", json=payload)
    assert resp.status_code == 409
    assert resp.json()["detail"]["code"] == "USERNAME_TAKEN"


def test_login_wrong_password(client):
    client.post(f"{API}/auth/register", json={"username": "api_user_pw", "password": "pw123456"})
    resp = client.post(f"{API}/auth/login", json={"username": "api_user_pw", "password": "nope"})
    assert resp.status_code == 401
    assert resp.json()["detail"]["code"] == "INVALID_CREDENTIALS"
    # 用户不存在与密码错误必须返回同一个错误，不能泄露账号是否注册过
    ghost = client.post(f"{API}/auth/login", json={"username": "ghost_user", "password": "nope"})
    assert ghost.json()["detail"]["code"] == "INVALID_CREDENTIALS"


def test_register_validation(client):
    resp = client.post(f"{API}/auth/register", json={"username": "ab", "password": "123"})
    assert resp.status_code == 422
    assert resp.json()["detail"]["code"] == "VALIDATION_ERROR"


def test_protected_endpoints_require_token(client):
    for path in ("/auth/me", "/profiles", "/reports"):
        resp = client.get(f"{API}{path}")
        assert resp.status_code == 401, path
        assert resp.json()["detail"]["code"] == "UNAUTHORIZED"


def test_forged_token_rejected(client):
    resp = client.get(f"{API}/auth/me", headers={"Authorization": "Bearer not.a.token"})
    assert resp.status_code == 401
    assert resp.json()["detail"]["code"] == "UNAUTHORIZED"


# ---------------- 画像 ----------------


def test_profile_crud(client, auth_headers):
    created = client.post(f"{API}/profiles", json={"name": "前端方向"}, headers=auth_headers)
    assert created.status_code == 201
    pid = created.json()["id"]
    assert created.json()["skills"] == []
    assert created.json()["createdAt"].endswith("Z")

    saved = client.put(
        f"{API}/profiles/{pid}",
        json={
            "name": "前端方向 v2",
            "skills": [
                {"skillId": "js-core", "level": 3},
                {"skillId": "html-css", "level": 4},
                {"skillId": "os-basic", "level": 9},
            ],
        },
        headers=auth_headers,
    )
    assert saved.status_code == 200
    levels = {s["skillId"]: s["level"] for s in saved.json()["skills"]}
    assert levels == {"js-core": 3, "html-css": 4, "os-basic": 5}  # 越界夹取
    assert saved.json()["name"] == "前端方向 v2"
    assert saved.json()["skills"][0]["name"] and saved.json()["skills"][0]["dim"]


def test_profile_put_is_full_replace(client, auth_headers):
    pid = client.post(f"{API}/profiles", json={"name": "p"}, headers=auth_headers).json()["id"]
    client.put(
        f"{API}/profiles/{pid}",
        json={"skills": [{"skillId": "js-core", "level": 3}, {"skillId": "html-css", "level": 2}]},
        headers=auth_headers,
    )
    replaced = client.put(
        f"{API}/profiles/{pid}",
        json={"skills": [{"skillId": "html-css", "level": 5}]},
        headers=auth_headers,
    )
    assert [s["skillId"] for s in replaced.json()["skills"]] == ["html-css"]
    assert replaced.json()["name"] == "p"  # 不传 name 不改名


def test_profile_unknown_skill(client, auth_headers):
    pid = client.post(f"{API}/profiles", json={"name": "p"}, headers=auth_headers).json()["id"]
    resp = client.put(
        f"{API}/profiles/{pid}",
        json={"skills": [{"skillId": "no-such-skill", "level": 1}]},
        headers=auth_headers,
    )
    assert resp.status_code == 422
    assert resp.json()["detail"]["code"] == "UNKNOWN_SKILL"


def test_profile_duplicate_skill_rejected(client, auth_headers):
    """同一 skillId 提交两次必须是 422，不能落到 UNIQUE 约束上变成 500。

    这条守两件事：错误码可读（4001 类业务码而不是驱动层约束名），以及校验失败时
    已有画像一个字都不改——入口拦截发生在 delete 之前。
    """
    pid = client.post(f"{API}/profiles", json={"name": "p"}, headers=auth_headers).json()["id"]
    client.put(
        f"{API}/profiles/{pid}",
        json={"skills": [{"skillId": "js-core", "level": 3}, {"skillId": "html-css", "level": 4}]},
        headers=auth_headers,
    )

    resp = client.put(
        f"{API}/profiles/{pid}",
        json={
            "skills": [
                {"skillId": "js-core", "level": 5},
                {"skillId": "html-css", "level": 4},
                {"skillId": "js-core", "level": 2},
            ]
        },
        headers=auth_headers,
    )
    assert resp.status_code == 422
    body = resp.json()
    assert body["detail"]["code"] == "VALIDATION_ERROR"
    assert "js-core" in body["detail"]["message"]

    kept = client.get(f"{API}/profiles/{pid}", headers=auth_headers).json()["skills"]
    assert {s["skillId"]: s["level"] for s in kept} == {"js-core": 3, "html-css": 4}


def test_profile_limit(client, auth_headers):
    for index in range(10):
        resp = client.post(f"{API}/profiles", json={"name": f"p{index}"}, headers=auth_headers)
        assert resp.status_code == 201, index
    over = client.post(f"{API}/profiles", json={"name": "第11份"}, headers=auth_headers)
    assert over.status_code == 409
    assert over.json()["detail"]["code"] == "PROFILE_LIMIT"


def test_profile_isolation(client):
    """A 的画像 B 必须看不到，且返回 404 而不是 403——403 等于承认这个 id 存在。"""
    def new_user(name: str) -> dict:
        token = client.post(
            f"{API}/auth/register", json={"username": name, "password": "pw123456"}
        ).json()["accessToken"]
        return {"Authorization": f"Bearer {token}"}

    a, b = new_user("iso_user_a"), new_user("iso_user_b")
    pid = client.post(f"{API}/profiles", json={"name": "A 的画像"}, headers=a).json()["id"]

    assert client.get(f"{API}/profiles/{pid}", headers=b).status_code == 404
    assert client.put(f"{API}/profiles/{pid}", json={"name": "篡改"}, headers=b).status_code == 404
    assert client.delete(f"{API}/profiles/{pid}", headers=b).status_code == 404
    assert len(client.get(f"{API}/profiles", headers=b).json()["profiles"]) == 0
    assert client.get(f"{API}/profiles/{pid}", headers=a).status_code == 200


def test_profile_delete_cascades(client, auth_headers):
    pid = client.post(f"{API}/profiles", json={"name": "p"}, headers=auth_headers).json()["id"]
    client.put(
        f"{API}/profiles/{pid}", json={"skills": [{"skillId": "js-core", "level": 3}]}, headers=auth_headers
    )
    assert client.delete(f"{API}/profiles/{pid}", headers=auth_headers).status_code == 204
    assert client.get(f"{API}/profiles/{pid}", headers=auth_headers).status_code == 404
    assert client.get(f"{API}/profiles", headers=auth_headers).json()["profiles"] == []


# ---------------- 算分 ----------------


@pytest.fixture
def filled_profile(client, auth_headers) -> int:
    pid = client.post(f"{API}/profiles", json={"name": "测试画像"}, headers=auth_headers).json()["id"]
    client.put(
        f"{API}/profiles/{pid}",
        json={
            "skills": [
                {"skillId": "js-core", "level": 3},
                {"skillId": "html-css", "level": 4},
                {"skillId": "react", "level": 4},
            ]
        },
        headers=auth_headers,
    )
    return pid


def test_match_with_profile(client, auth_headers, filled_profile):
    body = client.post(
        f"{API}/analysis/match",
        json={"profileId": filled_profile, "jobId": "frontend"},
        headers=auth_headers,
    ).json()
    assert body["profileId"] == filled_profile
    assert body["denominator"] == 255
    assert body["status"] == "ok"
    assert body["matchScore"] is not None
    assert len(body["radar"]["axes"]) == 6
    assert body["radar"]["dataAxisCount"] == 4
    assert len(body["topActions"]) <= 3
    assert body["meta"]["scoringVersion"] == "v0.2-core-only"
    # react 4 级让 vue 那条达标，因此不出现缺口，也不单独出现 react
    assert "vue" not in [g["skillId"] for g in body["gaps"]]
    assert "react" not in [g["skillId"] for g in body["gaps"]]


def test_match_trial_without_profile(client, auth_headers):
    """profileId 为 null 时用 skills 试算，不落库。"""
    body = client.post(
        f"{API}/analysis/match",
        json={"jobId": "frontend", "skills": [{"skillId": "js-core", "level": 5}]},
        headers=auth_headers,
    ).json()
    assert body["profileId"] is None
    assert body["status"] == "ok"
    assert client.get(f"{API}/profiles", headers=auth_headers).json()["profiles"] == []


def test_match_requires_profile_or_skills(client, auth_headers):
    resp = client.post(
        f"{API}/analysis/match", json={"jobId": "frontend", "profileId": None}, headers=auth_headers
    )
    assert resp.status_code == 422
    assert resp.json()["detail"]["code"] == "VALIDATION_ERROR"


def test_match_empty_profile_returns_null_score(client, auth_headers):
    pid = client.post(f"{API}/profiles", json={"name": "空"}, headers=auth_headers).json()["id"]
    body = client.post(
        f"{API}/analysis/match", json={"profileId": pid, "jobId": "frontend"}, headers=auth_headers
    ).json()
    assert body["status"] == "EMPTY_PROFILE"
    assert body["matchScore"] is None  # 前端据此显示「尚未完成自评」，不是 0%


def test_match_three_jobs_denominators(client, auth_headers, filled_profile):
    for job_id, denominator, axis in (
        ("frontend", 255, 4),
        ("java-backend", 294, 5),
        ("data-analyst", 292, 6),
    ):
        body = client.post(
            f"{API}/analysis/match",
            json={"profileId": filled_profile, "jobId": job_id},
            headers=auth_headers,
        ).json()
        assert body["denominator"] == denominator, job_id
        assert body["radar"]["dataAxisCount"] == axis, job_id


def test_match_unknown_job(client, auth_headers, filled_profile):
    resp = client.post(
        f"{API}/analysis/match", json={"profileId": filled_profile, "jobId": "nope"}, headers=auth_headers
    )
    assert resp.status_code == 404
    assert resp.json()["detail"]["code"] == "JOB_NOT_FOUND"


def test_match_unknown_skill_in_trial(client, auth_headers):
    resp = client.post(
        f"{API}/analysis/match",
        json={"jobId": "frontend", "skills": [{"skillId": "zzz", "level": 1}]},
        headers=auth_headers,
    )
    assert resp.status_code == 422
    assert resp.json()["detail"]["code"] == "UNKNOWN_SKILL"


def test_match_other_users_profile_404(client):
    def new_user(name: str) -> dict:
        token = client.post(
            f"{API}/auth/register", json={"username": name, "password": "pw123456"}
        ).json()["accessToken"]
        return {"Authorization": f"Bearer {token}"}

    a, b = new_user("match_user_a"), new_user("match_user_b")
    pid = client.post(f"{API}/profiles", json={"name": "A"}, headers=a).json()["id"]
    resp = client.post(
        f"{API}/analysis/match", json={"profileId": pid, "jobId": "frontend"}, headers=b
    )
    assert resp.status_code == 404
    assert resp.json()["detail"]["code"] == "PROFILE_NOT_FOUND"


# ---------------- 报告 ----------------


def test_report_snapshot_flow(client, auth_headers, filled_profile):
    created = client.post(
        f"{API}/reports", json={"profileId": filled_profile, "jobId": "frontend"}, headers=auth_headers
    )
    assert created.status_code == 201
    report = created.json()
    assert report["id"] > 0 and report["createdAt"].endswith("Z")
    assert report["radar"] and report["gaps"] is not None and report["denominator"] == 255

    listed = client.get(f"{API}/reports", headers=auth_headers).json()["reports"]
    assert len(listed) == 1 and listed[0]["jobName"]

    detail = client.get(f"{API}/reports/{report['id']}", headers=auth_headers).json()
    assert detail == report


def test_report_survives_profile_deletion(client, auth_headers, filled_profile):
    rid = client.post(
        f"{API}/reports", json={"profileId": filled_profile, "jobId": "frontend"}, headers=auth_headers
    ).json()["id"]
    client.delete(f"{API}/profiles/{filled_profile}", headers=auth_headers)
    assert client.get(f"{API}/reports/{rid}", headers=auth_headers).status_code == 200


def test_report_isolation(client):
    def new_user(name: str) -> dict:
        token = client.post(
            f"{API}/auth/register", json={"username": name, "password": "pw123456"}
        ).json()["accessToken"]
        return {"Authorization": f"Bearer {token}"}

    a, b = new_user("rep_user_a"), new_user("rep_user_b")
    pid = client.post(f"{API}/profiles", json={"name": "A"}, headers=a).json()["id"]
    rid = client.post(
        f"{API}/reports", json={"profileId": pid, "jobId": "frontend"}, headers=a
    ).json()["id"]
    assert client.get(f"{API}/reports/{rid}", headers=b).status_code == 404
    assert client.get(f"{API}/reports", headers=b).json()["reports"] == []


# ---------------- 简历解析 ----------------


def test_resume_parse(client, auth_headers):
    body = client.post(
        f"{API}/resume/parse",
        json={"text": "熟悉 JavaScript 与 Vue，用 React 做过后台管理系统，了解 TypeScript。"},
        headers=auth_headers,
    ).json()
    ids = {s["skillId"] for s in body["suggestions"]}
    assert {"js-core", "vue", "react"} <= ids
    assert all(s["evidence"] and s["matchedAlias"] for s in body["suggestions"])
    assert all(0 <= s["level"] <= 5 for s in body["suggestions"])
    assert all(s["dim"] for s in body["suggestions"])


def test_resume_parse_avoids_substring_trap(client, auth_headers):
    """`js` 不能命中 `next.js`——英文别名要带词边界。"""
    body = client.post(
        f"{API}/resume/parse", json={"text": "用 next.js 做过服务端渲染"}, headers=auth_headers
    ).json()
    assert "js-core" not in {s["skillId"] for s in body["suggestions"]}


def test_resume_parse_requires_auth(client):
    resp = client.post(f"{API}/resume/parse", json={"text": "javascript"})
    assert resp.status_code == 401


# ---------------- 契约整洁度 ----------------


def test_no_snake_case_leaks(client, auth_headers, filled_profile):
    """任何响应里都不该出现 snake_case 字段——出现就说明 ORM 字段漏出去了。"""
    snake = re.compile(r"^[a-z]+(_[a-z]+)+$")

    def walk(node, path=""):
        if isinstance(node, dict):
            for key, value in node.items():
                assert not snake.match(key), f"snake_case 泄漏：{path}.{key}"
                walk(value, f"{path}.{key}")
        elif isinstance(node, list):
            for item in node:
                walk(item, path)

    responses = [
        client.get(f"{API}/skills/dimensions").json(),
        client.get(f"{API}/skills").json(),
        client.get(f"{API}/jobs").json(),
        client.get(f"{API}/jobs/frontend").json(),
        client.get(f"{API}/auth/me", headers=auth_headers).json(),
        client.get(f"{API}/profiles", headers=auth_headers).json(),
        client.get(f"{API}/profiles/{filled_profile}", headers=auth_headers).json(),
        client.post(
            f"{API}/analysis/match",
            json={"profileId": filled_profile, "jobId": "frontend"},
            headers=auth_headers,
        ).json(),
    ]
    for body in responses:
        walk(body)


def test_password_hash_never_returned(client, auth_headers):
    text = client.get(f"{API}/auth/me", headers=auth_headers).text.lower()
    assert "password" not in text
    assert "$2b$" not in text  # bcrypt 前缀


def test_validation_error_shape(client):
    """请求体多传字段要 422，不能静默忽略——否则前端写错字段名会以为生效了。"""
    resp = client.post(f"{API}/auth/login", json={"username": "x", "password": "y", "extra": 1})
    assert resp.status_code == 422
    assert resp.json()["detail"]["code"] == "VALIDATION_ERROR"
