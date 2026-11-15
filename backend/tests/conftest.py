"""pytest 公共夹具。

关键一步：**在导入 app 之前**把数据库指向 SQLite 临时文件。
settings 是模块级单例，一旦 import 就固定了，所以环境变量必须在最前面设置。
"""

from __future__ import annotations

import contextlib
import io
import json
import os
import sys
from pathlib import Path

_BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(_BACKEND.as_posix()))

# —— 必须早于任何 app.* import ——
_TEST_DB = Path(__file__).resolve().parent / "_test_app.db"
if _TEST_DB.exists():
    _TEST_DB.unlink()
os.environ["DATABASE_URL"] = f"sqlite:///{_TEST_DB.as_posix()}"
os.environ["AUTO_CREATE_TABLES"] = "true"

import pytest  # noqa: E402

PROJECT_ROOT = _BACKEND.parent
DATA_DIR = PROJECT_ROOT / "data"


def _load(name: str) -> dict:
    with (DATA_DIR / name).open(encoding="utf-8") as fp:
        return json.load(fp)


@pytest.fixture(scope="session")
def skills_doc() -> dict:
    return _load("skills.json")


@pytest.fixture(scope="session")
def jobs_doc() -> dict:
    return _load("jobs.json")


@pytest.fixture(scope="session")
def skill_index(skills_doc: dict) -> dict[str, dict[str, str]]:
    """skillId -> {name, dimId, dimName}，算分服务要的形态。"""
    dim_names = {d["id"]: d["name"] for d in skills_doc["dimensions"]}
    return {
        s["id"]: {"name": s["name"], "dimId": s["dim"], "dimName": dim_names[s["dim"]]}
        for s in skills_doc["skills"]
    }


@pytest.fixture(scope="session")
def dim_specs(skills_doc: dict) -> list[dict[str, str]]:
    return [{"id": d["id"], "name": d["name"], "short": d["short"]} for d in skills_doc["dimensions"]]


@pytest.fixture(scope="session")
def jobs_by_id(jobs_doc: dict) -> dict[str, dict]:
    return {j["id"]: j for j in jobs_doc["jobs"]}


@pytest.fixture(scope="session")
def frontend(jobs_by_id: dict) -> dict:
    return jobs_by_id["frontend"]


@pytest.fixture(scope="session")
def frontend_ids(frontend: dict) -> dict[str, list[str]]:
    return {
        "core": [r["skillId"] for r in frontend["requirements"] if r["type"] == "core"],
        "plus": [r["skillId"] for r in frontend["requirements"] if r["type"] == "plus"],
    }


@pytest.fixture(scope="session", autouse=True)
def prepared_db():
    """建表 + 灌种子，整个测试会话只做一次。复用 seed.py，避免测试与生产两套导入逻辑。"""
    import seed
    from app.db.base import Base
    from app.db.session import engine
    from app.models import entities  # noqa: F401

    Base.metadata.create_all(bind=engine)
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer):
        code = seed.run()
    assert code == 0, f"种子导入失败：{buffer.getvalue()}"
    yield


@pytest.fixture(scope="session")
def client(prepared_db):
    from fastapi.testclient import TestClient

    from app.main import app

    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def auth_headers(client) -> dict[str, str]:
    """注册一个全新用户并返回带 token 的请求头。每次调用都是独立账号，互不干扰。"""
    import uuid

    username = f"u{uuid.uuid4().hex[:10]}"
    resp = client.post("/api/auth/register", json={"username": username, "password": "pw123456"})
    assert resp.status_code == 201, resp.text
    return {"Authorization": f"Bearer {resp.json()['accessToken']}"}
