"""注册、登录、当前用户（看板 3.2）。

- 用户名唯一性由数据库 unique 约束兜底，不只靠应用层查重（并发注册时查重会漏）。
- 口令用 bcrypt；`password_hash` 不出现在任何响应模型里。
"""

from __future__ import annotations

from fastapi import APIRouter
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from app.api.deps import CurrentUser, DbSession
from app.core.errors import InvalidCredentials, UsernameTaken, ValidationError
from app.core.security import create_access_token, hash_password, verify_password
from app.models.entities import User
from app.schemas.dto import AuthOut, LoginIn, RegisterIn, UserOut

router = APIRouter(prefix="/auth", tags=["认证"])


def _user_out(user: User) -> UserOut:
    return UserOut(
        id=user.id, username=user.username, role=user.role, createdAt=user.created_at
    )


@router.post("/register", response_model=AuthOut, status_code=201, summary="注册")
def register(payload: RegisterIn, db: DbSession) -> AuthOut:
    if db.execute(select(User.id).where(User.username == payload.username)).scalar_one_or_none():
        raise UsernameTaken()

    try:
        password_hash = hash_password(payload.password)
    except ValueError as exc:  # bcrypt 72 字节上限
        raise ValidationError(str(exc)) from exc

    user = User(username=payload.username, password_hash=password_hash, role="student")
    db.add(user)
    try:
        db.commit()
    except IntegrityError:  # 并发下唯一约束兜底
        db.rollback()
        raise UsernameTaken() from None
    db.refresh(user)

    token, _ = create_access_token(user.id, user.username)
    return AuthOut(accessToken=token, user=_user_out(user))


@router.post("/login", response_model=AuthOut, summary="登录")
def login(payload: LoginIn, db: DbSession) -> AuthOut:
    user = db.execute(
        select(User).where(User.username == payload.username)
    ).scalar_one_or_none()
    # 用户不存在与密码错误返回同一个错误，避免暴露账号是否注册过
    if user is None or not verify_password(payload.password, user.password_hash):
        raise InvalidCredentials()

    token, _ = create_access_token(user.id, user.username)
    return AuthOut(accessToken=token, user=_user_out(user))


@router.get("/me", response_model=UserOut, summary="当前用户")
def me(user: CurrentUser) -> UserOut:
    return _user_out(user)
