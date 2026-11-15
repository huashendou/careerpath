"""技能画像 CRUD（看板 3.4）。

隔离规则：所有查询都带 `user_id == 当前用户`。访问他人画像一律 404 PROFILE_NOT_FOUND，
不用 403——403 等于告诉对方「这个 id 存在，只是不属于你」。
"""

from __future__ import annotations

from fastapi import APIRouter, Response
from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session, selectinload

from app.api.deps import CurrentUser, DbSession
from app.core.errors import ProfileLimit, ProfileNotFound, UnknownSkill
from app.models.entities import Profile, ProfileSkill, Skill, User
from app.schemas.dto import (
    ProfileCreateIn,
    ProfileListItemOut,
    ProfileOut,
    ProfilesOut,
    ProfileSkillOut,
    ProfileUpdateIn,
    SkillLevelIn,
)
from app.services.matching import clamp_level

router = APIRouter(prefix="/profiles", tags=["画像"])

MAX_PROFILES = 10


def _owned(db: Session, user: User, profile_id: int) -> Profile:
    profile = db.execute(
        select(Profile)
        .options(selectinload(Profile.skills).selectinload(ProfileSkill.skill))
        .where(Profile.id == profile_id, Profile.user_id == user.id)
    ).scalar_one_or_none()
    if profile is None:
        raise ProfileNotFound()
    return profile


def _out(profile: Profile) -> ProfileOut:
    return ProfileOut(
        id=profile.id,
        name=profile.name,
        createdAt=profile.created_at,
        updatedAt=profile.updated_at,
        skills=[
            ProfileSkillOut(
                skillId=ps.skill_id,
                name=ps.skill.name,
                dim=ps.skill.dim_id,
                level=ps.level,
            )
            for ps in profile.skills
        ],
    )


def _assert_known(db: Session, skills: list[SkillLevelIn]) -> None:
    ids = {s.skillId for s in skills}
    if not ids:
        return
    known = set(db.execute(select(Skill.id).where(Skill.id.in_(ids))).scalars().all())
    unknown = ids - known
    if unknown:
        raise UnknownSkill(sorted(unknown))


@router.post("", response_model=ProfileOut, status_code=201, summary="新建画像")
def create_profile(payload: ProfileCreateIn, user: CurrentUser, db: DbSession) -> ProfileOut:
    count = db.execute(
        select(func.count()).select_from(Profile).where(Profile.user_id == user.id)
    ).scalar_one()
    if count >= MAX_PROFILES:
        raise ProfileLimit()

    profile = Profile(user_id=user.id, name=payload.name)
    db.add(profile)
    db.commit()
    db.refresh(profile)
    return _out(profile)


@router.get("", response_model=ProfilesOut, summary="我的画像列表")
def list_profiles(user: CurrentUser, db: DbSession) -> ProfilesOut:
    rows = db.execute(
        select(Profile)
        .options(selectinload(Profile.skills))
        .where(Profile.user_id == user.id)
        .order_by(Profile.updated_at.desc(), Profile.id.desc())
    ).scalars().all()
    return ProfilesOut(
        profiles=[
            ProfileListItemOut(
                id=p.id, name=p.name, updatedAt=p.updated_at, skillCount=len(p.skills)
            )
            for p in rows
        ]
    )


@router.get("/{profile_id}", response_model=ProfileOut, summary="画像详情")
def get_profile(profile_id: int, user: CurrentUser, db: DbSession) -> ProfileOut:
    return _out(_owned(db, user, profile_id))


@router.put("/{profile_id}", response_model=ProfileOut, summary="保存画像")
def update_profile(
    profile_id: int, payload: ProfileUpdateIn, user: CurrentUser, db: DbSession
) -> ProfileOut:
    profile = _owned(db, user, profile_id)

    if payload.name is not None:
        profile.name = payload.name

    if payload.skills is not None:
        # 全量覆盖：先删后插。逐条 diff 在这里没有价值，客户端本来就提交整份表单。
        _assert_known(db, payload.skills)
        db.execute(delete(ProfileSkill).where(ProfileSkill.profile_id == profile.id))
        db.flush()
        for item in payload.skills:
            db.add(
                ProfileSkill(
                    profile_id=profile.id,
                    skill_id=item.skillId,
                    level=clamp_level(item.level),
                )
            )

    db.commit()
    # skills 关系在删除后已过期，必须重新加载，否则返回的是旧列表
    db.expire(profile, ["skills"])
    profile = _owned(db, user, profile_id)
    return _out(profile)


@router.delete("/{profile_id}", status_code=204, summary="删除画像")
def delete_profile(profile_id: int, user: CurrentUser, db: DbSession) -> Response:
    profile = _owned(db, user, profile_id)
    db.delete(profile)  # profile_skills 由 cascade="all, delete-orphan" 一并清理
    db.commit()
    return Response(status_code=204)
