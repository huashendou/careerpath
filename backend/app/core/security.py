"""认证与口令工具。

口令用 bcrypt 哈希；令牌用 JWT（HS256）。
不引入 passlib——它对新版 bcrypt 的兼容性反复出问题，直接用 bcrypt 更稳。
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

import bcrypt
import jwt

from app.core.config import settings

# bcrypt 只处理前 72 字节，超出部分会被静默截断。宁可显式拒绝，也不要让用户以为长口令更安全。
MAX_PASSWORD_BYTES = 72


def hash_password(password: str) -> str:
    raw = password.encode("utf-8")
    if len(raw) > MAX_PASSWORD_BYTES:
        raise ValueError(f"口令过长：bcrypt 上限 {MAX_PASSWORD_BYTES} 字节（中文约 24 字）")
    return bcrypt.hashpw(raw, bcrypt.gensalt()).decode("utf-8")


def verify_password(password: str, password_hash: str) -> bool:
    try:
        return bcrypt.checkpw(password.encode("utf-8"), password_hash.encode("utf-8"))
    except (ValueError, TypeError):
        return False


def create_access_token(user_id: int, username: str) -> tuple[str, int]:
    """返回 (token, expires_in 秒)。"""
    expires_in = settings.jwt_expire_minutes * 60
    payload: dict[str, Any] = {
        "sub": str(user_id),
        "username": username,
        "iat": datetime.now(UTC),
        "exp": datetime.now(UTC) + timedelta(seconds=expires_in),
    }
    token = jwt.encode(payload, settings.jwt_secret, algorithm=settings.jwt_algorithm)
    return token, expires_in


def decode_access_token(token: str) -> dict[str, Any] | None:
    """解码失败一律返回 None，由调用方决定抛 401。"""
    try:
        return jwt.decode(token, settings.jwt_secret, algorithms=[settings.jwt_algorithm])
    except jwt.PyJWTError:
        return None
