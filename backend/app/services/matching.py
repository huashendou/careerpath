"""匹配与差距分析（core-only 口径）。

这是全项目唯一一份算分实现，前端不得再写一份。

设计约束：
1. **纯函数**：不查库、不读全局状态、不写文件。输入岗位要求与自评，输出结果字典。
   pytest 才能在不起数据库的情况下验完全部规则。
2. **plus 完全退出分子分母**：加分项的定义是「没有也能干」，把它算进分母等于把
   「没有也能干」变成「没有就扣分」，自相矛盾。
3. **anyOf 只计一次**：Vue / React 二选一，取高者，权重只加一次；缺口也只出一条。
4. **空维返回 None，不返回 0**：某维度没有 core 要求时，0 会被读成「要求为零」，
   真实含义是「本岗位不把它当核心要求」。
5. **无法算分不抛异常**：返回 `matchScore=None` + `status`，这是业务结论不是错误。
   抛 4xx 会迫使前端把「正常结论」和「请求写错了」混在一个分支里处理。

口径依据：`docs/P0-数据模型设计.md` 第六节；字段对应：`docs/协作看板.md` 3.5。
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Mapping, Sequence
from decimal import ROUND_HALF_UP, Decimal
from typing import Any

LEVEL_MIN = 0
LEVEL_MAX = 5

CORE = "core"
PLUS = "plus"

STATUS_MISSING = "missing"
STATUS_WEAK = "weak"
STATUS_MASTERED = "mastered"

# 整体结论三态
OK = "ok"
NO_CORE_REQUIREMENT = "NO_CORE_REQUIREMENT"
EMPTY_PROFILE = "EMPTY_PROFILE"

# plus 两态
PLUS_MASTERED = "mastered"
PLUS_PENDING = "pending"


def clamp_level(value: Any) -> int:
    """把任意输入收敛为 0–5 的整数。

    非数字 → 0；小数四舍五入；越界夹取；布尔值不算数字（True 不能变成 1 级）。
    """
    if value is None or isinstance(value, bool):
        return LEVEL_MIN
    try:
        number = float(value)
    except (TypeError, ValueError):
        return LEVEL_MIN
    if number != number:  # NaN
        return LEVEL_MIN
    if not math.isfinite(number):  # ±Infinity：Decimal 量化会抛 InvalidOperation
        return LEVEL_MAX if number > 0 else LEVEL_MIN
    rounded = Decimal(str(number)).quantize(Decimal("1"), ROUND_HALF_UP)
    return max(LEVEL_MIN, min(LEVEL_MAX, int(rounded)))


def effective_level(req: Mapping[str, Any], ratings: Mapping[str, Any]) -> int:
    """该条要求的有效等级：自身与 anyOf 成员取最高。

    anyOf 成员不单独计权，只在这里提供等级。
    """
    level = clamp_level(ratings.get(req["skillId"]))
    for member in req.get("anyOf") or ():
        level = max(level, clamp_level(ratings.get(member)))
    return level


def normalize_requirements(
    requirements: Iterable[Mapping[str, Any]],
    skills: Mapping[str, Mapping[str, Any]],
) -> list[dict[str, Any]]:
    """清洗要求列表：去重（保留首条）、丢弃引用不存在的技能、丢弃 target<=0 的条目。

    这些在 seed 阶段就该校验失败；运行期再兜一层，保证不会因为一条脏数据让整页崩掉。
    """
    seen: set[str] = set()
    cleaned: list[dict[str, Any]] = []
    for raw in requirements:
        skill_id = raw["skillId"]
        if skill_id in seen or skill_id not in skills:
            continue
        target = int(raw.get("target") or 0)
        if target <= 0:
            continue
        seen.add(skill_id)
        cleaned.append(
            {
                "skillId": skill_id,
                "weight": int(raw.get("weight") or 0),
                "target": target,
                "type": raw.get("type", CORE),
                "anyOf": list(raw.get("anyOf") or []),
            }
        )
    return cleaned


def _round(value: float, quantum: str) -> float:
    """四舍五入到指定位数（不用 Python 的银行家舍入，避免 0.5 时结果反直觉）。"""
    return float(Decimal(str(value)).quantize(Decimal(quantum), rounding=ROUND_HALF_UP))


def _core_items(items: Sequence[Mapping[str, Any]]) -> list[Mapping[str, Any]]:
    return [r for r in items if r["type"] == CORE]


def _plus_items(items: Sequence[Mapping[str, Any]]) -> list[Mapping[str, Any]]:
    return [r for r in items if r["type"] == PLUS]


def _status(current: int, target: int) -> str:
    if current <= 0:
        return STATUS_MISSING
    if current < target:
        return STATUS_WEAK
    return STATUS_MASTERED


def _core_totals(
    core: Sequence[Mapping[str, Any]], ratings: Mapping[str, Any]
) -> tuple[int, int]:
    """返回 (分子, 分母)。只有 core 参与，plus 一个都不加。"""
    numerator = 0
    denominator = 0
    for req in core:
        numerator += req["weight"] * min(effective_level(req, ratings), req["target"])
        denominator += req["weight"] * req["target"]
    return numerator, denominator


def _gap_items(
    core: Sequence[Mapping[str, Any]],
    ratings: Mapping[str, Any],
    skills: Mapping[str, Mapping[str, Any]],
) -> list[dict[str, Any]]:
    """只收 gap>0 的项。已掌握的技能不进「能力差距」列表——它不是差距。

    `level` 是该项自身等级，`effectiveLevel` 是算上 anyOf 成员后的等级。
    两者都返回，是为了让「Vue 没评但 React 4 级」这种情况可以看出是替代项生效，
    而不是误以为 Vue 本身达标了。
    """
    items: list[dict[str, Any]] = []
    for req in core:
        own = clamp_level(ratings.get(req["skillId"]))
        effective = effective_level(req, ratings)
        gap = max(req["target"] - effective, 0)
        if gap <= 0:
            continue
        skill = skills[req["skillId"]]
        items.append(
            {
                "skillId": req["skillId"],
                "skillName": skill["name"],
                "dim": skill["dimId"],
                "weight": req["weight"],
                "target": req["target"],
                "level": own,
                "effectiveLevel": effective,
                "gap": gap,
                "priority": req["weight"] * gap,
                "status": STATUS_MISSING if effective <= 0 else STATUS_WEAK,
            }
        )
    items.sort(key=lambda x: (-x["priority"], -x["weight"], x["skillId"]))
    return items


def _plus_items_out(
    plus: Sequence[Mapping[str, Any]],
    ratings: Mapping[str, Any],
    skills: Mapping[str, Mapping[str, Any]],
) -> list[dict[str, Any]]:
    """加分项清单：只有 mastered / pending 两态，不参与任何百分比。"""
    mastered: list[dict[str, Any]] = []
    pending: list[dict[str, Any]] = []
    for req in plus:
        level = effective_level(req, ratings)
        skill = skills[req["skillId"]]
        base = {
            "skillId": req["skillId"],
            "skillName": skill["name"],
            "dim": skill["dimId"],
            "weight": req["weight"],
            "target": req["target"],
            "level": level,
        }
        if level >= req["target"]:
            mastered.append({**base, "status": PLUS_MASTERED})
        else:
            pending.append({**base, "status": PLUS_PENDING, "_rank": req["weight"] * (req["target"] - level)})

    mastered.sort(key=lambda x: (-x["weight"], x["skillId"]))
    pending.sort(key=lambda x: (-x["_rank"], -x["weight"], x["skillId"]))
    for item in pending:
        item.pop("_rank", None)
    return mastered + pending


def _radar(
    core: Sequence[Mapping[str, Any]],
    ratings: Mapping[str, Any],
    dimensions: Sequence[Mapping[str, str]],
    skills: Mapping[str, Mapping[str, Any]],
) -> dict[str, Any]:
    """六维雷达。目标线与当前线必须用同一组 core 权重，否则两条线不可比。"""
    axes: list[dict[str, Any]] = []
    data_axis_count = 0
    for dim in dimensions:
        in_dim = [r for r in core if skills[r["skillId"]]["dimId"] == dim["id"]]
        if not in_dim:
            axes.append(
                {
                    "dim": dim["id"],
                    "name": dim["name"],
                    "target": None,
                    "current": None,
                    "hasCore": False,
                    "coreWeight": 0,
                }
            )
            continue

        data_axis_count += 1
        weight_sum = sum(r["weight"] for r in in_dim)
        target = sum(r["weight"] * r["target"] for r in in_dim) / weight_sum
        current = sum(r["weight"] * effective_level(r, ratings) for r in in_dim) / weight_sum
        axes.append(
            {
                "dim": dim["id"],
                "name": dim["name"],
                "target": _round(target, "0.01"),
                "current": _round(current, "0.01"),
                "hasCore": True,
                "coreWeight": weight_sum,
            }
        )
    return {"axes": axes, "dataAxisCount": data_axis_count}


def analyze(
    *,
    job_id: str,
    job_name: str,
    requirements: Sequence[Mapping[str, Any]],
    ratings: Mapping[str, Any],
    skills: Mapping[str, Mapping[str, Any]],
    dimensions: Sequence[Mapping[str, str]],
    data_version: str = "示例数据 v0.1",
    disclaimer: str = "",
    scoring_version: str = "v0.2-core-only",
    profile_id: int | None = None,
) -> dict[str, Any]:
    """算分入口。返回结构与看板 3.5 的 MatchResult 逐字段一致。"""
    cleaned = normalize_requirements(requirements, skills)
    core = _core_items(cleaned)
    plus = _plus_items(cleaned)

    numerator, denominator = _core_totals(core, ratings)
    gaps = _gap_items(core, ratings, skills)
    plus_items = _plus_items_out(plus, ratings, skills)

    # 三种状态的判定顺序有讲究：岗位本身不可算（无 core）比「用户还没自评」更根本，
    # 换任何画像结论都一样，所以先报 NO_CORE_REQUIREMENT。
    if denominator == 0:
        status = NO_CORE_REQUIREMENT
        match_score = None
    elif not ratings:
        status = EMPTY_PROFILE
        match_score = None
    else:
        status = OK
        match_score = _round(numerator / denominator * 100, "0.0001")

    core_counts = {STATUS_MASTERED: 0, STATUS_WEAK: 0, STATUS_MISSING: 0}
    for req in core:
        core_counts[_status(effective_level(req, ratings), req["target"])] += 1

    return {
        "jobId": job_id,
        "jobName": job_name,
        "profileId": profile_id,
        "matchScore": match_score,
        "status": status,
        "numerator": numerator,
        "denominator": denominator,
        "counts": {
            "mastered": core_counts[STATUS_MASTERED],
            "weak": core_counts[STATUS_WEAK],
            "missing": core_counts[STATUS_MISSING],
            "plusMastered": sum(1 for i in plus_items if i["status"] == PLUS_MASTERED),
            "plusPending": sum(1 for i in plus_items if i["status"] == PLUS_PENDING),
        },
        "gaps": gaps,
        "topActions": [g["skillId"] for g in gaps[:3]],
        "plusItems": plus_items,
        "radar": _radar(core, ratings, dimensions, skills),
        "meta": {
            "scoringVersion": scoring_version,
            "displayBadge": data_version,
            "disclaimer": disclaimer,
        },
    }
