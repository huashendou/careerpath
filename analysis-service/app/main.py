"""职航 CareerPath · 智能分析服务（内部服务）。

定位（AGENTS.md 第四节）：**只做解析、标准化、打分、差距与统计聚合，不持有用户会话**。
因此这里没有登录、没有 CORS、没有业务表写入，只有一个共享密钥闸门和少量内部接口。

调用方只有 Java 主服务：`X-Internal-Token` 不匹配一律 401。
本服务监听 127.0.0.1，不对公网暴露；密钥是第二道闸，不是唯一防线。
"""

from __future__ import annotations

import logging
import uuid
from contextlib import asynccontextmanager
from datetime import UTC, datetime

from fastapi import Depends, FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.config import settings
from app.core.dictionary import store
from app.core.errors import InternalError, ValidationError, error_body
from app.schemas.internal import (
    DictionaryStats,
    HealthOut,
    MatchIn,
    MatchOut,
    ParseJdIn,
    ParseJdOut,
)
from app.services.jd_parser import ALGORITHM_VERSION, parse_jd
from app.services.matching import ALGORITHM_VERSION as MATCH_ALGORITHM_VERSION
from app.services.matching import NO_CORE_REQUIREMENT, analyze

SERVICE_VERSION = "0.1.0"

logging.basicConfig(
    level=logging.DEBUG if settings.debug else logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] | %(message)s",
)
logger = logging.getLogger("careerpath.analysis")


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("启动 %s | 词典文件：%s", settings.app_name, settings.skills_path)
    try:
        data = store.get()
        logger.info("词典就绪：%s 类 / %s 技能", len(data.categories), len(data.skills))
    except Exception as exc:  # 词典坏了也要能起，健康检查会报 degraded，便于定位
        logger.error("技能词典加载失败，解析接口将不可用：%s", exc)
    yield
    logger.info("已停止")


app = FastAPI(
    title="职航 CareerPath · 分析服务",
    description=(
        "仅供 Java 主服务内部调用，不对外暴露。\n\n"
        "接口契约见 `docs/协作看板.md` 第三节 c3.0 的「Java → Python 内部接口」。"
    ),
    version=SERVICE_VERSION,
    docs_url=f"{settings.api_prefix}/docs",
    openapi_url=f"{settings.api_prefix}/openapi.json",
    lifespan=lifespan,
)


def require_internal_token(x_internal_token: str = Header(default="")) -> None:
    """所有 /internal 接口的统一闸门。"""

    from app.core.errors import UnauthorizedInternal

    if not settings.internal_token:
        # 没配密钥时宁可全部拒绝，也不要退化成「谁都能调」
        raise InternalError("分析服务未配置内部密钥，拒绝所有调用")
    if x_internal_token != settings.internal_token:
        raise UnauthorizedInternal()


@app.middleware("http")
async def trace_id_middleware(request: Request, call_next):
    """透传 Java 生成的 traceId，两进程的日志能对上同一笔请求。"""
    trace_id = request.headers.get("X-Trace-Id") or uuid.uuid4().hex[:16]
    request.state.trace_id = trace_id
    response = await call_next(request)
    response.headers["X-Trace-Id"] = trace_id
    return response


@app.exception_handler(InternalError)
async def handle_internal_error(request: Request, exc: InternalError) -> JSONResponse:
    trace_id = getattr(request.state, "trace_id", "-")
    logger.warning("[%s] %s %s → %s %s", trace_id, request.method, request.url.path, exc.status, exc.code)
    return JSONResponse(status_code=exc.status, content=error_body(exc.code, exc.message))


@app.exception_handler(RequestValidationError)
async def handle_validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
    errors = exc.errors()
    first = errors[0] if errors else {}
    location = ".".join(str(part) for part in first.get("loc", ()) if part != "body")
    message = f"{location}：{first.get('msg', '参数不合法')}" if location else first.get(
        "msg", "参数不合法"
    )
    return JSONResponse(status_code=422, content=error_body("VALIDATION_ERROR", message))


@app.exception_handler(StarletteHTTPException)
async def handle_http_exception(request: Request, exc: StarletteHTTPException) -> JSONResponse:
    code = {404: "NOT_FOUND", 405: "METHOD_NOT_ALLOWED"}.get(exc.status_code, "HTTP_ERROR")
    return JSONResponse(status_code=exc.status_code, content=error_body(code, str(exc.detail)))


@app.exception_handler(Exception)
async def handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
    trace_id = getattr(request.state, "trace_id", "-")
    logger.exception("[%s] 未捕获异常：%s %s", trace_id, request.method, request.url.path)
    return JSONResponse(status_code=500, content=error_body("INTERNAL_ERROR", "分析服务内部错误"))


