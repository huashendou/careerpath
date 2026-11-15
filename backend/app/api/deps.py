"""路由公共依赖：数据库会话、当前用户、词典索引。"""

from __future__ import annotations

from typing import Annotated

from fastapi import Depends
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select
from sqlalchemy.orm import Session

from app.core.errors import Unauthorized
from app.core.security import decode_access_token
from app.db.session import get_db
from app.models.entities import AppMeta, Dimension, Skill, User

bearer_scheme = HTTPBearer(auto_error=False, description="JWT，格式：Bearer <token>")

DbSession = Annotated[Session, Depends(get_db)]
Credentials = Annotated[HTTPAuthorizationCredentials | None, Depends(bearer_scheme)]


def get_current_user(credentials: Credentials, db: DbSession) -> User:
    """所有需要鉴权的接口都走这里。Token 无效与用户不存在都返回 401，不区分原因。"""
    if credentials is None or not credentials.credentials:
        raise Unauthorized()
    payload = decode_access_token(credentials.credentials)
    if not payload:
        raise Unauthorized()
    try:
        user_id = int(payload.get("sub", ""))
    except (TypeError, ValueError):
        raise Unauthorized() from None
    user = db.get(User, user_id)
    if user is None:
        raise Unauthorized()
    return user


CurrentUser = Annotated[User, Depends(get_current_user)]


def build_skill_index(db: Session) -> dict[str, dict[str, str]]:
    """skillId -> {name, dimId, dimName}。算分服务要它来补全展示字段。"""
    rows = db.execute(
        select(Skill.id, Skill.name, Dimension.id, Dimension.name).join(
            Dimension, Skill.dim_id == Dimension.id
        )
    ).all()
    return {
        skill_id: {"name": name, "dimId": dim_id, "dimName": dim_name}
        for skill_id, name, dim_id, dim_name in rows
    }


def load_dimensions(db: Session) -> list[dict[str, str]]:
    """六维定义，顺序固定，雷达轴按它排列。"""
    rows = db.execute(select(Dimension).order_by(Dimension.sort_order)).scalars().all()
    return [{"id": d.id, "name": d.name, "short": d.short} for d in rows]


def load_meta(db: Session) -> AppMeta | None:
    return db.get(AppMeta, 1)
