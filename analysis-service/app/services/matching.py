"""匹配与差距分析（required-only 口径 + 证据独立展示）。

这是全项目**唯一一份**算分实现：Java 主服务不得自己再算一遍，前端也不得复算。

设计约束：
1. **纯函数**：不查库、不读全局状态、不写文件。输入岗位要求与自评，输出结果字典。
   pytest 才能在不起数据库的情况下验完全部规则。
2. **加分项完全退出分子分母**：bonus 的定义是「没有也能干」，把它算进分母等于把
   「没有也能干」变成「没有就扣分」，自相矛盾。
3. **证据不进分数**（用户 2026-11-15 裁决）：`requiredScore` 只由自评等级决定；
   证据只出 `evidenceCoverage` 与 `hasEvidence` 标记。理由同上——没上传材料就扣分，
   与「加分项进分母」是同一种错误。
4. **anyOf 只计一次**：Vue / React 二选一，取高者，权重只加一次；缺口也只出一条。
5. **空维返回 None，不返回 0**：某维度没有 required 要求时，0 会被读成「要求为零」，
   真实含义是「本岗位不把它当核心要求」。
6. **无法算分不抛异常**：返回 `requiredScore=None` + `status`，这是业务结论不是错误。

公式：`requiredScore = Σ_req(W×min(L,T)) / Σ_req(W×T) × 100`
与《整体介绍》`Σ(W×Mi)/ΣW`（`Mi` 为归一化掌握度）数学等价，此处选分母写法是为了
直接把 `numerator`/`denominator` 暴露给界面，让「分母不是 100」这条能一眼核对。

口径依据：`docs/P0-重构方案.md` 第五节。
"""

from __future__ import annotations

import logging
import math
from collections.abc import Iterable, Mapping, Sequence
from decimal import ROUND_HALF_UP, Decimal
from typing import Any

logger = logging.getLogger(__name__)

LEVEL_MIN = 0
LEVEL_MAX = 5

# 兼容两种叫法：v1 数据用 core/plus，v2 库表用 required/bonus
REQUIRED = "required"
BONUS = "bonus"
_CORE_ALIASES = {"required", "core"}
_BONUS_ALIASES = {"bonus", "plus"}

STATUS_MISSING = "missing"
STATUS_WEAK = "weak"
STATUS_MASTERED = "mastered"

OK = "ok"
NO_REQUIRED_REQUIREMENT = "NO_REQUIRED_REQUIREMENT"
EMPTY_PROFILE = "EMPTY_PROFILE"

BONUS_MASTERED = "mastered"
BONUS_PENDING = "pending"

ALGORITHM_VERSION = "v2.0-required-only"


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


def _is_bonus(req_type: Any) -> bool:
    return str(req_type or REQUIRED).lower() in _BONUS_ALIASES


def normalize_requirements(
    requirements: Iterable[Mapping[str, Any]],
    skills: Mapping[str, Mapping[str, Any]],
    *,
    job_id: str = "-",
) -> list[dict[str, Any]]:
    """清洗要求列表：去重（保留首条）、丢弃引用不存在的技能、丢弃 target<=0 的条目。

    统一键名为内部形态（`target` / `type`），并接受 `targetLevel` / `reqType` 两种入参写法，
    这样 v1 的回归用例与 v2 的库表字段能共用同一份算法。

    丢弃**必须留日志**：静默跳过会让「岗位少了一条要求」在结果里看不出来，
    分母悄悄变小也没人知道。
    """
    seen: set[str] = set()
    cleaned: list[dict[str, Any]] = []
    for raw in requirements:
        skill_id = raw.get("skillId") or raw.get("skill_id")
        if not skill_id:
            logger.warning("算分跳过缺少 skillId 的要求：job=%s", job_id)
            continue
        if skill_id in seen:
            logger.warning("算分跳过重复要求：job=%s skillId=%s", job_id, skill_id)
            continue
        if skill_id not in skills:
            logger.warning("算分跳过未知技能：job=%s skillId=%s", job_id, skill_id)
            continue
        target_raw = raw.get("target", raw.get("targetLevel"))
        target = int(target_raw or 0)
        if target <= 0:
            logger.warning(
                "算分跳过目标等级非正的要求：job=%s skillId=%s target=%s",
                job_id,
                skill_id,
                target_raw,
            )
            continue
        seen.add(skill_id)
        cleaned.append(
            {
                "skillId": skill_id,
                "weight": int(raw.get("weight") or 0),
                "target": target,
                "type": BONUS if _is_bonus(raw.get("type", raw.get("reqType"))) else REQUIRED,
                "anyOf": list(raw.get("anyOf") or raw.get("any_of") or []),
            }
        )
    return cleaned


