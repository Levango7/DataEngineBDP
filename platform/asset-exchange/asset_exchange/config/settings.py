"""应用配置（环境变量驱动，前缀 ASSET_EXCHANGE_）.

支持配置项：
    ASSET_EXCHANGE_HOST              监听地址（默认 0.0.0.0）
    ASSET_EXCHANGE_PORT              监听端口（默认 8087）
    ASSET_EXCHANGE_LOG_LEVEL         日志级别（默认 info）
    ASSET_EXCHANGE_RELOAD            开发模式热重载（默认 false）
    ASSET_EXCHANGE_STORE_TYPE        存储类型: mock / sqlite（默认 sqlite）
    ASSET_EXCHANGE_DB_PATH           SQLite 数据库文件路径（默认 data/asset_exchange.db）
    ASSET_EXCHANGE_API_PREFIX        API 路由前缀（默认 /api/v1）
    ASSET_EXCHANGE_PROVIDER_SHARE    提供方收益分成（默认 0.8）
    ASSET_EXCHANGE_PLATFORM_SHARE    平台抽成（默认 0.2）
    ASSET_EXCHANGE_INTERNAL_FACTOR   内部结算成本系数（默认 0.3）
    ASSET_EXCHANGE_AUDIT_FACADE_URL  Phase 1 SecurityFacade T021 URL（可选）
    ASSET_EXCHANGE_PLATFORM_ACCOUNT_ID 平台分账账户 ID（默认 platform-main）
    ASSET_EXCHANGE_FINOPS_BILLING_URL        FinOps 账单服务地址
    ASSET_EXCHANGE_FINOPS_BILLING_TIMEOUT    FinOps 账单请求超时（秒）
    ASSET_EXCHANGE_OPEN_API_CATALOG_URL      open-api-catalog 服务地址
    ASSET_EXCHANGE_OPEN_API_CATALOG_TIMEOUT  开放 API 目录请求超时（秒）
"""

from __future__ import annotations

from functools import lru_cache
from typing import Literal, Optional

from pydantic import AliasChoices, Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """AssetExchange 应用配置."""

    model_config = SettingsConfigDict(
        env_prefix="ASSET_EXCHANGE_",
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # ---- server ----
    # 驼峰字段必须显式写含前缀的 validation_alias（同 llmops/knowledge-engine）：pydantic-settings
    # 只把「字段名（或别名）」大写后拼 env_prefix，驼峰字段会落成 ASSET_EXCHANGE_LOGLEVEL 等，
    # 与部署所用的 ASSET_EXCHANGE_LOG_LEVEL / ASSET_EXCHANGE_STORE_TYPE 不匹配 ⇒ 环境变量被静默忽略。
    host: str = Field(default="0.0.0.0", description="监听地址")
    port: int = Field(default=8087, ge=1, le=65535, description="监听端口")
    logLevel: str = Field(
        default="info", validation_alias=AliasChoices("ASSET_EXCHANGE_LOG_LEVEL", "logLevel"), description="日志级别"
    )
    reload: bool = Field(default=False, description="开发模式热重载")

    # ---- store ----
    storeType: Literal["mock", "sqlite"] = Field(
        default="sqlite",
        validation_alias=AliasChoices("ASSET_EXCHANGE_STORE_TYPE", "storeType"),
        description="存储类型: mock / sqlite",
    )
    dbPath: str = Field(
        default="data/asset_exchange.db",
        validation_alias=AliasChoices("ASSET_EXCHANGE_DB_PATH", "dbPath"),
        description="SQLite 数据库文件路径（storeType=sqlite 时生效）",
    )

    # ---- api ----
    apiPrefix: str = Field(
        default="/api/v1",
        validation_alias=AliasChoices("ASSET_EXCHANGE_API_PREFIX", "apiPrefix"),
        description="API 路由前缀",
    )

    # ---- billing ----
    providerShare: float = Field(
        default=0.8,
        ge=0,
        le=1,
        validation_alias=AliasChoices("ASSET_EXCHANGE_PROVIDER_SHARE", "providerShare"),
        description="提供方收益分成（默认 0.8）",
    )
    platformShare: float = Field(
        default=0.2,
        ge=0,
        le=1,
        validation_alias=AliasChoices("ASSET_EXCHANGE_PLATFORM_SHARE", "platformShare"),
        description="平台抽成（默认 0.2）",
    )
    internalFactor: float = Field(
        default=0.3,
        ge=0,
        le=1,
        validation_alias=AliasChoices("ASSET_EXCHANGE_INTERNAL_FACTOR", "internalFactor"),
        description="内部结算成本系数（默认 0.3）",
    )

    # ---- audit ----
    auditFacadeUrl: Optional[str] = Field(
        default=None,
        validation_alias=AliasChoices("ASSET_EXCHANGE_AUDIT_FACADE_URL", "auditFacadeUrl"),
        description="Phase 1 SecurityFacade T021 URL（可选，用于远程审计同步）",
    )
    platformAccountId: str = Field(
        default="platform-main",
        validation_alias=AliasChoices("ASSET_EXCHANGE_PLATFORM_ACCOUNT_ID", "platformAccountId"),
        description="平台分账账户 ID",
    )

    # ---- finops 出账闭环对接 ----
    finopsBillingUrl: str = Field(
        default="http://localhost:8085/api/finops/v1/billing",
        validation_alias=AliasChoices("ASSET_EXCHANGE_FINOPS_BILLING_URL", "finopsBillingUrl"),
        description="FinOps 账单服务地址（出账闭环：asset-exchange 调用获取账单）",
    )
    finopsBillingTimeout: float = Field(
        default=10.0,
        ge=0.1,
        validation_alias=AliasChoices("ASSET_EXCHANGE_FINOPS_BILLING_TIMEOUT", "finopsBillingTimeout"),
        description="FinOps 账单服务请求超时（秒）",
    )

    # ---- 开放 API 目录对接（API 交付的真实凭证来源） ----
    openApiCatalogUrl: str = Field(
        default="http://localhost:8090/api/v1",
        validation_alias=AliasChoices("ASSET_EXCHANGE_OPEN_API_CATALOG_URL", "openApiCatalogUrl"),
        description="open-api-catalog 服务地址（含 /api/v1 前缀）。"
        "API 交付的 Access Key 由它发放并写入 APISIX key-auth",
    )
    openApiCatalogTimeout: float = Field(
        default=10.0,
        ge=0.1,
        validation_alias=AliasChoices("ASSET_EXCHANGE_OPEN_API_CATALOG_TIMEOUT", "openApiCatalogTimeout"),
        description="开放 API 目录请求超时（秒）",
    )

    @field_validator("logLevel")
    @classmethod
    def _validate_log_level(cls, v: str) -> str:
        allowed = {"debug", "info", "warning", "error", "critical"}
        lv = v.lower()
        if lv not in allowed:
            raise ValueError(f"logLevel 必须为 {allowed} 之一，得到 {v}")
        return lv

    @field_validator("platformShare")
    @classmethod
    def _validate_shares_sum(cls, v: float, info) -> float:
        provider = info.data.get("providerShare", 0.8)
        if abs(provider + v - 1.0) > 1e-6:
            raise ValueError(f"providerShare({provider}) + platformShare({v}) 必须等于 1.0")
        return v

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
