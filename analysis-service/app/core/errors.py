"""业务异常。

错误体统一为 `{"detail": {"code": "...", "message": "..."}}`（看板 3.1）。
`code` 是**字符串**枚举，前端按它分支；`message` 是给人看的中文提示。

为什么不用数字码：数字码表要靠文档对照，字符串自带语义，前端 `if (code === "USERNAME_TAKEN")`
比 `if (code === 2003)` 更难写错。HTTP 状态码仍照常设置，两者不冲突。
"""

from __future__ import annotations


class ApiError(Exception):
    """所有可预期错误的基类。子类只声明 status / code / message 三个类属性。"""

    status: int = 400
    code: str = "BAD_REQUEST"
    message: str = "请求不合法"

    def __init__(self, message: str | None = None) -> None:
        if message:
            self.message = message
        super().__init__(self.message)


# ---------------- 401 ----------------
class Unauthorized(ApiError):
    status = 401
    code = "UNAUTHORIZED"
    message = "未登录或登录已失效"


class InvalidCredentials(ApiError):
    status = 401
    code = "INVALID_CREDENTIALS"
    message = "用户名或密码不正确"


# ---------------- 404 ----------------
class JobNotFound(ApiError):
    status = 404
    code = "JOB_NOT_FOUND"
    message = "岗位不存在"


class ProfileNotFound(ApiError):
    status = 404
    code = "PROFILE_NOT_FOUND"
    message = "画像不存在"


class ReportNotFound(ApiError):
    status = 404
    code = "REPORT_NOT_FOUND"
    message = "报告不存在"


# ---------------- 409 ----------------
class UsernameTaken(ApiError):
    status = 409
    code = "USERNAME_TAKEN"
    message = "用户名已存在"


class ProfileLimit(ApiError):
    status = 409
    code = "PROFILE_LIMIT"
    message = "画像数量已达上限（10 份），请先删除不用的画像"


# ---------------- 422 ----------------
class ValidationError(ApiError):
    status = 422
    code = "VALIDATION_ERROR"
    message = "参数校验失败"


class UnknownSkill(ApiError):
    status = 422
    code = "UNKNOWN_SKILL"
    message = "技能不存在"

    def __init__(self, skill_ids: list[str] | None = None) -> None:
        if skill_ids:
            self.message = f"技能不存在：{', '.join(sorted(skill_ids)[:5])}"
        super().__init__()


def error_body(code: str, message: str) -> dict:
    """FastAPI 的 HTTPException(detail=dict) 与自定义 handler 共用这个形状。"""
    return {"detail": {"code": code, "message": message}}
