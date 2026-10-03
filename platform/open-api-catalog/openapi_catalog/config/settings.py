"""应用配置（环境变量驱动，前缀 OPENAPI_CATALOG_）.

支持配置项（⚠️ env 名 = 前缀 + 字段名原样大写，camelCase 字段不带内部下划线——
pydantic-settings 不做 snake_case 转换；此前本文档列的下划线拼写会被静默忽略，
2026-10-03 实测订正，如 OPENAPI_CATALOG_DBPATH 而非 ..._DB_PATH）：
    OPENAPI_CATALOG_HOST           监听地址（默认 0.0.0.0）
    OPENAPI_CATALOG_PORT           监听端口（默认 8090）
    OPENAPI_CATALOG_LOGLEVEL       日志级别（默认 info）
    OPENAPI_CATALOG_RELOAD         开发模式热重载（默认 false）
    OPENAPI_CATALOG_APIPREFIX      API 路由前缀（默认 /api/v1）
    OPENAPI_CATALOG_APISIXADMINURL APISIX Admin API 地址
    OPENAPI_CATALOG_DEFAULTQUOTA   默认订阅配额（次/分钟）
    OPENAPI_CATALOG_KEYCLOAKURL    Keycloak 服务地址
    OPENAPI_CATALOG_STORETYPE      存储类型: mock / sqlite（默认 sqlite）
    OPENAPI_CATALOG_DBPATH         SQLite 数据库文件路径（默认相对路径
                                   data/openapi_catalog.db；K8s 下必须指向可写挂载，
                                   如 /data/openapi_catalog.db，否则只读根下建库失败）
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
    defaultQuota: int = Field(default=100, ge=1, description="默认订阅配额（次/分钟）")
    defaultRateLimit: int = Field(default=100, ge=1, description="默认限流（次/秒）")

    # ---- store ----
    storeType: Literal["mock", "sqlite"] = Field(default="sqlite", description="存储类型: mock / sqlite")
    dbPath: str = Field(
        default="data/openapi_catalog.db",
        description="SQLite 数据库文件路径（storeType=sqlite 时生效）",
    )

    # ---- finops 出账闭环对接 ----
    finopsBillingUrl: str = Field(
        default="http://localhost:8085/api/finops/v1/billing",
        description="FinOps 账单服务地址（出账闭环：导出 API 调用量到 finops）",
    )
    finopsBillingTimeout: float = Field(
        default=10.0,
        ge=0.1,
        description="FinOps 账单服务请求超时（秒）",
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
