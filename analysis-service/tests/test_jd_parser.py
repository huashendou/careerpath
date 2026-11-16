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


def test_bonus_section_marks_following_clauses() -> None:
    """加分项写成小标题时，其下每条都不带「优先」二字，也要判成 bonus。

    结构取自淘天集团 2026 届前端实习 JD：【岗位要求】…【加分项】（满足其一即为加分）…
    只看分句的话，「熟悉 Sass 或 Tailwind」这种条目里没有任何加分信号，会被兜底成必备，
    于是加分算进必备分——分错了，用户看到的差距顺序也是错的。
    """
    data = store.get()
    text = (
        "【岗位要求】\n"
        "1. 精通各类前端技术（HTML/CSS/JavaScript），熟悉 TypeScript；\n"
        "2. 掌握至少一种领域内常见框架（React、Vue）的原理和使用；\n"
        "【加分项】（满足其一即为加分）\n"
        "1. 熟悉 Sass 或 Tailwind 等 CSS 工具链；\n"
    )
    requirements, _ = parse_jd(text, data)
    by_id = {item.skill_id: item for item in requirements}

    assert by_id["html-css"].req_type == "required"
    assert by_id["css-toolchain"].req_type == "bonus"


def test_section_takes_nearest_heading() -> None:
    """加分段之后又出现必备段时，要回到 required。

    按「位置之前最近的标题」判，而不是「全文出现过加分项」——后者会把整篇都判成加分，
    在这类两段并存的 JD 上错得比不判更彻底。
    """
    data = store.get()
    text = (
        "【加分项】\n1. 熟悉 Sass；\n"
        "【任职要求】\n1. 熟悉 Vue 框架。\n"
    )
    requirements, _ = parse_jd(text, data)
    by_id = {item.skill_id: item for item in requirements}

    assert by_id["css-toolchain"].req_type == "bonus"
    assert by_id["vue"].req_type == "required"


def test_bare_component_library_alias_does_not_fire() -> None:
    """「开源组件库经验」不能抽成 CSS 工具链要求。

    淘天 JD 的加分项里写着「高知名度博客、开源项目、开源组件库等经验」，
    早期词典用裸词「组件库」当别名，把这句话抽成了「CSS 预处理器与组件库」必备要求——
    一条纯误报，会让岗位凭空多出一项用户没有的差距。
    """
    data = store.get()
    requirements, _ = parse_jd("加分项：有高知名度博客、开源项目、开源组件库等经验。", data)
    assert "css-toolchain" not in {item.skill_id for item in requirements}
