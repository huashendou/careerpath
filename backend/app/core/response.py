"""已废弃。

本文件原本放「统一响应包 {code, message, data}」，与看板 c2.0 冻结的契约冲突：
成功响应直接返回资源对象，错误体为 `{"detail": {"code", "message"}}`。

现在的实现：
- 业务异常 → `app/core/errors.py`
- 成功响应 → 路由直接返回 Pydantic 模型，不包封套

保留本文件只为一处引用旧路径时不至于 ImportError，请勿新增引用。
"""

from app.core.errors import error_body  # noqa: F401
