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
"""
from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic import Field, field_validator
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
    host: str = Field(default="0.0.0.0", description="监听地址")
    port: int = Field(default=8087, ge=1, le=65535, description="监听端口")
    logLevel: str = Field(default="info", description="日志级别")
    reload: bool = Field(default=False, description="开发模式热重载")

    # ---- store ----
    storeType: Literal["mock", "sqlite"] = Field(
        default="sqlite", description="存储类型: mock / sqlite"
    )
    dbPath: str = Field(
        default="data/asset_exchange.db",
        description="SQLite 数据库文件路径（storeType=sqlite 时生效）",
    )

    # ---- api ----
    apiPrefix: str = Field(default="/api/v1", description="API 路由前缀")

    # ---- billing ----
    providerShare: float = Field(
        default=0.8, ge=0, le=1, description="提供方收益分成（默认 0.8）"
    )
    platformShare: float = Field(
        default=0.2, ge=0, le=1, description="平台抽成（默认 0.2）"
    )
    internalFactor: float = Field(
        default=0.3, ge=0, le=1, description="内部结算成本系数（默认 0.3）"
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
            raise ValueError(
                f"providerShare({provider}) + platformShare({v}) 必须等于 1.0"
            )
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