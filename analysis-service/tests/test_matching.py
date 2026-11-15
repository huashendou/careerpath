"""算分口径回归测试（core-only）。

这些断言是 P0 阶段文档结论的可执行版本。任何人改动 `matching.py` 导致口径漂移，
这里必须红。特别地：
- plus 全 0 与全 5 的 M_core 必须完全相等（差值为 0，不是「约等于」）；
- 分母是 Σ_core(w×T) = 255 / 294 / 292，不是 100；
- 空维 target/current 是 None，不是 0。
"""

from __future__ import annotations

import logging
import math

import pytest

from app.services.matching import (
    EMPTY_PROFILE,
    NO_REQUIRED_REQUIREMENT,
    OK,
    analyze,
    clamp_level,
    effective_level,
)

FRONTEND_DEN = 255
JAVA_DEN = 294
ANALYST_DEN = 292


def run(job: dict, ratings: dict, skills: dict, dims: list, **kwargs) -> dict:
    return analyze(
        job_id=job["id"],
        job_name=job["name"],
        requirements=job["requirements"],
        ratings=ratings,
        skills=skills,
        dimensions=dims,
        **kwargs,
    )


def core_of(job: dict) -> list[dict]:
    return [r for r in job["requirements"] if r["type"] == "core"]


def plus_of(job: dict) -> list[dict]:
    return [r for r in job["requirements"] if r["type"] == "plus"]


