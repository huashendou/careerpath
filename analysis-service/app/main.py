"""FastAPI 入口：装配路由、CORS、统一异常处理。

异常一律输出 `{"detail": {"code", "message"}}`（看板 3.1）。
成功响应直接返回资源对象，不包 `code/data` 封套。
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from sqlalchemy import func, select, text
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.api import analysis, auth, catalog, profiles, reports, resume
from app.core.config import settings
from app.core.errors import ApiError, error_body
from app.db.base import Base
from app.db.session import SessionLocal, engine
from app.models import entities  # noqa: F401  让 Base.metadata 收集到全部表

logging.basicConfig(
    level=logging.DEBUG if settings.debug else logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s | %(message)s",
)
logger = logging.getLogger("radar")

HTTP_CODE_NAMES = {
    400: "BAD_REQUEST",
    401: "UNAUTHORIZED",
    403: "FORBIDDEN",
    404: "NOT_FOUND",
    405: "METHOD_NOT_ALLOWED",
    500: "INTERNAL_ERROR",
}


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("启动 %s | 数据库：%s", settings.app_name, settings.safe_database_label)
    if settings.auto_create_tables:
        try:
            Base.metadata.create_all(bind=engine)
            logger.info("表结构已就绪（create_all）")
        except Exception as exc:  # 数据库不可用时也要能打开 /docs 看接口
            logger.warning("建表失败，接口将不可用：%s", exc)
    yield
    logger.info("已停止")


app = FastAPI(
    title="职能雷达 API",
    description=(
        "大学生实习求职技能匹配与能力差距分析。\n\n"
        "算分口径为 **core-only**：加分项（plus）不进入匹配度的分子与分母。\n"
        "接口契约见 `docs/协作看板.md` 第三节（c2.1）。"
    ),
    version="0.2.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origin_list,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

for router in (
    catalog.router,
    auth.router,
    profiles.router,
    analysis.router,
    reports.router,
    resume.router,
):
    app.include_router(router, prefix=settings.api_prefix)


@app.exception_handler(ApiError)
async def handle_api_error(request: Request, exc: ApiError) -> JSONResponse:
    return JSONResponse(status_code=exc.status, content=error_body(exc.code, exc.message))


@app.exception_handler(RequestValidationError)
async def handle_validation_error(
    request: Request, exc: RequestValidationError
) -> JSONResponse:
    errors = exc.errors()
    first = errors[0] if errors else {}
    location = ".".join(str(part) for part in first.get("loc", ()) if part != "body")
    message = f"{location}：{first.get('msg', '参数不合法')}" if location else first.get(
        "msg", "参数不合法"
    )
    return JSONResponse(status_code=422, content=error_body("VALIDATION_ERROR", message))


@app.exception_handler(StarletteHTTPException)
async def handle_http_exception(
    request: Request, exc: StarletteHTTPException
) -> JSONResponse:
    code = HTTP_CODE_NAMES.get(exc.status_code, "HTTP_ERROR")
    return JSONResponse(status_code=exc.status_code, content=error_body(code, str(exc.detail)))


@app.exception_handler(Exception)
async def handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
    logger.exception("未捕获异常：%s %s", request.method, request.url.path)
    return JSONResponse(
        status_code=500, content=error_body("INTERNAL_ERROR", "服务器内部错误")
    )


@app.get("/api/health", tags=["运维"], summary="健康检查")
def health() -> dict:
    """给前端与自检脚本用：能否连库、种子数据是否已导入。"""
    try:
        with SessionLocal() as db:
            db.execute(text("SELECT 1"))
            job_count = db.execute(select(func.count()).select_from(entities.Job)).scalar_one()
            skill_count = (
                db.execute(select(func.count()).select_from(entities.Skill)).scalar_one()
            )
        return {
            "status": "ok",
            "database": settings.safe_database_label,
            "jobs": job_count,
            "skills": skill_count,
        }
    except Exception as exc:
        logger.warning("健康检查失败：%s", exc)
        return {"status": "degraded", "database": settings.safe_database_label}


@app.get("/", include_in_schema=False)
def index() -> dict:
    return {"service": "职能雷达 API", "docs": "/docs"}
