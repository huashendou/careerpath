"""匹配与差距分析接口（看板 3.5）。

两种用法：
1. 传 `profileId`：按库里已保存的画像算分。
2. `profileId` 为 null 且传 `skills`：试算，不落库。前端「改一个等级看分数怎么变」
   走的就是这条路，避免每次调参都要先存一次画像。

两种情况都必须登录：算分结果会带「你的差距」，匿名不应该能拿到别人的画像结论。
"""

from __future__ import annotations

from fastapi import APIRouter
from sqlalchemy import select
from sqlalchemy.orm import selectinload

from app.api.deps import CurrentUser, DbSession
from app.core.errors import ProfileNotFound, UnknownSkill, ValidationError
from app.models.entities import Profile, ProfileSkill
from app.schemas.dto import MatchIn, MatchResultOut
from app.services.runner import known_skill_ids, ratings_from_input, ratings_of, run_match

router = APIRouter(prefix="/analysis", tags=["分析"])


@router.post("/match", response_model=MatchResultOut, summary="匹配度与能力差距")
def match(payload: MatchIn, user: CurrentUser, db: DbSession) -> MatchResultOut:
    if payload.profileId is None:
        if payload.skills is None:
            raise ValidationError("profileId 为 null 时必须传 skills 做试算")
        unknown = {s.skillId for s in payload.skills} - known_skill_ids(
            db, {s.skillId for s in payload.skills}
        )
        if unknown:
            raise UnknownSkill(sorted(unknown))
        ratings = ratings_from_input(payload.skills)
        profile_id = None
    else:
        profile = db.execute(
            select(Profile)
            .options(selectinload(Profile.skills).selectinload(ProfileSkill.skill))
            .where(Profile.id == payload.profileId, Profile.user_id == user.id)
        ).scalar_one_or_none()
        if profile is None:
            raise ProfileNotFound()
        ratings = ratings_of(profile)
        profile_id = profile.id

    result = run_match(db, job_id=payload.jobId, ratings=ratings, profile_id=profile_id)
    return MatchResultOut(**result)