def uniform(job: dict, value: int | None = None) -> dict:
    """给每条 core 要求填同一个等级；value=None 时按 floor(target/2) 取半档。"""
    return {
        r["skillId"]: (r["target"] // 2 if value is None else value) for r in core_of(job)
    }


def _warnings(caplog) -> list[str]:
    return [r.getMessage() for r in caplog.records if r.levelno == logging.WARNING]


# ---------------- 分母 ----------------


@pytest.mark.parametrize(
    ("job_id", "expected"),
    [("frontend", FRONTEND_DEN), ("java-backend", JAVA_DEN), ("data-analyst", ANALYST_DEN)],
)
def test_denominator_is_core_weight_times_target(
    jobs_by_id, skill_index, dim_specs, job_id, expected
):
    """分母不是 100。core 权重 79/89/83 与目标等级相乘后才是分母。"""
    result = run(jobs_by_id[job_id], uniform(jobs_by_id[job_id], 0), skill_index, dim_specs)
    assert result["denominator"] == expected


def test_core_weights_unchanged(jobs_by_id):
    """权重是原始数据，任何人不得擅自放大到 100。"""
    weights = [
        sum(r["weight"] for r in core_of(jobs_by_id[j]))
        for j in ("frontend", "java-backend", "data-analyst")
    ]
    assert weights == [79, 89, 83]


# ---------------- 基本端点 ----------------


def test_all_core_at_five_is_hundred(frontend, skill_index, dim_specs):
    result = run(frontend, uniform(frontend, 5), skill_index, dim_specs)
    assert result["requiredScore"] == 100.0
    assert result["numerator"] == result["denominator"] == FRONTEND_DEN


def test_all_core_at_zero_is_zero(frontend, skill_index, dim_specs):
    result = run(frontend, uniform(frontend, 0), skill_index, dim_specs)
    assert result["requiredScore"] == 0.0
    assert result["numerator"] == 0


def test_target_level_is_full_marks(frontend, skill_index, dim_specs):
    """刚好达到目标等级就是满分，多出来的等级不加分（min(L,T) 封顶）。"""
    exact = {r["skillId"]: r["target"] for r in core_of(frontend)}
    over = {r["skillId"]: 5 for r in core_of(frontend)}
    assert run(frontend, exact, skill_index, dim_specs)["requiredScore"] == 100.0
    assert run(frontend, over, skill_index, dim_specs)["requiredScore"] == 100.0


def test_half_level_reference_value(frontend, skill_index, dim_specs):
    """半档（floor(target/2)）= 41.1765%，与看板 3.5 示例数字一致。"""
    result = run(frontend, uniform(frontend), skill_index, dim_specs)
    assert result["requiredScore"] == pytest.approx(41.1765, abs=1e-6)
    assert result["numerator"] == 105


def test_one_level_reference_value(frontend, skill_index, dim_specs):
    result = run(frontend, uniform(frontend, 1), skill_index, dim_specs)
    assert result["requiredScore"] == pytest.approx(30.9804, abs=1e-6)
    assert result["numerator"] == 79


def test_score_is_four_decimals(frontend, skill_index, dim_specs):
    value = run(frontend, uniform(frontend), skill_index, dim_specs)["requiredScore"]
    assert round(value, 4) == value


# ---------------- plus 隔离（六组对照） ----------------


def _with_plus(job: dict, core_value: int | None, plus_value: int) -> dict:
    ratings = uniform(job, core_value)
    ratings.update({r["skillId"]: plus_value for r in plus_of(job)})
    return ratings


@pytest.mark.parametrize("core_value", [None, 0, 5])
def test_plus_never_changes_match_score(frontend, skill_index, dim_specs, core_value):
    """同 core、plus 全 0 与全 5 的 M_core 必须完全相等。"""
    low = run(frontend, _with_plus(frontend, core_value, 0), skill_index, dim_specs)
    high = run(frontend, _with_plus(frontend, core_value, 5), skill_index, dim_specs)
    assert low["requiredScore"] == high["requiredScore"]
    assert low["numerator"] == high["numerator"]
    assert low["denominator"] == high["denominator"]


def test_all_plus_without_core_stays_zero(frontend, skill_index, dim_specs):
    """core 全 0、plus 全 5 仍然是 0 分。加分项不能把「核心能力为零」救回来。"""
    ratings = {r["skillId"]: 5 for r in plus_of(frontend)}
    ratings.update({r["skillId"]: 0 for r in core_of(frontend)})
    assert run(frontend, ratings, skill_index, dim_specs)["requiredScore"] == 0.0


def test_plus_items_have_only_two_states(frontend, skill_index, dim_specs):
    result = run(frontend, _with_plus(frontend, None, 5), skill_index, dim_specs)
    assert {p["status"] for p in result["bonusItems"]} <= {"mastered", "pending"}
    assert result["counts"]["bonusMastered"] + result["counts"]["bonusPending"] == len(
        plus_of(frontend)
    )


def test_plus_items_sorted_mastered_first(frontend, skill_index, dim_specs):
    result = run(frontend, _with_plus(frontend, None, 5), skill_index, dim_specs)
    statuses = [p["status"] for p in result["bonusItems"]]
    if "mastered" in statuses and "pending" in statuses:
        last_mastered = max(i for i, s in enumerate(statuses) if s == "mastered")
        assert statuses.index("pending") > last_mastered


def test_plus_counts_reflect_mastery(frontend, skill_index, dim_specs):
    result = run(frontend, _with_plus(frontend, None, 5), skill_index, dim_specs)
    assert result["counts"]["bonusMastered"] == len(plus_of(frontend))
    assert result["counts"]["bonusPending"] == 0


# ---------------- anyOf ----------------


def test_anyof_member_counts_once(frontend, skill_index, dim_specs):
    """React 只通过 Vue 那条要求生效，不单独出现在 gaps 里，也不算两条要求。"""
    result = run(frontend, {"react": 4}, skill_index, dim_specs)
    ids = [g["skillId"] for g in result["gaps"]]
    assert "react" not in ids
    assert "vue" not in ids  # react 4 级 = vue target 4，已达标
    assert result["counts"]["requiredMastered"] == 1


def test_anyof_uses_higher_of_two(frontend, skill_index, dim_specs):
    """自身与成员取高者。"""
    req = {"skillId": "vue", "anyOf": ["react"], "target": 4}
    assert effective_level(req, {"vue": 1, "react": 4}) == 4
    assert effective_level(req, {"vue": 3, "react": 1}) == 3
    assert effective_level(req, {"vue": 0, "react": 0}) == 0


def test_anyof_gap_uses_effective_level(frontend, skill_index, dim_specs):
    """缺口按有效等级算：react 2 级时 vue 那条只差 2 级，不是 4 级。"""
    result = run(frontend, {"react": 2}, skill_index, dim_specs)
    vue = next(g for g in result["gaps"] if g["skillId"] == "vue")
    assert (vue["level"], vue["effectiveLevel"], vue["gap"]) == (0, 2, 2)
    assert vue["priority"] == vue["weight"] * 2


def test_anyof_weight_counted_once(frontend, skill_index, dim_specs):
    """带上 React 之后，分母不变——anyOf 不产生第二条要求。"""
    base = run(frontend, {}, skill_index, dim_specs)
    with_react = run(frontend, {"react": 5}, skill_index, dim_specs)
    assert base["denominator"] == with_react["denominator"] == FRONTEND_DEN


# ---------------- 缺口与优先级 ----------------


def test_gaps_only_include_unmet_core(frontend, skill_index, dim_specs):
    result = run(frontend, uniform(frontend, 5), skill_index, dim_specs)
    assert result["gaps"] == []
    assert result["topActions"] == []


def test_gaps_sorted_by_priority_desc(frontend, skill_index, dim_specs):
    result = run(frontend, uniform(frontend, 1), skill_index, dim_specs)
    priorities = [g["priority"] for g in result["gaps"]]
    assert priorities == sorted(priorities, reverse=True)


def test_gaps_never_contain_mastered(frontend, skill_index, dim_specs):
    result = run(frontend, uniform(frontend, 5), skill_index, dim_specs)
    assert all(g["status"] in ("missing", "weak") for g in result["gaps"])
    result = run(frontend, uniform(frontend, 0), skill_index, dim_specs)
    assert {g["status"] for g in result["gaps"]} == {"missing"}


def test_top_actions_at_most_three(frontend, skill_index, dim_specs):
    result = run(frontend, uniform(frontend, 0), skill_index, dim_specs)
    assert len(result["topActions"]) == 3
    assert result["topActions"] == [g["skillId"] for g in result["gaps"][:3]]


def test_counts_add_up(frontend, skill_index, dim_specs):
    result = run(frontend, uniform(frontend, 1), skill_index, dim_specs)
    counts = result["counts"]
    assert counts["requiredMastered"] + counts["requiredWeak"] + counts["requiredMissing"] == len(core_of(frontend))


# ---------------- 雷达 ----------------


def test_radar_has_six_axes(jobs_by_id, skill_index, dim_specs):
    for job_id in ("frontend", "java-backend", "data-analyst"):
        result = run(jobs_by_id[job_id], {}, skill_index, dim_specs)
        assert len(result["radar"]["axes"]) == 6


@pytest.mark.parametrize(
    ("job_id", "expected"), [("frontend", 4), ("java-backend", 5), ("data-analyst", 6)]
)
def test_radar_data_axis_count(jobs_by_id, skill_index, dim_specs, job_id, expected):
    result = run(jobs_by_id[job_id], {}, skill_index, dim_specs)
    assert result["radar"]["dataAxisCount"] == expected


def test_empty_axis_is_null_not_zero(jobs_by_id, skill_index, dim_specs):
    """空维必须返回 None。返回 0 会被读成「要求为零」，真实含义是「本岗位不看重它」。"""
    axes = run(jobs_by_id["frontend"], {}, skill_index, dim_specs)["radar"]["axes"]
    empty = [a for a in axes if not a["hasCore"]]
    assert empty, "前端岗应当有 2 个无 core 的维度"
    for axis in empty:
        assert axis["target"] is None
        assert axis["current"] is None
        assert axis["coreWeight"] == 0


def test_radar_target_and_current_use_same_weights(frontend, skill_index, dim_specs):
    """目标线与当前线同口径，达标时两者必须重合。"""
    ratings = {r["skillId"]: r["target"] for r in core_of(frontend)}
    axes = run(frontend, ratings, skill_index, dim_specs)["radar"]["axes"]
    for axis in axes:
        if axis["hasCore"]:
            assert axis["current"] == axis["target"]


def test_radar_core_weight_sums_to_job_core(frontend, skill_index, dim_specs):
    axes = run(frontend, {}, skill_index, dim_specs)["radar"]["axes"]
    assert sum(a["coreWeight"] for a in axes) == 79


# ---------------- 状态三态 ----------------


def test_empty_profile_status(frontend, skill_index, dim_specs):
    """一条自评都没有 → EMPTY_PROFILE，requiredScore 为 null。前端据此显示「尚未完成自评」。"""
    result = run(frontend, {}, skill_index, dim_specs)
    assert result["status"] == EMPTY_PROFILE
    assert result["requiredScore"] is None


def test_zero_ratings_is_not_empty_profile(frontend, skill_index, dim_specs):
    """评了但都是 0 级，是真实结论 0%，不是「还没自评」。两者提示完全不同。"""
    result = run(frontend, uniform(frontend, 0), skill_index, dim_specs)
    assert result["status"] == OK
    assert result["requiredScore"] == 0.0


def test_no_core_requirement_status(skill_index, dim_specs):
    """只有 plus 的岗位：分母为 0，返回 NO_REQUIRED_REQUIREMENT 而不是除零崩溃。"""
    job = {
        "id": "x",
        "name": "测试岗位",
        "requirements": [
            {"skillId": "js-core", "weight": 50, "target": 3, "type": "plus"},
            {"skillId": "html-css", "weight": 50, "target": 3, "type": "plus"},
        ],
    }
    result = run(job, {"js-core": 3}, skill_index, dim_specs)
    assert result["status"] == NO_REQUIRED_REQUIREMENT
    assert result["requiredScore"] is None
    assert result["denominator"] == 0


def test_three_jobs_score_differently(skill_index, dim_specs, jobs_by_id):
    """同一份中档画像，三岗分数必须不同——否则说明维度/权重没被真正用上。"""
    ratings = {skill_id: 2 for skill_id in skill_index}
    scores = {
        job_id: run(job, ratings, skill_index, dim_specs)["requiredScore"]
        for job_id, job in jobs_by_id.items()
    }
    assert len(set(scores.values())) == 3, scores


# ---------------- 边界与脏数据 ----------------


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        (None, 0),
        (0, 0),
        (5, 5),
        (-3, 0),
        (9, 5),
        (3.4, 3),
        (3.5, 4),
        (4.5, 5),
        (True, 0),
        (False, 0),
        ("3", 3),
        ("abc", 0),
        (math.nan, 0),
        (math.inf, 5),
        (-math.inf, 0),
    ],
)
def test_clamp_level(raw, expected):
    assert clamp_level(raw) == expected