def _round(value: float, quantum: str) -> float:
    """四舍五入到指定位数（不用 Python 的银行家舍入，避免 0.5 时结果反直觉）。"""
    return float(Decimal(str(value)).quantize(Decimal(quantum), rounding=ROUND_HALF_UP))


def _core_items(items: Sequence[Mapping[str, Any]]) -> list[Mapping[str, Any]]:
    return [r for r in items if r["type"] == REQUIRED]


def _bonus_items(items: Sequence[Mapping[str, Any]]) -> list[Mapping[str, Any]]:
    return [r for r in items if r["type"] == BONUS]


def _status(current: int, target: int) -> str:
    if current <= 0:
        return STATUS_MISSING
    if current < target:
        return STATUS_WEAK
    return STATUS_MASTERED


def _totals(core: Sequence[Mapping[str, Any]], ratings: Mapping[str, Any]) -> tuple[int, int]:
    """返回 (分子, 分母)。只有 required 参与，bonus 一个都不加。"""
    numerator = 0
    denominator = 0
    for req in core:
        numerator += req["weight"] * min(effective_level(req, ratings), req["target"])
        denominator += req["weight"] * req["target"]
    return numerator, denominator


def _evidence_strength(evidence: Mapping[str, Any], skill_id: str) -> int:
    """某技能的证据强度 0–100。没有证据记为 0，**但这不参与算分**。"""
    raw = evidence.get(skill_id)
    if raw is None:
        return 0
    try:
        return max(0, min(100, int(raw)))
    except (TypeError, ValueError):
        return 0


def _evidence_coverage(
    core: Sequence[Mapping[str, Any]], evidence: Mapping[str, Any]
) -> dict[str, Any]:
    """证据覆盖面：只统计 required 技能，缺席证据不影响任何分数。"""
    total = len(core)
    covered = sum(1 for r in core if _evidence_strength(evidence, r["skillId"]) > 0)
    percent = _round(covered / total * 100, "0.01") if total else None
    return {"coveredCount": covered, "totalCount": total, "coverage": percent}


def _gap_items(
    core: Sequence[Mapping[str, Any]],
    ratings: Mapping[str, Any],
    skills: Mapping[str, Mapping[str, Any]],
    evidence: Mapping[str, Any],
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
        strength = _evidence_strength(evidence, req["skillId"])
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
                "hasEvidence": strength > 0,
                "evidenceStrength": strength,
            }
        )
    items.sort(key=lambda x: (-x["priority"], -x["weight"], x["skillId"]))
    return items


