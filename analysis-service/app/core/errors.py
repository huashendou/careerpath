"""内部服务异常。

本服务不面向浏览器，错误体仍沿用全项目统一形状 `{"detail": {"code", "message"}}`，
好处是 Java 侧的错误映射代码只有一份，不用为内部调用再写一套解析。
"""

from __future__ import annotations


class InternalError(Exception):
    """所有可预期错误的基类。子类只声明 status / code / message 三个类属性。"""

    status: int = 400
    code: str = "BAD_REQUEST"
    message: str = "请求不合法"

    def __init__(self, message: str | None = None) -> None:
        if message:
            self.message = message
        super().__init__(self.message)


class UnauthorizedInternal(InternalError):
    status = 401
    code = "INTERNAL_UNAUTHORIZED"
    message = "内部调用密钥缺失或不正确"


class ValidationError(InternalError):
    status = 422
    code = "VALIDATION_ERROR"
    message = "参数校验失败"


class DictionaryNotReady(InternalError):
    status = 503
    code = "DICTIONARY_NOT_READY"
    message = "技能词典未加载，服务尚未就绪"


def error_body(code: str, message: str) -> dict:
    return {"detail": {"code": code, "message": message}}