@app.get(
    f"{settings.api_prefix}/health",
    response_model=HealthOut,
    tags=["运维"],
    summary="健康检查",
)
def health(_: None = Depends(require_internal_token)) -> HealthOut:
    """给启动脚本与 Java 侧探活用。

    健康检查会**主动补一次懒加载**：词典文件后置挂载、或热修后重启失败时，
    这里能自愈而不是一直报 degraded。

    字典未加载时返回 `degraded` 而不是 500：服务进程本身是活的，
    真正不可用的是解析能力，这两种状态要能区分开。
    """
    try:
        data = store.get()
        dictionary = DictionaryStats(
            loaded=True,
            version=data.version,
            category_count=len(data.categories),
            skill_count=len(data.skills),
        )
    except Exception as exc:  # 词典文件缺失或格式错误
        logger.warning("健康检查：词典不可用（%s）", exc)
        dictionary = DictionaryStats(loaded=False)

    return HealthOut(
        status="ok" if dictionary.loaded else "degraded",
        service="careerpath-analysis",
        version=SERVICE_VERSION,
        time=datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
        dictionary=dictionary,
    )


@app.post(
    f"{settings.api_prefix}/parse/jd",
    response_model=ParseJdOut,
    tags=["解析"],
    summary="JD 文本 → 技能要求建议",
)
def parse_jd_endpoint(payload: ParseJdIn, _: None = Depends(require_internal_token)) -> ParseJdOut:
    """**只出建议，不落库**。Java 侧必须让用户逐条确认后再写 `job_skill_requirement`。"""
    text = payload.text.strip()
    if len(text) > settings.max_text_length:
        raise ValidationError(f"文本过长：上限 {settings.max_text_length} 字符")

    data = store.get()
    requirements, conditions = parse_jd(text, data)
    required = sum(1 for item in requirements if item.req_type == "required")
    bonus = len(requirements) - required

    logger.info(
        "JD 解析完成：%s 条（必备 %s / 加分 %s）jobTitle=%s",
        len(requirements),
        required,
        bonus,
        payload.job_title or "-",
    )
    return ParseJdOut(
        requirements=requirements,
        conditions=conditions,
        matched_count=len(requirements),
        required_count=required,
        bonus_count=bonus,
        algorithm_version=ALGORITHM_VERSION,
        dictionary_version=data.version,
        disclaimer=data.disclaimer,
    )


@app.post(
    f"{settings.api_prefix}/match",
    response_model=MatchOut,
    tags=["匹配"],
    summary="岗位要求 + 个人技能 → 匹配度、差距与解释",
)
def match_endpoint(payload: MatchIn, _: None = Depends(require_internal_token)) -> MatchOut:
    """**纯计算，不落库**。Java 侧负责取数与保存结果，并记录 `algorithmVersion`。

    证据只影响 `evidenceCoverage` 与 `hasEvidence`，**不改 matchScore**：
    没上传材料就扣分，与「加分项进分母」是同一种错误。
    """
    data = store.get()
    skills = {s.id: {"name": s.name, "dimId": s.dim} for s in data.skills}
    dimensions = [{"id": c.id, "name": c.name} for c in data.categories]

    ratings = {item.skill_id: item.level for item in payload.user_skills}
    evidence = {item.skill_id: item.strength for item in payload.evidence}
    requirements = [
        {
            "skillId": item.skill_id,
            "type": item.req_type,
            "weight": item.weight,
            "target": item.target_level,
            "anyOf": item.any_of,
        }
        for item in payload.requirements
    ]

    result = analyze(
        job_id=payload.job_id,
        job_name=payload.job_name,
        requirements=requirements,
        ratings=ratings,
        skills=skills,
        dimensions=dimensions,
        evidence=evidence,
        data_version=payload.data_version,
        disclaimer=payload.disclaimer or data.disclaimer,
        scoring_version=MATCH_ALGORITHM_VERSION,
        user_id=payload.user_id,
    )

    logger.info(
        "匹配完成：job=%s status=%s score=%s num/den=%s/%s gaps=%s coverage=%s",
        payload.job_id,
        result["status"],
        result["matchScore"],
        result["numerator"],
        result["denominator"],
        len(result["gaps"]),
        result["evidenceCoverage"],
    )
    if result["status"] == NO_CORE_REQUIREMENT:
        # 不是错误：这个岗位确实没有必备要求，结论就是「无法算分」。
        logger.warning("岗位无可算分的必备要求：job=%s", payload.job_id)
    return MatchOut.model_validate(result)