def _bonus_items_out(
    bonus: Sequence[Mapping[str, Any]],
    ratings: Mapping[str, Any],
    skills: Mapping[str, Mapping[str, Any]],
) -> list[dict[str, Any]]:
    """加分项清单：只有 mastered / pending 两态，不参与任何百分比。"""
    mastered: list[dict[str, Any]] = []
    pending: list[dict[str, Any]] = []
    for req in bonus:
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
            mastered.append({**base, "status": BONUS_MASTERED})
        else:
            rank = req["weight"] * (req["target"] - level)
            pending.append({**base, "status": BONUS_PENDING, "_rank": rank})

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
    """六维雷达。目标线与当前线必须用同一组 required 权重，否则两条线不可比。"""
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

        weight_sum = sum(r["weight"] for r in in_dim)
        if weight_sum <= 0:  # 权重全为 0 的退化维度：按空轴处理，不除零
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
    job_id: Any,
    job_name: str,
    requirements: Sequence[Mapping[str, Any]],
    ratings: Mapping[str, Any],
    skills: Mapping[str, Mapping[str, Any]],
    dimensions: Sequence[Mapping[str, str]],
    evidence: Mapping[str, Any] | None = None,
    data_version: str = "示例数据 v0.1",
    disclaimer: str = "",
    scoring_version: str = ALGORITHM_VERSION,
    user_id: Any = None,
) -> dict[str, Any]:
    """算分入口。返回结构与看板 3.3 的 MatchResult 逐字段一致。"""
    evidence = evidence or {}
    cleaned = normalize_requirements(requirements, skills, job_id=str(job_id))
    core = _core_items(cleaned)
    bonus = _bonus_items(cleaned)

    numerator, denominator = _totals(core, ratings)
    gaps = _gap_items(core, ratings, skills, evidence)
    bonus_items = _bonus_items_out(bonus, ratings, skills)
    coverage = _evidence_coverage(core, evidence)

    # 三种状态的判定顺序有讲究：岗位本身不可算（无 required）比「用户还没自评」更根本，
    # 换任何画像结论都一样，所以先报 NO_REQUIRED_REQUIREMENT。
    if denominator == 0:
        status = NO_REQUIRED_REQUIREMENT
        required_score = None
    elif not ratings:
        status = EMPTY_PROFILE
        required_score = None
    else:
        status = OK
        required_score = _round(numerator / denominator * 100, "0.0001")

    bonus_numerator, bonus_denominator = _totals(bonus, ratings)
    # 与 requiredScore 同理：用户还没自评时，「加分项拿了 0%」不成立，应给 null。
    # 只有「自评过、但确实没够上」才是真实结论 0.0（见 test_zero_ratings_is_not_empty_profile）。
    # 分母为 0 是岗位本身没有加分项，属「不适用」，同样是 null。
    # 注意 evidenceCoverage 不走这条规则：没有证据本身就是 0%，是可计算的真实结论。
    bonus_score = (
        _round(bonus_numerator / bonus_denominator * 100, "0.0001")
        if bonus_denominator and ratings
        else None
    )

    counts = {STATUS_MASTERED: 0, STATUS_WEAK: 0, STATUS_MISSING: 0}
    for req in core:
        counts[_status(effective_level(req, ratings), req["target"])] += 1

    return {
        "jobId": job_id,
        "jobName": job_name,
        "userId": user_id,
        "requiredScore": required_score,
        "bonusScore": bonus_score,
        "status": status,
        "numerator": numerator,
        "denominator": denominator,
        "bonusNumerator": bonus_numerator,
        "bonusDenominator": bonus_denominator,
        "evidenceCoverage": coverage["coverage"],
        "counts": {
            "requiredMastered": counts[STATUS_MASTERED],
            "requiredWeak": counts[STATUS_WEAK],
            "requiredMissing": counts[STATUS_MISSING],
            "bonusMastered": sum(1 for i in bonus_items if i["status"] == BONUS_MASTERED),
            "bonusPending": sum(1 for i in bonus_items if i["status"] == BONUS_PENDING),
            "evidenceCovered": coverage["coveredCount"],
            "requiredTotal": coverage["totalCount"],
        },
        "gaps": gaps,
        "topActions": [g["skillId"] for g in gaps[:3]],
        "bonusItems": bonus_items,
        "radar": _radar(core, ratings, dimensions, skills),
        "meta": {
            "scoringVersion": scoring_version,
            "displayBadge": data_version,
            "disclaimer": disclaimer,
        },
    }