def test_duplicate_requirement_kept_once(skill_index, dim_specs, caplog):
    job = {
        "id": "x",
        "name": "重复要求",
        "requirements": [
            {"skillId": "js-core", "weight": 30, "target": 4, "type": "core"},
            {"skillId": "js-core", "weight": 70, "target": 4, "type": "core"},
        ],
    }
    with caplog.at_level(logging.WARNING, logger="app.services.matching"):
        result = run(job, {}, skill_index, dim_specs)
    assert result["denominator"] == 30 * 4  # 保留首条，不叠加
    assert any("js-core" in m and "job=x" in m for m in _warnings(caplog)), _warnings(caplog)


def test_unknown_skill_requirement_dropped(skill_index, dim_specs, caplog):
    """未知 skillId 必须跳过**并留日志**。静默跳过会让分母悄悄变小，没人看得出来。"""
    job = {
        "id": "x",
        "name": "脏数据",
        "requirements": [
            {"skillId": "js-core", "weight": 30, "target": 4, "type": "core"},
            {"skillId": "not-exist", "weight": 70, "target": 4, "type": "core"},
        ],
    }
    with caplog.at_level(logging.WARNING, logger="app.services.matching"):
        result = run(job, {}, skill_index, dim_specs)
    assert result["denominator"] == 30 * 4
    assert any("not-exist" in m and "job=x" in m for m in _warnings(caplog)), _warnings(caplog)


def test_zero_target_requirement_dropped(skill_index, dim_specs, caplog):
    job = {
        "id": "x",
        "name": "target 为 0",
        "requirements": [
            {"skillId": "js-core", "weight": 30, "target": 4, "type": "core"},
            {"skillId": "html-css", "weight": 70, "target": 0, "type": "core"},
        ],
    }
    with caplog.at_level(logging.WARNING, logger="app.services.matching"):
        result = run(job, {}, skill_index, dim_specs)
    assert result["denominator"] == 30 * 4
    assert any("html-css" in m and "target" in m for m in _warnings(caplog)), _warnings(caplog)


def test_meta_block_is_filled(frontend, skill_index, dim_specs):
    result = run(
        frontend,
        {},
        skill_index,
        dim_specs,
        data_version="示例数据 v0.1",
        disclaimer="示例权重",
        scoring_version="v0.2-core-only",
    )
    assert result["meta"] == {
        "scoringVersion": "v0.2-core-only",
        "displayBadge": "示例数据 v0.1",
        "disclaimer": "示例权重",
    }
