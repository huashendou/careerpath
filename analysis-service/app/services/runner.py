"""把数据库里的岗位模型喂给算分服务。

这一层负责「查库 + 组装」，算分逻辑一行都不在这里——`matching.py` 保持纯函数，
才能用 pytest 在不起数据库的情况下验完所有口径。
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.orm import Session, selectinload

from app.api.deps import build_skill_index, load_dimensions, load_meta
from app.core.errors import JobNotFound
from app.models.entities import Job, Profile, ProfileSkill
from app.services.matching import analyze


def ratings_of(profile: Profile) -> dict[str, int]:
    """画像 → {skillId: level}。level 在写入时已夹取过，这里直接取。"""
    return {ps.skill_id: ps.level for ps in profile.skills}


def run_match(
    db: Session,
    *,
    job_id: str,
    ratings: dict[str, int],
    profile_id: int | None = None,
) -> dict:
    job = db.execute(
        select(Job).options(selectinload(Job.requirements)).where(Job.id == job_id)
    ).scalar_one_or_none()
    if job is None:
        raise JobNotFound()

    meta = load_meta(db)
    return analyze(
        job_id=job.id,
        job_name=job.name,
        requirements=[
            {
                "skillId": r.skill_id,
                "weight": r.weight,
                "target": r.target,
                "type": r.req_type,
                "anyOf": list(r.any_of or []),
            }
            for r in sorted(job.requirements, key=lambda r: r.sort_order)
        ],
        ratings=ratings,
        skills=build_skill_index(db),
        dimensions=load_dimensions(db),
        data_version=job.data_version,
        disclaimer=meta.disclaimer if meta else "",
        scoring_version=meta.scoring_version if meta else "v0.2-core-only",
        profile_id=profile_id,
    )


def ratings_from_input(items) -> dict[str, int]:
    """请求里的 [{skillId, level}] → {skillId: level}。重复 id 保留首条。"""
    from app.services.matching import clamp_level

    result: dict[str, int] = {}
    for item in items or []:
        result.setdefault(item.skillId, clamp_level(item.level))
    return result


def known_skill_ids(db: Session, ids: set[str]) -> set[str]:
    from app.models.entities import Skill

    if not ids:
        return set()
    return set(db.execute(select(Skill.id).where(Skill.id.in_(ids))).scalars().all())
