"""pytest 公共夹具。

v2 起分析服务**不连数据库**：技能词典直接读 `data/skills.json`，
因此这里不需要临时库、不需要 seed，测试能在任何机器上裸跑。
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

_ANALYSIS = Path(__file__).resolve().parents[1]
sys.path.insert(0, _ANALYSIS.as_posix())

# 必须在任何 app.* import 之前设好：settings 是模块级单例，import 之后改无效
os.environ.setdefault("INTERNAL_TOKEN", "test-internal-token")

import pytest  # noqa: E402

PROJECT_ROOT = _ANALYSIS.parent
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
