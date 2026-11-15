"""全局配置：全部从环境变量 / .env 读取，不在代码里写死任何口令。"""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """项目配置。

    优先级：环境变量 > backend/.env > 本类默认值。
    口令类字段的默认值一律是占位符，真实值只允许出现在 .env 里。
    """

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    app_name: str = "职能雷达 API"
    api_prefix: str = "/api"
    debug: bool = True

    # 形如 mysql+pymysql://user:pass@127.0.0.1:3306/smart_radar?charset=utf8mb4
    # 测试时用 sqlite+pysqlite:///:memory: 覆盖
    database_url: str = (
        "mysql+pymysql://radar_app:change-me@127.0.0.1:3306/smart_radar?charset=utf8mb4"
    )
    db_echo: bool = False
    # 启动时自动建表。开发期方便；正式环境应改由迁移脚本负责。
    auto_create_tables: bool = True

    # JWT。生产环境必须换成随机长串，且不得提交进仓库。
    jwt_secret: str = "dev-only-do-not-use-in-production"
    jwt_algorithm: str = "HS256"
    jwt_expire_minutes: int = 60 * 24

    # 前端来源，逗号分隔
    cors_origins: str = "http://127.0.0.1:5173,http://localhost:5173"

    # 种子数据目录（相对 backend/）
    data_dir: str = "../data"

    @property
    def cors_origin_list(self) -> list[str]:
        return [o.strip() for o in self.cors_origins.split(",") if o.strip()]

    @property
    def is_sqlite(self) -> bool:
        return self.database_url.startswith("sqlite")

    @property
    def safe_database_label(self) -> str:
        """日志用：只显示库名与主机，绝不打印口令。"""
        url = self.database_url
        if "@" in url:
            tail = url.split("@", 1)[1]
            scheme = url.split("://", 1)[0]
            return f"{scheme}://***@{tail}"
        return url


@lru_cache
def get_settings() -> Settings:
    return Settings()


settings = get_settings()
