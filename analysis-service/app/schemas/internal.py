"""内部接口的入参 / 出参。

命名一律 camelCase（与对外契约一致），Java 侧直接映射 DTO，不用再做命名转换。
`model_config` 同时允许按字段名赋值，方便 Python 内部用 snake_case 构造。
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


def to_camel(name: str) -> str:
    head, *rest = name.split("_")
    return head + "".join(word.capitalize() for word in rest)


class CamelModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        serialize_by_alias=True,
    )


# ---------------------------------------------------------------- 健康检查


class DictionaryStats(CamelModel):
    loaded: bool
    version: str = ""
    category_count: int = 0
    skill_count: int = 0


class HealthOut(CamelModel):
    status: Literal["ok", "degraded"]
    service: str
    version: str
    time: str
    dictionary: DictionaryStats


# ---------------------------------------------------------------- JD 解析


class ParseJdIn(CamelModel):
    """Java 把用户粘贴的原始 JD 原样传来，不做任何清洗。

    清洗放在解析器里做，是为了让「原文 → 结果」这一步可复现：同一份原文永远得到同一个结果。
    """

    text: str = Field(min_length=1, description="JD 原文，逐字保留")
    job_title: str | None = Field(default=None, description="岗位名，仅用于日志与结果标注")
    source: str | None = Field(default=None, description="来源，如 boss直聘 / 手动粘贴")


class RequirementSuggestion(CamelModel):
    """一条**建议**。落库前必须经用户确认或修改，接口不写任何表。"""

    skill_id: str
    skill_name: str
    dim: str
    req_type: Literal["required", "bonus"]
    weight: int
    target_level: int
    matched_alias: str
    # 原文可追溯：offset 用于高亮，text 用于直接展示
    source_start: int
    source_end: int
    source_text: str
    evidence_hint: str
    confidence: float


class JdConditions(CamelModel):
    """JD 里的硬性条件。识别不到就是 None，不猜。"""

    education: str | None = None
    experience: str | None = None


class ParseJdOut(CamelModel):
    requirements: list[RequirementSuggestion]
    conditions: JdConditions
    matched_count: int
    required_count: int
    bonus_count: int
    algorithm_version: str
    dictionary_version: str
    disclaimer: str


# ---------------------------------------------------------------- 匹配算分


class RequirementIn(CamelModel):
    """一条岗位技能要求。`reqType` 兼容 `required` / `bonus` 两种取值。"""

    skill_id: str
    req_type: Literal["required", "bonus"] = "required"
    weight: int = Field(default=1, ge=0)
    target_level: int = Field(default=1, ge=1, le=5)
    any_of: list[str] = Field(default_factory=list, description="二选一成员，只取最高等级且只计一次")


class UserSkillIn(CamelModel):
    skill_id: str
    level: int = Field(ge=0, le=5)


class EvidenceIn(CamelModel):
    """证据强度。**不进分数**，只用于 `evidenceCoverage` 与「缺证据」标记。"""

    skill_id: str
    strength: int = Field(default=0, ge=0, le=100)


class MatchIn(CamelModel):
    """Java 侧已把库里的要求与自评读好再传进来；本服务不查库。"""

    job_id: str
    job_name: str = ""
    user_id: int | None = None
    requirements: list[RequirementIn]
    user_skills: list[UserSkillIn] = Field(default_factory=list)
    evidence: list[EvidenceIn] = Field(default_factory=list)
    data_version: str = "示例数据 v0.1"
    disclaimer: str = ""


class GapItem(CamelModel):
    skill_id: str
    skill_name: str
    dim: str
    weight: int
    target: int
    level: int
    effective_level: int
    gap: int
    priority: int
    status: Literal["missing", "weak", "mastered"]
    has_evidence: bool
    evidence_strength: int


class BonusItem(CamelModel):
    skill_id: str
    skill_name: str
    dim: str
    weight: int
    target: int
    level: int
    status: Literal["mastered", "pending"]


class RadarAxis(CamelModel):
    dim: str
    name: str
    target: float | None
    current: float | None
    has_core: bool
    core_weight: int


class Radar(CamelModel):
    axes: list[RadarAxis]
    data_axis_count: int


class MatchCounts(CamelModel):
    """计数一律带前缀：required* 只统计必备项，bonus* 只统计加分项。

    不加前缀的 `mastered` 会被误读成「总共掌握了几项」，而必备与加分本来就不该相加。
    """

    required_mastered: int
    required_weak: int
    required_missing: int
    required_total: int
    bonus_mastered: int
    bonus_pending: int
    evidence_covered: int


class MatchMeta(CamelModel):
    scoring_version: str
    display_badge: str
    disclaimer: str


class MatchOut(CamelModel):
    job_id: str
    job_name: str
    user_id: int | None
    # 刻意不叫 matchScore：那个名字会让人以为它是「总分」，而契约要求必备/加分分开出分。
    required_score: float | None = Field(description="只由自评等级决定；无法算分时为 null")
    bonus_score: float | None
    status: Literal["ok", "NO_REQUIRED_REQUIREMENT", "EMPTY_PROFILE"]
    numerator: int
    denominator: int
    bonus_numerator: int
    bonus_denominator: int
    evidence_coverage: float | None = Field(description="证据覆盖面，独立展示，不进 requiredScore")
    counts: MatchCounts
    gaps: list[GapItem]
    top_actions: list[str]
    bonus_items: list[BonusItem]
    radar: Radar
    meta: MatchMeta
