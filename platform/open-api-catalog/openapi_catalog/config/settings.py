"""应用配置（环境变量驱动，前缀 OPENAPI_CATALOG_）.

支持配置项：
    OPENAPI_CATALOG_HOST          监听地址（默认 0.0.0.0）
    OPENAPI_CATALOG_PORT          监听端口（默认 8090）
    OPENAPI_CATALOG_LOG_LEVEL     日志级别（默认 info）
    OPENAPI_CATALOG_RELOAD        开发模式热重载（默认 false）
    OPENAPI_CATALOG_API_PREFIX    API 路由前缀（默认 /api/v1）
    OPENAPI_CATALOG_APISIX_ADMIN  APISIX Admin API 地址
    OPENAPI_CATALOG_DEFAULT_QUOTA 默认订阅配额（次/分钟）
    OPENAPI_CATALOG_KEYCLOAK_URL  Keycloak 服务地址
    OPENAPI_CATALOG_STORE_TYPE    存储类型: mock / sqlite（默认 sqlite）
    OPENAPI_CATALOG_DB_PATH       SQLite 数据库文件路径
"""
from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """开放 API 服务目录应用配置."""

    model_config = SettingsConfigDict(
        env_prefix="OPENAPI_CATALOG_",
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # ---- server ----
    host: str = Field(default="0.0.0.0", description="监听地址")
    port: int = Field(default=8090, ge=1, le=65535, description="监听端口")
    logLevel: str = Field(default="info", description="日志级别")
    reload: bool = Field(default=False, description="开发模式热重载")

    # ---- api ----
    apiPrefix: str = Field(default="/api/v1", description="API 路由前缀")

    # ---- apisix ----
    apisixAdminUrl: str = Field(
        default="http://apisix-admin:9180/apisix/admin",
        description="APISIX Admin API 地址",
    )
    apisixAdminKey: str = Field(
        default="edd1c9f034335f136f87ad84b625c8ab",
        description="APISIX Admin Key",
    )

    # ---- keycloak ----
    keycloakUrl: str = Field(
        default="http://keycloak:8080/realms/shuqing",
        description="Keycloak Realm 地址",
    )

    # ---- default policy ----
    defaultQuota: int = Field(
        default=100, ge=1, description="默认订阅配额（次/分钟）"
    )
    defaultRateLimit: int = Field(
        default=100, ge=1, description="默认限流（次/秒）"
    )

    # ---- store ----
    storeType: Literal["mock", "sqlite"] = Field(
        default="sqlite", description="存储类型: mock / sqlite"
    )
    dbPath: str = Field(
        default="data/openapi_catalog.db",
        description="SQLite 数据库文件路径（storeType=sqlite 时生效）",
    )

    @field_validator("logLevel")
    @classmethod
    def _validate_log_level(cls, v: str) -> str:
        allowed = {"debug", "info", "warning", "error", "critical"}
        lv = v.lower()
        if lv not in allowed:
            raise ValueError(f"logLevel 必须为 {allowed} 之一，得到 {v}")
        return lv

    @property
    def isMock(self) -> bool:
        """是否 Mock 模式."""
        return self.storeType == "mock"

    @property
    def isSQLite(self) -> bool:
        """是否 SQLite 模式."""
        return self.storeType == "sqlite"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """获取全局配置单例（带缓存）."""
    return Settings()


def reset_settings() -> None:
    """重置配置缓存（测试用）."""
    get_settings.cache_clear()
