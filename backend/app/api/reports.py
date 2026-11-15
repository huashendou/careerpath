"""报告存档（看板 3.6）。

报告是**快照**：把整份 MatchResult 存进 payload。岗位权重以后调整，历史报告也不会被改写——
用户拿着三个月前的报告问「为什么当时是 41%」，答案必须还是当时那份模型算出来的。
"""

from __future__ import annotations

from decimal import Decimal

from fastapi import APIRouter
from sqlalchemy import select
from sqlalchemy.orm import selectinload

from app.api.deps import CurrentUser, DbSession
from app.core.errors import ProfileNotFound, ReportNotFound
from app.models.entities import Job, Profile, ProfileSkill, Report
from app.schemas.dto import (
    ReportCreateIn,
    ReportDetailOut,
    ReportListItemOut,
    ReportsOut,
)
from app.services.runner import ratings_of, run_match

router = APIRouter(prefix="/reports", tags=["报告"])


@router.post("", response_model=ReportDetailOut, status_code=201, summary="存档一份报告")
def create_report(payload: ReportCreateIn, user: CurrentUser, db: DbSession) -> ReportDetailOut:
    profile = db.execute(
        select(Profile)
        .options(selectinload(Profile.skills).selectinload(ProfileSkill.skill))
        .where(Profile.id == payload.profileId, Profile.user_id == user.id)
    ).scalar_one_or_none()
    if profile is None:
        raise ProfileNotFound()

    result = run_match(
        db, job_id=payload.jobId, ratings=ratings_of(profile), profile_id=profile.id
    )

    score = result["matchScore"]
    report = Report(
        user_id=user.id,
        job_id=result["jobId"],
        profile_id=profile.id,
        match_score=Decimal(str(score)) if score is not None else None,
        payload=result,
    )
    db.add(report)
    db.commit()
    db.refresh(report)

    return ReportDetailOut(id=report.id, createdAt=report.created_at, **result)


@router.get("", response_model=ReportsOut, summary="我的报告列表")
def list_reports(user: CurrentUser, db: DbSession) -> ReportsOut:
    rows = db.execute(
        select(Report, Job.name)
        .join(Job, Job.id == Report.job_id)
        .where(Report.user_id == user.id)
        .order_by(Report.created_at.desc(), Report.id.desc())
    ).all()
    return ReportsOut(
        reports=[
            ReportListItemOut(
                id=report.id,
                jobId=report.job_id,
                jobName=job_name,
                matchScore=float(report.match_score) if report.match_score is not None else None,
                createdAt=report.created_at,
            )
            for report, job_name in rows
        ]
    )


@router.get("/{report_id}", response_model=ReportDetailOut, summary="报告详情")
def get_report(report_id: int, user: CurrentUser, db: DbSession) -> ReportDetailOut:
    report = db.execute(
        select(Report).where(Report.id == report_id, Report.user_id == user.id)
    ).scalar_one_or_none()
    if report is None:
        raise ReportNotFound()
    return ReportDetailOut(id=report.id, createdAt=report.created_at, **report.payload)
