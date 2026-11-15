"""内部接口的契约测试：密钥闸门、健康检查、JD 解析出参形状。"""

from __future__ import annotations

from fastapi.testclient import TestClient

from app.core.config import settings
from app.main import app

TOKEN_HEADER = {"X-Internal-Token": settings.internal_token}

client = TestClient(app)

JD = "岗位要求：熟练掌握 JavaScript 核心语法，熟悉 Redis，有开源项目者优先。"


def test_health_requires_internal_token() -> None:
    response = client.get("/internal/health")
    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "INTERNAL_UNAUTHORIZED"


def test_health_ok_with_token() -> None:
    response = client.get("/internal/health", headers=TOKEN_HEADER)
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["service"] == "careerpath-analysis"
    # 6 类 / 61 技能是种子基线，词典被改动时这条会先红
    assert body["dictionary"]["categoryCount"] == 6
    assert body["dictionary"]["skillCount"] == 61
    assert body["time"].endswith("Z")


def test_parse_jd_requires_token() -> None:
    response = client.post("/internal/parse/jd", json={"text": JD})
    assert response.status_code == 401


def test_parse_jd_returns_suggestions() -> None:
    response = client.post("/internal/parse/jd", json={"text": JD, "jobTitle": "前端实习"}, headers=TOKEN_HEADER)
    assert response.status_code == 200
    body = response.json()
    assert body["matchedCount"] >= 2
    assert body["requiredCount"] + body["bonusCount"] == body["matchedCount"]
    assert body["algorithmVersion"] == "jd-rule-v1.0"

    first = body["requirements"][0]
    # camelCase：Java 侧直接映射 DTO，不做命名转换
    for key in ("skillId", "skillName", "dim", "reqType", "targetLevel", "sourceStart", "sourceEnd"):
        assert key in first
    assert first["reqType"] in {"required", "bonus"}
    assert JD[first["sourceStart"] : first["sourceEnd"]] == first["sourceText"]


def test_parse_jd_rejects_empty_text() -> None:
    response = client.post("/internal/parse/jd", json={"text": ""}, headers=TOKEN_HEADER)
    assert response.status_code == 422
    assert response.json()["detail"]["code"] == "VALIDATION_ERROR"


def test_trace_id_is_echoed() -> None:
    response = client.get("/internal/health", headers={**TOKEN_HEADER, "X-Trace-Id": "abc123"})
    assert response.headers["X-Trace-Id"] == "abc123"
