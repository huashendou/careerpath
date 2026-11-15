"""JD 解析器：把一段岗位描述变成结构化的技能要求。

第一版口径（AGENTS.md 第六节）：**技能词典 + 同义词映射 + 正则规则**，不依赖大模型。
输出只是**预填建议**，Java 落库前必须让用户逐条确认/修改，本服务不写任何业务表。

分类依据「命中词所在的分句」，而不是整段文本：
「熟悉 Vue，有 React 经验者优先」里，Vue 属于必备、React 属于加分，按整段判会把后者也判成必备。
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass
from typing import Literal

from app.core.dictionary import Dictionary
from app.schemas.internal import JdConditions, RequirementSuggestion
from app.services.text_match import find_hits

logger = logging.getLogger(__name__)

# 算法版本：口径一变就升版本，报告里要能追溯是哪一版算出来的
ALGORITHM_VERSION = "jd-rule-v1.0"

# 分句符：中英文标点 + 换行
CLAUSE_MARKS = "。！？；\n，,;"

# 加分信号（命中即判 bonus）
BONUS_WORDS = (
    "优先",
    "加分",
    "更佳",
    "有则更佳",
    "锦上添花",
    "plus",
    "nice to have",
    "额外",
    "不限",
)
# 必备信号（出现即可判 required；没有信号时按 required 兜底）
REQUIRED_WORDS = (
    "必备",
    "必须",
    "要求",
    "至少",
    "扎实",
    "熟练掌握",
    "深入理解",
    "精通",
)

# 目标等级推定：动词强度 → 0–5 级中的目标档位
LEVEL_RULES: tuple[tuple[int, tuple[str, ...]], ...] = (
    (4, ("精通", "深入理解", "底层原理", "源码", "架构", "专家")),
    (3, ("熟练掌握", "熟练", "掌握", "独立", "主导", "扎实")),
    (2, ("了解", "接触", "用过", "使用过", "熟悉")),
)
DEFAULT_TARGET_REQUIRED = 3
DEFAULT_TARGET_BONUS = 2

EDUCATION_PATTERN = re.compile(r"(博士|硕士|研究生|本科|大专|专科)(研究生)?(及以上|以上|学历)?")
EXPERIENCE_PATTERN = re.compile(
    r"(\d+\s*[-~至]\s*\d+\s*年|\d+\s*年以上|应届(毕业生)?|在校(生)?|实习(生)?)"
)
EVIDENCE_RADIUS = 20


@dataclass(frozen=True)
class _Draft:
    skill_id: str
    alias: str
    start: int
    end: int
    clause: str


def _clause_at(text: str, position: int) -> str:
    """取包含 position 的那个分句。分句是分类与等级推定的最小语境。"""
    start = 0
    for mark in CLAUSE_MARKS:
        index = text.rfind(mark, 0, position)
        if index != -1:
            start = max(start, index + 1)
    end = len(text)
    for mark in CLAUSE_MARKS:
        index = text.find(mark, position)
        if index != -1:
            end = min(end, index)
    return text[start:end].strip()


def _classify(clause: str) -> Literal["required", "bonus"]:
    """先看加分信号再看必备信号。

    有些 JD 写「必备技能：Java；加分项：Redis」，两类词会同时出现在一个分句里，
    这时「优先/加分」更具体，优先采信。
    """
    lowered = clause.lower()
    if any(word in lowered for word in BONUS_WORDS):
        return "bonus"
    if any(word in lowered for word in REQUIRED_WORDS):
        return "required"
    return "required"


def _target_level(clause: str, req_type: str) -> int:
    for level, words in LEVEL_RULES:
        if any(word in clause for word in words):
            return level
    return DEFAULT_TARGET_REQUIRED if req_type == "required" else DEFAULT_TARGET_BONUS


def _confidence(alias: str, clause: str) -> float:
    """别名越长、语境里越有要求词，越可信。只用于排序与前端灰显，不参与算分。"""
    base = min(0.55 + len(alias) * 0.03, 0.85)
    if any(word in clause for word in REQUIRED_WORDS + BONUS_WORDS):
        base += 0.1
    return round(min(base, 0.98), 2)


def extract_conditions(text: str) -> JdConditions:
    education = EDUCATION_PATTERN.search(text)
    experience = EXPERIENCE_PATTERN.search(text)
    return JdConditions(
        education=education.group(0).strip() if education else None,
        experience=experience.group(0).strip() if experience else None,
    )


def parse_jd(text: str, dictionary: Dictionary) -> tuple[list[RequirementSuggestion], JdConditions]:
    """返回 (技能要求建议, 硬性条件)。同一技能多次出现只出一条。"""
    hits = find_hits(text, dictionary)

    # 同一技能可能命中多次：必备优先、位置取最早、等级取最高，最后只留一条。
    merged: dict[str, _Draft] = {}
    for hit in hits:
        clause = _clause_at(text, hit.start)
        current = merged.get(hit.skill_id)
        if current is None:
            merged[hit.skill_id] = _Draft(hit.skill_id, hit.alias, hit.start, hit.end, clause)
            continue
        keep_required = _classify(current.clause) == "required" or _classify(clause) == "required"
        if keep_required and _classify(current.clause) != "required":
            merged[hit.skill_id] = _Draft(hit.skill_id, hit.alias, hit.start, hit.end, clause)

    suggestions: list[RequirementSuggestion] = []
    for draft in merged.values():
        skill = dictionary.get(draft.skill_id)
        if skill is None:  # 理论上不会发生：hits 来自同一份词典
            continue
        req_type = _classify(draft.clause)
        left = max(0, draft.start - EVIDENCE_RADIUS)
        right = min(len(text), draft.end + EVIDENCE_RADIUS)
        suggestions.append(
            RequirementSuggestion(
                skill_id=skill.id,
                skill_name=skill.name,
                dim=skill.dim,
                req_type=req_type,
                # 权重默认为 1（等权）。真实 JD 没有权威权重，等权是最不坏的假设；
                # 用户可在修正时调整，不强制合计 100（见重构方案第五节）。
                weight=1,
                target_level=_target_level(draft.clause, req_type),
                matched_alias=draft.alias,
                source_start=draft.start,
                source_end=draft.end,
                source_text=text[draft.start : draft.end],
                evidence_hint=text[left:right].replace("\n", " ").strip(),
                confidence=_confidence(draft.alias, draft.clause),
            )
        )

    # 必备在前，再按置信度降序：用户从上往下确认，先看到最该看的
    suggestions.sort(key=lambda item: (item.req_type != "required", -item.confidence, item.skill_id))
    return suggestions, extract_conditions(text)
