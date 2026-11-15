"""技能词典与岗位模型（只读、免鉴权）。

对应看板 3.3。四个接口都不需要 token——前端登录前就要能渲染岗位列表。
"""

from __future__ import annotations

from fastapi import APIRouter
from sqlalchemy import select
from sqlalchemy.orm import selectinload

from app.api.deps import DbSession, load_meta
from app.core.errors import JobNotFound
from app.models.entities import Dimension, Job, Skill
from app.schemas.dto import (
    CatalogOut,
    DimensionOut,
    JobDetailOut,
    JobsOut,
    JobSummaryOut,
    LevelOut,
    RequirementOut,
    SkillOut,
    SkillsOut,
)

router = APIRouter(tags=["词典与岗位"])


@router.get("/skills/dimensions", response_model=CatalogOut, summary="六维定义与等级锚点")
def get_catalog(db: DbSession) -> CatalogOut:
    """自评页与雷达图所需的全部静态信息，一次拿全，避免前端硬编码维度名与等级文案。"""
    dims = db.execute(select(Dimension).order_by(Dimension.sort_order)).scalars().all()
    meta = load_meta(db)
    return CatalogOut(
        dimensions=[
            DimensionOut(
                id=d.id, name=d.name, short=d.short, desc=d.description, why=d.why
            )
            for d in dims
        ],
        levels=[LevelOut(**lv) for lv in (meta.levels if meta else [])],
        dataVersion=meta.data_version if meta else "",
        disclaimer=meta.disclaimer if meta else "",
        scoringVersion=meta.scoring_version if meta else "",
    )


@router.get("/skills", response_model=SkillsOut, summary="技能词典")
def list_skills(db: DbSession, dim: str | None = None) -> SkillsOut:
    stmt = select(Skill).order_by(Skill.sort_order)
    if dim:
        stmt = stmt.where(Skill.dim_id == dim)
    rows = db.execute(stmt).scalars().all()
    return SkillsOut(
        total=len(rows),
        skills=[
            SkillOut(
                id=s.id,
                name=s.name,
                dim=s.dim_id,
                group=s.skill_group,
                note=s.note,
                aliases=list(s.aliases or []),
            )
            for s in rows
        ],
    )


def _summary(job: Job) -> JobSummaryOut:
    reqs = job.requirements
    core = [r for r in reqs if r.req_type == "core"]
    plus = [r for r in reqs if r.req_type == "plus"]
    return JobSummaryOut(
        id=job.id,
        name=job.name,
        shortName=job.short_name,
        seniority=job.seniority,
        summary=job.summary,
        displayBadge=job.data_version,
        requirementCount=len(reqs),
        coreRequirementCount=len(core),
        coreWeightSum=sum(r.weight for r in core),
        plusWeightSum=sum(r.weight for r in plus),
    )


@router.get("/jobs", response_model=JobsOut, summary="岗位列表")
def list_jobs(db: DbSession) -> JobsOut:
    """不含 requirements：三个岗位一共 86 条要求，列表页不需要。"""
    jobs = (
        db.execute(
            select(Job).options(selectinload(Job.requirements)).order_by(Job.sort_order)
        )
        .scalars()
        .all()
    )
    return JobsOut(jobs=[_summary(j) for j in jobs])


@router.get("/jobs/{job_id}", response_model=JobDetailOut, summary="岗位详情")
def get_job(job_id: str, db: DbSession) -> JobDetailOut:
    job = db.get(Job, job_id)
    if job is None:
        raise JobNotFound()

    reqs = sorted(job.requirements, key=lambda r: r.sort_order)
    return JobDetailOut(
        id=job.id,
        name=job.name,
        shortName=job.short_name,
        seniority=job.seniority,
        summary=job.summary,
        notes=job.notes,
        displayBadge=job.data_version,
        scoringScope=job.scoring_scope,
        requirements=[
            RequirementOut(
                skillId=r.skill_id,
                skillName=r.skill.name,
                dim=r.skill.dim_id,
                weight=r.weight,
                target=r.target,
                type=r.req_type,
                # 恒为数组：无成员时是 []，不是 null。前端不必判空。
                anyOf=list(r.any_of or []),
                anyOfNote=r.any_of_note,
            )
            for r in reqs
        ],
    )
