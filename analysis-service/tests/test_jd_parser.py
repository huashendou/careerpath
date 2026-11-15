"""JD 解析的行为断言。

重点不是「能解析出东西」，而是：
1. 必备/加分按**分句**判定，不按整段；
2. 每条结果都能回指原文（offset 与原文切片一致）；
3. 同一技能多次出现只出一条，且不丢「必备」属性。
"""

from __future__ import annotations

from app.core.dictionary import store
from app.services.jd_parser import extract_conditions, parse_jd

JD = """
岗位职责：
1. 负责公司前端业务开发，使用 Vue 3 与 TypeScript 完成页面搭建；
2. 与后端协作完成接口联调。

任职要求：
1. 本科及以上学历，计算机相关专业，1-3 年经验；
2. 熟练掌握 JavaScript 核心语法，理解原型链与异步编程；
3. 熟悉 Vue 或 React，有组件库封装经验；
4. 了解 Node.js 构建工具链；
5. 有开源项目贡献或技术博客者优先；
6. 熟悉 Redis 缓存设计与使用的优先考虑。
"""


def test_required_and_bonus_are_split_by_clause() -> None:
    data = store.get()
    requirements, _ = parse_jd(JD, data)
    by_id = {item.skill_id: item for item in requirements}

    # 「有开源项目贡献…者优先」所在分句没有技能，这里验证的是带「优先」的分句判为加分
    assert by_id["redis"].req_type == "bonus"
    # 「熟练掌握 JavaScript 核心语法」是必备
    assert by_id["js-core"].req_type == "required"


def test_source_span_matches_original_text() -> None:
    data = store.get()
    requirements, _ = parse_jd(JD, data)
    for item in requirements:
        assert JD[item.source_start : item.source_end] == item.source_text
        assert item.source_text.lower() == item.matched_alias.lower() or item.source_text == item.matched_alias


def test_same_skill_appears_once() -> None:
    data = store.get()
    text = "熟悉 Vue。熟悉 Vue。必须有 Vue 经验。"
    requirements, _ = parse_jd(text, data)
    ids = [item.skill_id for item in requirements]
    assert len(ids) == len(set(ids))


def test_required_wins_when_skill_seen_twice() -> None:
    """同一技能先出现在加分句、后出现在必备句时，结论必须是必备。"""
    data = store.get()
    text = "熟悉 Redis 者优先。岗位要求必须掌握 Redis。"
    requirements, _ = parse_jd(text, data)
    redis = next(item for item in requirements if item.skill_id == "redis")
    assert redis.req_type == "required"


def test_conditions_are_extracted() -> None:
    conditions = extract_conditions(JD)
    assert conditions.education is not None
    assert "本科" in conditions.education
    assert conditions.experience is not None


def test_empty_text_yields_no_requirements() -> None:
    data = store.get()
    requirements, conditions = parse_jd("   ", data)
    assert requirements == []
    assert conditions.education is None


def test_weight_is_equal_by_default() -> None:
    """真实 JD 没有权威权重，默认等权；用户可在修正时调整。"""
    data = store.get()
    requirements, _ = parse_jd(JD, data)
    assert {item.weight for item in requirements} == {1}
