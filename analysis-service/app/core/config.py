"""分析服务配置。

与 v1 的差别：不再有 JWT、数据库连接与 CORS——本服务**不持有用户会话、不碰业务表**，
只接收 Java 传来的结构化入参，返回解析与计算结果。配置项因此只剩三件：
监听端口、内部调用密钥、技能词典文件位置。
"""

from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# analysis-service/app/core/config.py → 上溯三级即项目根
PROJECT_ROOT = Path(__file__).resolve().parents[3]


class Settings(BaseSettings):
    """优先级：环境变量 > analysis-service/.env > 本类默认值。

    口令类字段的默认值一律是开发占位符，真实值只允许出现在 `.env`（已在 .gitignore）。
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    app_name: str = "职航 CareerPath 分析服务"
    api_prefix: str = "/internal"
    debug: bool = True

    # Java → Python 的共享密钥，放在请求头 X-Internal-Token。
    # 本服务只监听 127.0.0.1，不对外暴露；密钥是第二道闸，不是唯一防线。
    internal_token: str = "careerpath-local-key"

    # 技能词典种子。Java 侧用同一个文件灌库，两进程读到的是同一份数据。
    skills_file: str = "data/skills.json"

    # 单次解析允许的最大文本长度，防止超长文本把进程打满
    max_text_length: int = 200_000

    @property
    def skills_path(self) -> Path:
        path = Path(self.skills_file)
        return path if path.is_absolute() else PROJECT_ROOT / path


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
