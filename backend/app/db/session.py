"""数据库引擎与会话。

只做三件事：建引擎、给 Session、提供 FastAPI 依赖。
业务逻辑不写在这里。
"""

from collections.abc import Generator

from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from app.core.config import settings

# SQLite 需要关掉线程检查；MySQL 不需要。
_connect_args = {"check_same_thread": False} if settings.is_sqlite else {}

engine = create_engine(
    settings.database_url,
    echo=settings.db_echo,
    pool_pre_ping=True,
    connect_args=_connect_args,
)

SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


def get_db() -> Generator[Session, None, None]:
    """FastAPI 依赖：每个请求一个会话，结束必关。"""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
