"""简历文本解析（看板 3.7，P5）。

**返回的只是预填建议**，前端必须允许用户逐条修改后再保存画像。这里绝不写库。

匹配规则（看板硬规则）：
1. 英文别名按**长度倒序**匹配，并加词边界，避免 `js` 命中 `next.js`、`java` 命中 `javascript`；
2. 中文别名按**子串**匹配；
3. 命中后把该区间**遮蔽**，防止同一段文本被更短的别名二次命中。

等级推定是启发式的，只用来给个初值——用户看到的是「建议 3 级」，点一下就能改。
"""

from __future__ import annotations

import re

from fastapi import APIRouter
from sqlalchemy import select

from app.api.deps import CurrentUser, DbSession
from app.models.entities import Skill
from app.schemas.dto import ResumeParseIn, ResumeParseOut, SuggestionOut

router = APIRouter(prefix="/resume", tags=["简历解析"])

MASK = "\x00"
EVIDENCE_RADIUS = 18

# 从强到弱。命中哪个词，就把该技能的初始等级定为几级。
LEVEL_HINTS: tuple[tuple[int, tuple[str, ...]], ...] = (
    (4, ("精通", "深入理解", "底层原理", "源码", "架构设计")),
    (3, ("熟练", "熟悉", "掌握", "独立开发", "独立完成", "主导")),
    (2, ("了解", "接触过", "使用过", "学习过", "有项目经验", "做过")),
)


def _is_ascii(text: str) -> bool:
    return all(ord(ch) < 128 for ch in text)


def _infer_level(window: str) -> int:
    for level, words in LEVEL_HINTS:
        if any(word in window for word in words):
            return level
    return 1


def _clause_at(text: str, pos: int) -> str:
    """取包含 pos 的那个分句。

    按整段找提示词会把「熟悉 JavaScript，了解 TypeScript」里的 TypeScript 也判成 3 级——
    「熟悉」是前半句在修饰 JS。按分句切，各管各的。
    """
    start = 0
    for marks in "。！？；\n，,":
        index = text.rfind(marks, 0, pos)
        if index != -1:
            start = max(start, index + 1)
    end = len(text)
    for marks in "。！？；\n，,":
        index = text.find(marks, pos)
        if index != -1:
            end = min(end, index)
    return text[start:end]


def _evidence(text: str, start: int, end: int) -> str:
    left = max(0, start - EVIDENCE_RADIUS)
    right = min(len(text), end + EVIDENCE_RADIUS)
    snippet = text[left:right].replace("\n", " ").strip()
    return f"{'…' if left > 0 else ''}{snippet}{'…' if right < len(text) else ''}"


def _compile(alias: str) -> re.Pattern[str]:
    if _is_ascii(alias):
        # 词边界：两侧不能紧邻字母、数字或 +#.（覆盖 node.js / c++ / c#）
        return re.compile(
            rf"(?<![A-Za-z0-9+#.]){re.escape(alias)}(?![A-Za-z0-9+#])", re.IGNORECASE
        )
    return re.compile(re.escape(alias))


def parse_text(text: str, skills: list[Skill]) -> list[SuggestionOut]:
    masked = list(text)
    hits: list[tuple[int, str, str]] = []  # (位置, skillId, 命中的别名)

    candidates: list[tuple[str, str]] = []
    for skill in skills:
        for alias in [*(skill.aliases or []), skill.name]:
            if alias and len(alias) >= 2:
                candidates.append((alias, skill.id))
    # 长度倒序：先让 next.js 吃掉，js 才不会重复命中
    candidates.sort(key=lambda pair: len(pair[0]), reverse=True)

    seen_skill: set[str] = set()
    for alias, skill_id in candidates:
        if skill_id in seen_skill:
            continue
        pattern = _compile(alias)
        haystack = "".join(masked)
        match = pattern.search(haystack)
        if match is None:
            continue
        start, end = match.span()
        hits.append((start, skill_id, alias))
        seen_skill.add(skill_id)
        for index in range(start, end):
            masked[index] = MASK

    by_id = {s.id: s for s in skills}
    hits.sort(key=lambda item: item[0])
    suggestions: list[SuggestionOut] = []
    for start, skill_id, alias in hits:
        skill = by_id[skill_id]
        clause = _clause_at(text, start)
        suggestions.append(
            SuggestionOut(
                skillId=skill_id,
                name=skill.name,
                dim=skill.dim_id,
                level=_infer_level(clause or text[max(0, start - 60) : start + 60]),
                matchedAlias=alias,
                evidence=_evidence(text, start, start + len(alias)),
            )
        )
    return suggestions


@router.post("/parse", response_model=ResumeParseOut, summary="简历文本 → 技能建议")
def parse_resume(payload: ResumeParseIn, user: CurrentUser, db: DbSession) -> ResumeParseOut:
    skills = db.execute(select(Skill)).scalars().all()
    return ResumeParseOut(suggestions=parse_text(payload.text, list(skills)))
