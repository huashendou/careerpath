"""别名匹配的边界用例。

这些用例不是为了覆盖率，而是为了盯住三条会导致「分数全错」的规则：
英文词边界、中文子串、命中区间遮蔽。v1 就栽在 `js ⊂ next.js` 上。
"""

from __future__ import annotations

from app.core.dictionary import store
from app.services.text_match import find_hits


def _hit_map(text: str) -> dict[str, str]:
    """skillId → 命中的别名。"""
    data = store.get()
    return {hit.skill_id: hit.alias for hit in find_hits(text, data)}


def test_english_alias_respects_word_boundary() -> None:
    """`next.js` 里不能把 `js` 认成 JavaScript 核心语法。

    词典里没有 Next.js 这一项，所以这段文本的正确答案是「不命中 js-core」。
    """
    hits = _hit_map("使用 Next.js 做过服务端渲染")
    assert "js-core" not in hits


def test_java_does_not_swallow_javascript() -> None:
    """`javascript` 不能被 `java` 抢先命中。"""
    hits = _hit_map("熟悉 JavaScript 与 TypeScript")
    assert hits.get("js-core") in {"JavaScript", "javascript", "js"}
    assert "java" not in hits


def test_spring_cloud_not_split() -> None:
    """`Spring Cloud` 是一个技能，不能被 `spring` 再切出一个独立技能。"""
    data = store.get()
    hits = find_hits("熟悉 Spring Cloud 微服务", data)
    # 命中几条不重要（「微服务」也是该技能的别名），重要的是没有多出别的技能
    assert {hit.skill_id for hit in hits} == {"spring-cloud"}
    # 遮蔽生效：命中区间互不重叠
    spans = [(hit.start, hit.end) for hit in hits]
    for (_, prev_end), (next_start, _) in zip(spans, spans[1:]):
        assert prev_end <= next_start


def test_chinese_alias_matches_substring() -> None:
    """中文没有词边界，按子串匹配。"""
    hits = _hit_map("掌握数据结构与算法")
    assert hits


def test_hits_are_position_ordered_and_non_overlapping() -> None:
    data = store.get()
    hits = find_hits("熟悉 Vue，熟悉 React，熟悉 TypeScript", data)
    assert hits == sorted(hits, key=lambda hit: (hit.start, hit.end))
    spans = [(hit.start, hit.end) for hit in hits]
    for (_, prev_end), (next_start, _) in zip(spans, spans[1:]):
        assert prev_end <= next_start
