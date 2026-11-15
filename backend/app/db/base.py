"""ORM 基类与时间约定。

时间一律存 **naive UTC**：MySQL 的 DATETIME 不保存时区，存带 tzinfo 的对象会被静默丢弃时区，
读回来再标 UTC 就对了；若存本地时间再标 Z，客户端会看到差 8 小时的错误时刻。
对外序列化统一走 `schemas.dto.IsoZ`，输出 `2026-11-15T08:30:00Z`。
"""

from datetime import UTC, datetime

from sqlalchemy import DateTime
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


def utcnow() -> datetime:
    """当前 UTC 时间，去掉 tzinfo（适配 MySQL DATETIME）。"""
    return datetime.now(UTC).replace(tzinfo=None)


def to_iso_z(value: datetime | None) -> str | None:
    """naive UTC → `2026-11-15T08:30:00Z`。"""
    if value is None:
        return None
    return value.replace(tzinfo=UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


class TimestampMixin:
    """创建/更新时间由应用层生成，避免应用与数据库时钟不一致（容器里很常见）。"""

    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, default=utcnow, onupdate=utcnow, nullable=False
    )
