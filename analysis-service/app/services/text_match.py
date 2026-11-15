"""别名匹配器：把一段自由文本映射到技能词典。

这是全项目唯一一份别名匹配实现，JD 解析与简历解析共用（AGENTS.md 第六节硬规则）：

1. **英文别名长度倒序 + 词边界**——先让 `next.js` 吃掉，`js` 才不会重复命中；
   词边界两侧排除字母数字与 `+#.`，才能同时正确处理 `node.js`、`c++`、`c#`。
2. **中文别名按子串**——中文没有词边界，按子串匹配即可。
3. **命中后遮蔽区间**——同一段文本不会被更短的别名二次命中。

如果这里写错，`spring` 会被 `spring cloud` 抢走、`java` 会把 `javascript` 认成 Java，
下游所有分数都不可信，所以规则必须集中在这一处。
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from app.core.dictionary import Dictionary

MASK = "\x00"

# 单个技能最多记录多少次出现，避免一份 JD 重复 200 次「Vue」把响应撑爆
MAX_HITS_PER_SKILL = 5


@dataclass(frozen=True)
class AliasHit:
    """一次命中。`start`/`end` 是原文下标，落库后可以精确回指。"""

    skill_id: str
    alias: str
    start: int
    end: int


def _is_ascii(text: str) -> bool:
    return all(ord(ch) < 128 for ch in text)


def compile_alias(alias: str) -> re.Pattern[str]:
    """英文加词边界，中文按子串。"""
    if _is_ascii(alias):
        return re.compile(
            rf"(?<![A-Za-z0-9+#.]){re.escape(alias)}(?![A-Za-z0-9+#])",
            re.IGNORECASE,
        )
    return re.compile(re.escape(alias))


def build_alias_table(dictionary: Dictionary) -> list[tuple[str, str]]:
    """(别名, skillId) 列表，按别名长度倒序。

    技能名本身也参与匹配——JD 里更常见的写法就是「JavaScript 核心语法」这种全称。
    """
    table: list[tuple[str, str]] = []
    for skill in dictionary.skills:
        for alias in (*skill.aliases, skill.name):
            alias = alias.strip()
            if len(alias) >= 2:
                table.append((alias, skill.id))
    table.sort(key=lambda pair: (-len(pair[0]), pair[0]))
    return table


def find_hits(text: str, dictionary: Dictionary) -> list[AliasHit]:
    """返回按出现位置排序的命中列表；每个技能最多保留 MAX_HITS_PER_SKILL 条。"""
    if not text:
        return []

    masked = list(text)
    hits: list[AliasHit] = []
    seen: dict[str, int] = {}

    for alias, skill_id in build_alias_table(dictionary):
        if seen.get(skill_id, 0) >= MAX_HITS_PER_SKILL:
            continue
        pattern = compile_alias(alias)
        # 每次遮蔽后都用新串继续搜，命中的区间不会再被更短别名吃到
        haystack = "".join(masked)
        position = 0
        while seen.get(skill_id, 0) < MAX_HITS_PER_SKILL:
            match = pattern.search(haystack, position)
            if match is None:
                break
            start, end = match.span()
            hits.append(AliasHit(skill_id=skill_id, alias=alias, start=start, end=end))
            seen[skill_id] = seen.get(skill_id, 0) + 1
            for index in range(start, end):
                masked[index] = MASK
            haystack = "".join(masked)
            position = end

    hits.sort(key=lambda hit: (hit.start, hit.end))
    return hits
