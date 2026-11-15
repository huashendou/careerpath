"""`/internal/match` 契约与口径测试。

重点守三条红线：
1. **证据不进分数**：同一份自评，加满证据后 `matchScore` 必须逐位相等；
2. **加分项不进分母**：`denominator` 只由 required 组成，前端岗是 255 不是 100；
3. **空集不返回 0**：空自评 `EMPTY_PROFILE`、无必备要求 `NO_CORE_REQUIREMENT`，两者都是 `null`。
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.core.config import settings
from app.main import app

TOKEN = {"X-Internal-Token": settings.internal_token}
client = TestClient(app)

DATA_DIR = Path(__file__).resolve().parents[2] / "data"


def _jobs() -> list[dict]:
    return json.loads((DATA_DIR / "jobs.json").read_text(encoding="utf-8"))["jobs"]


def _job(job_id: str) -> dict:
    return next(j for j in _jobs() if j["id"] == job_id)


def _requirements(job: dict, only: str | None = None) -> list[dict]:
    """把种子里的 core/plus 转成接口的 required/bonus。"""
    out = []
    for r in job["requirements"]:
        req_type = "required" if r["type"] == "core" else "bonus"
        if only and req_type != only:
            continue
        out.append(
            {
                "skillId": r["skillId"],
                "reqType": req_type,
                "weight": r["weight"],
                "targetLevel": r["target"],
                "anyOf": r.get("anyOf") or [],
            }
        )
    return out


def _post(payload: dict) -> dict:
    resp = client.post("/internal/match", json=payload, headers=TOKEN)
    assert resp.status_code == 200, resp.text
    return resp.json()


def test_match_requires_internal_token() -> None:
    resp = client.post("/internal/match", json={"jobId": "x", "requirements": []})
    assert resp.status_code == 401
    assert resp.json()["detail"]["code"] == "INTERNAL_UNAUTHORIZED"


def test_frontend_denominator_is_255_not_100() -> None:
    """分母 = Σ_required(w×T) = 255。这条红了说明有人把权重归一化到 100 了。"""
    job = _job("frontend")
    body = _post(
        {
            "jobId": job["id"],
            "jobName": job["name"],
            "requirements": _requirements(job),
            "userSkills": [{"skillId": "js-core", "level": 3}],
        }
    )
    assert body["denominator"] == 255
    # 分子 = Σ w×min(L,T)，只算了一条 js-core
    core = {r["skillId"]: r for r in job["requirements"] if r["type"] == "core"}
    assert body["numerator"] == core["js-core"]["weight"] * min(3, core["js-core"]["target"])


def test_evidence_does_not_change_score() -> None:
    """红线：加满证据，分数一位都不能变。"""
    job = _job("frontend")
    base = {
        "jobId": job["id"],
        "jobName": job["name"],
        "requirements": _requirements(job),
        "userSkills": [{"skillId": "js-core", "level": 2}, {"skillId": "html-css", "level": 3}],
    }
    without = _post(base)
    with_evidence = _post(
        {
            **base,
            "evidence": [
                {"skillId": "js-core", "strength": 100},
                {"skillId": "html-css", "strength": 80},
            ],
        }
    )

    assert without["matchScore"] == with_evidence["matchScore"]
    assert without["numerator"] == with_evidence["numerator"]
    assert without["denominator"] == with_evidence["denominator"]
    # 变的只有证据覆盖面
    assert without["evidenceCoverage"] == 0.0
    assert with_evidence["evidenceCoverage"] > 0.0
    assert with_evidence["counts"]["evidenceCovered"] == 2


def test_empty_profile_returns_null_not_zero() -> None:
    job = _job("frontend")
    body = _post(
        {
            "jobId": job["id"],
            "jobName": job["name"],
            "requirements": _requirements(job),
            "userSkills": [],
        }
    )
    assert body["status"] == "EMPTY_PROFILE"
    assert body["matchScore"] is None
    assert body["gaps"]  # 缺口照样列出来，只是不给百分比


def test_no_required_requirement_returns_null() -> None:
    """只有加分项的岗位：无法算分，但加分项清单要照常给。"""
    job = _job("frontend")
    body = _post(
        {
            "jobId": job["id"],
            "jobName": job["name"],
            "requirements": _requirements(job, only="bonus"),
            "userSkills": [{"skillId": "js-core", "level": 5}],
        }
    )
    assert body["status"] == "NO_CORE_REQUIREMENT"
    assert body["matchScore"] is None
    assert body["denominator"] == 0
    assert len(body["plusItems"]) > 0


def test_radar_empty_dimension_is_null_not_zero() -> None:
    job = _job("frontend")
    body = _post(
        {
            "jobId": job["id"],
            "jobName": job["name"],
            "requirements": _requirements(job),
            "userSkills": [{"skillId": "js-core", "level": 4}],
        }
    )
    axes = {a["dim"]: a for a in body["radar"]["axes"]}
    assert len(axes) == 6
    # 前端岗没有「数据与存储」「洞察与业务」的必备要求
    for dim in ("data", "insight"):
        assert axes[dim]["target"] is None
        assert axes[dim]["current"] is None
        assert axes[dim]["hasCore"] is False
    assert body["radar"]["dataAxisCount"] == 4


def test_anyof_counted_once() -> None:
    """Vue / React 二选一：只出一条要求，取最高等级，权重只加一次。"""
    payload = {
        "jobId": "anyof-case",
        "requirements": [
            {"skillId": "vue", "reqType": "required", "weight": 9, "targetLevel": 4, "anyOf": ["react"]}
        ],
        "userSkills": [{"skillId": "react", "level": 5}],
    }
    body = _post(payload)
    assert body["denominator"] == 36  # 9 × 4，不是 72
    assert body["numerator"] == 36  # React 5 级 → L_eff=5，取 min(5,4)=4
    assert body["matchScore"] == 100.0
    assert body["gaps"] == []


def test_bonus_score_is_separate() -> None:
    job = _job("frontend")
    body = _post(
        {
            "jobId": job["id"],
            "jobName": job["name"],
            "requirements": _requirements(job),
            "userSkills": [{"skillId": "js-core", "level": 3}],
        }
    )
    assert body["bonusDenominator"] > 0
    assert body["bonusScore"] is not None
    # 两个分数各算各的，不是同一个数
    assert body["bonusScore"] != body["matchScore"]


def test_unknown_skill_dropped_with_warning(caplog: pytest.LogCaptureFixture) -> None:
    payload = {
        "jobId": "dirty",
        "requirements": [
            {"skillId": "js-core", "reqType": "required", "weight": 10, "targetLevel": 2},
            {"skillId": "ghost-skill", "reqType": "required", "weight": 90, "targetLevel": 2},
        ],
        "userSkills": [],
    }
    with caplog.at_level("WARNING"):
        body = _post(payload)
    assert body["denominator"] == 20  # 只留 js-core，脏数据不进分母
    assert any("ghost-skill" in r.getMessage() for r in caplog.records)
