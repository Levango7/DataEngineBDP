"""应用配置（环境变量驱动，前缀 BP_）.

支持配置项：
    BP_HOST              监听地址（默认 0.0.0.0）
    BP_PORT              监听端口（默认 8088）
    BP_LOG_LEVEL         日志级别（默认 info）
    BP_RELOAD            开发模式热重载（默认 false）
    BP_STORE_TYPE        存储类型: mock / sqlite（默认 sqlite）
    BP_DB_PATH           SQLite 数据库文件路径（默认 data/business_portal.db）
    BP_API_PREFIX        API 路由前缀（默认 /api/v1）
    BP_INTERNAL_FACTOR   内部结算系数（默认 0.3，§11.5 定价模型）
    BP_BUDGET_SOFT_LIMIT 预算软限制开关（默认 true，超限告警不阻断）
"""
from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Business Portal 应用配置."""

    model_config = SettingsConfigDict(
        env_prefix="BP_",
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # ---- server ----
    host: str = Field(default="0.0.0.0", description="监听地址")
    port: int = Field(default=8088, ge=1, le=65535, description="监听端口")
    logLevel: str = Field(default="info", description="日志级别")
    reload: bool = Field(default=False, description="开发模式热重载")

    # ---- store ----
    storeType: Literal["mock", "sqlite"] = Field(
        default="sqlite", description="存储类型: mock / sqlite"
    )
    dbPath: str = Field(
        default="data/business_portal.db",
        description="SQLite 数据库文件路径（storeType=sqlite 时生效）",
    )

    # ---- api ----
    apiPrefix: str = Field(default="/api/v1", description="API 路由前缀")

    # ---- 业务参数 ----
    # 内部结算系数：成本 × 0.3 推财务（§11.5 定价模型）
    internalFactor: float = Field(
        default=0.3, ge=0.0, le=1.0, description="内部结算系数"
    )
    # 预算软限制：true=超限告警不阻断，false=超限阻断
    budgetSoftLimit: bool = Field(
        default=True, description="预算软限制开关"
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
    def isSqlite(self) -> bool:
        """是否 SQLite 模式."""
        return self.storeType == "sqlite"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """获取全局配置单例（带缓存）."""
    return Settings()


def reset_settings() -> None:
    """重置配置缓存（测试用）."""
    get_settings.cache_clear()