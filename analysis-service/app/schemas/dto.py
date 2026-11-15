"""出入参 DTO（Pydantic v2）。

字段名直接用 camelCase：这一层就是给 HTTP 用的，不做二次命名转换。
领域模型（ORM）才用 snake_case。

契约依据：`docs/协作看板.md` 第三节（c2.1）。改字段先改看板，再改这里。
"""

from __future__ import annotations

from datetime import datetime
from typing import Annotated, Any

from pydantic import BaseModel, ConfigDict, Field, PlainSerializer

from app.db.base import to_iso_z

# 时间统一输出 2026-11-15T08:30:00Z
IsoZ = Annotated[datetime, PlainSerializer(lambda v: to_iso_z(v), return_type=str)]


class StrictIn(BaseModel):
    """所有请求体都拒绝未知字段：传错字段名要 422 报错，而不是被静默忽略。"""

    model_config = ConfigDict(extra="forbid")


# ---------------- 认证 ----------------


class RegisterIn(StrictIn):
    username: str = Field(min_length=3, max_length=32, pattern=r"^[A-Za-z0-9_]+$")
    password: str = Field(min_length=6, max_length=64)


class LoginIn(StrictIn):
    username: str = Field(min_length=1, max_length=32)
    password: str = Field(min_length=1, max_length=64)


class UserOut(BaseModel):
    id: int
    username: str
    role: str
    createdAt: IsoZ


class AuthOut(BaseModel):
    accessToken: str
    tokenType: str = "bearer"
    user: UserOut


# ---------------- 技能词典与岗位 ----------------


class DimensionOut(BaseModel):
    id: str
    name: str
    short: str
    desc: str
    why: str


class LevelOut(BaseModel):
    level: int
    label: str
    anchor: str
    typical: str


class CatalogOut(BaseModel):
    """GET /api/skills/dimensions

    c2.1 补丁：额外返回 dataVersion / disclaimer / scoringVersion。
    原契约只在 MatchResult.meta 里给免责声明，导致自评页首次进入时拿不到，
    而自评页恰恰是最需要提示「这是示例权重」的地方。
    """

    dimensions: list[DimensionOut]
    levels: list[LevelOut]
    dataVersion: str
    disclaimer: str
    scoringVersion: str


class SkillOut(BaseModel):
    id: str
    name: str
    dim: str
    group: str
    note: str
    aliases: list[str] = Field(default_factory=list)


class SkillsOut(BaseModel):
    total: int
    skills: list[SkillOut]


class JobSummaryOut(BaseModel):
    id: str
    name: str
    shortName: str
    seniority: str
    summary: str
    displayBadge: str
    requirementCount: int
    coreRequirementCount: int
    coreWeightSum: int
    plusWeightSum: int


class JobsOut(BaseModel):
    jobs: list[JobSummaryOut]


class RequirementOut(BaseModel):
    skillId: str
    skillName: str
    dim: str
    weight: int
    target: int
    type: str
    anyOf: list[str] = Field(default_factory=list)
    anyOfNote: str | None = None


class JobDetailOut(BaseModel):
    id: str
    name: str
    shortName: str
    seniority: str
    summary: str
    notes: str
    displayBadge: str
    scoringScope: str
    requirements: list[RequirementOut]


# ---------------- 画像 ----------------


class ProfileSkillOut(BaseModel):
    skillId: str
    name: str
    dim: str
    level: int


class ProfileOut(BaseModel):
    id: int
    name: str
    createdAt: IsoZ
    updatedAt: IsoZ
    skills: list[ProfileSkillOut]


class ProfileListItemOut(BaseModel):
    id: int
    name: str
    updatedAt: IsoZ
    skillCount: int


class ProfilesOut(BaseModel):
    profiles: list[ProfileListItemOut]


class SkillLevelIn(StrictIn):
    skillId: str = Field(min_length=1, max_length=64)
    # 允许任意类型进来，由 clamp_level 统一收敛到 0–5（小数四舍五入、越界夹取）
    level: Any = 0


class ProfileCreateIn(StrictIn):
    name: str = Field(default="我的求职画像", min_length=1, max_length=64)


class ProfileUpdateIn(StrictIn):
    name: str | None = Field(default=None, min_length=1, max_length=64)
    skills: list[SkillLevelIn] | None = None


# ---------------- 匹配与差距分析 ----------------


class GapItemOut(BaseModel):
    skillId: str
    skillName: str
    dim: str
    weight: int
    target: int
    level: int
    effectiveLevel: int
    gap: int
    priority: int
    status: str


class PlusItemOut(BaseModel):
    skillId: str
    skillName: str
    dim: str
    weight: int
    target: int
    level: int
    status: str


class RadarAxisOut(BaseModel):
    dim: str
    name: str
    target: float | None
    current: float | None
    hasCore: bool
    coreWeight: int


class RadarOut(BaseModel):
    axes: list[RadarAxisOut]
    dataAxisCount: int


class CountsOut(BaseModel):
    mastered: int
    weak: int
    missing: int
    plusMastered: int
    plusPending: int


class MatchMetaOut(BaseModel):
    scoringVersion: str
    displayBadge: str
    disclaimer: str


class MatchResultOut(BaseModel):
    """POST /api/analysis/match 的响应体。

    c2.1 补丁：新增 numerator / denominator。它们是匹配度的组成项，
    暴露出来是为了让评审能直接验算「分母 255 不是 100」这条硬规则，不必反推。
    """

    jobId: str
    jobName: str
    profileId: int | None
    matchScore: float | None
    status: str
    numerator: int
    denominator: int
    counts: CountsOut
    gaps: list[GapItemOut]
    topActions: list[str]
    plusItems: list[PlusItemOut]
    radar: RadarOut
    meta: MatchMetaOut


class MatchIn(StrictIn):
    """profileId 为 null 时必须同时传 skills 做试算（不落库）。"""

    jobId: str = Field(min_length=1, max_length=32)
    profileId: int | None = None
    skills: list[SkillLevelIn] | None = None


# ---------------- 报告存档 ----------------


class ReportCreateIn(StrictIn):
    profileId: int
    jobId: str = Field(min_length=1, max_length=32)


class ReportListItemOut(BaseModel):
    id: int
    jobId: str
    jobName: str
    matchScore: float | None
    createdAt: IsoZ


class ReportsOut(BaseModel):
    reports: list[ReportListItemOut]


class ReportDetailOut(MatchResultOut):
    id: int
    createdAt: IsoZ


# ---------------- 简历解析 ----------------


class ResumeParseIn(StrictIn):
    text: str = Field(min_length=1, max_length=20000)


class SuggestionOut(BaseModel):
    skillId: str
    name: str
    dim: str
    level: int
    matchedAlias: str
    evidence: str


class ResumeParseOut(BaseModel):
    suggestions: list[SuggestionOut]
