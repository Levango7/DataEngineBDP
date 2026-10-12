"""应用配置（环境变量驱动，前缀 OPENAPI_CATALOG_）.

支持配置项（2026-10-11 订正：驼峰字段已补 validation_alias，**两套拼写都生效**——
下划线规范名（全仓统一口径）与非下划线历史名（其部署配置已在用，保持兼容））：
    OPENAPI_CATALOG_HOST / OPENAPI_CATALOG_HOST         监听地址（默认 0.0.0.0）
    OPENAPI_CATALOG_PORT                                监听端口（默认 8090）
    OPENAPI_CATALOG_LOG_LEVEL（历史名 OPENAPI_CATALOG_LOGLEVEL）      日志级别（默认 info）
    OPENAPI_CATALOG_RELOAD                              开发模式热重载（默认 false）
    OPENAPI_CATALOG_API_PREFIX（历史名 ..._APIPREFIX）  API 路由前缀（默认 /api/v1）
    OPENAPI_CATALOG_APISIX_ADMIN_URL（历史名 ..._APISIXADMINURL）      APISIX Admin API 地址
    OPENAPI_CATALOG_DEFAULT_QUOTA（历史名 ..._DEFAULTQUOTA）           默认订阅配额（次/分钟）
    OPENAPI_CATALOG_KEYCLOAK_URL（历史名 ..._KEYCLOAKURL）             Keycloak 服务地址
    OPENAPI_CATALOG_STORE_TYPE（历史名 ..._STORETYPE）  存储类型: mock / sqlite（默认 sqlite）
    OPENAPI_CATALOG_DB_PATH（历史名 ..._DBPATH）        SQLite 数据库文件路径（默认相对路径
                                   data/openapi_catalog.db；K8s 下必须指向可写挂载，
                                   如 /data/openapi_catalog.db，否则只读根下建库失败）
"""

from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic import AliasChoices, Field, field_validator
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
    # 驼峰字段必须显式写含前缀的 validation_alias：pydantic-settings 只把「字段名（或别名）」
    # 大写后拼 env_prefix，驼峰字段会落成 OPENAPI_CATALOG_LOGLEVEL 而非部署所用的
    # ..._LOG_LEVEL（2.5.2 与 2.15.0 双版本实测）。本组件历史上把非下划线名当契约写入配置
    # （deploy/local/values-local-core.yaml 等），故用 AliasChoices 同时接受两套拼写，
    # 规范的下划线名优先、历史名兜底（既有部署不受影响）。
    host: str = Field(default="0.0.0.0", description="监听地址")
    port: int = Field(default=8090, ge=1, le=65535, description="监听端口")
    logLevel: str = Field(
        default="info",
        validation_alias=AliasChoices("OPENAPI_CATALOG_LOG_LEVEL", "OPENAPI_CATALOG_LOGLEVEL"),
        description="日志级别",
    )
    reload: bool = Field(default=False, description="开发模式热重载")

    # ---- api ----
    apiPrefix: str = Field(
        default="/api/v1",
        validation_alias=AliasChoices("OPENAPI_CATALOG_API_PREFIX", "OPENAPI_CATALOG_APIPREFIX"),
        description="API 路由前缀",
    )

    # ---- apisix ----
    apisixAdminUrl: str = Field(
        default="http://apisix-admin:9180/apisix/admin",
        validation_alias=AliasChoices("OPENAPI_CATALOG_APISIX_ADMIN_URL", "OPENAPI_CATALOG_APISIXADMINURL"),
        description="APISIX Admin API 地址",
    )
    apisixAdminKey: str = Field(
        default="edd1c9f034335f136f87ad84b625c8ab",
        validation_alias=AliasChoices("OPENAPI_CATALOG_APISIX_ADMIN_KEY", "OPENAPI_CATALOG_APISIXADMINKEY"),
        description="APISIX Admin Key",
    )

    # ---- keycloak ----
    keycloakUrl: str = Field(
        default="http://keycloak:8080/realms/shuqing",
        validation_alias=AliasChoices("OPENAPI_CATALOG_KEYCLOAK_URL", "OPENAPI_CATALOG_KEYCLOAKURL"),
        description="Keycloak Realm 地址",
    )

    # ---- default policy ----
    defaultQuota: int = Field(
        default=100,
        ge=1,
        validation_alias=AliasChoices("OPENAPI_CATALOG_DEFAULT_QUOTA", "OPENAPI_CATALOG_DEFAULTQUOTA"),
        description="默认订阅配额（次/分钟）",
    )
    defaultRateLimit: int = Field(
        default=100,
        ge=1,
        validation_alias=AliasChoices("OPENAPI_CATALOG_DEFAULT_RATE_LIMIT", "OPENAPI_CATALOG_DEFAULTRATELIMIT"),
        description="默认限流（次/秒）",
    )

    # ---- store ----
    storeType: Literal["mock", "sqlite"] = Field(
        default="sqlite",
        validation_alias=AliasChoices("OPENAPI_CATALOG_STORE_TYPE", "OPENAPI_CATALOG_STORETYPE"),
        description="存储类型: mock / sqlite",
    )
    dbPath: str = Field(
        default="data/openapi_catalog.db",
        validation_alias=AliasChoices("OPENAPI_CATALOG_DB_PATH", "OPENAPI_CATALOG_DBPATH"),
        description="SQLite 数据库文件路径（storeType=sqlite 时生效）",
    )

    # ---- finops 出账闭环对接 ----
    finopsBillingUrl: str = Field(
        default="http://localhost:8085/api/finops/v1/billing",
        validation_alias=AliasChoices("OPENAPI_CATALOG_FINOPS_BILLING_URL", "OPENAPI_CATALOG_FINOPSBILLINGURL"),
        description="FinOps 账单服务地址（出账闭环：导出 API 调用量到 finops）",
    )
    finopsBillingTimeout: float = Field(
        default=10.0,
        ge=0.1,
        validation_alias=AliasChoices("OPENAPI_CATALOG_FINOPS_BILLING_TIMEOUT", "OPENAPI_CATALOG_FINOPSBILLINGTIMEOUT"),
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
