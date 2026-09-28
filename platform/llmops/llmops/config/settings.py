"""应用配置（环境变量驱动，前缀 LLMOPS_）.

支持配置项：
    LLMOPS_HOST              监听地址（默认 0.0.0.0）
    LLMOPS_PORT              监听端口（默认 8080）
    LLMOPS_LOG_LEVEL         日志级别（默认 info）
    LLMOPS_RELOAD            开发模式热重载（默认 false）
    LLMOPS_STORE_TYPE        存储类型: mock / mlflow（默认 mlflow）
    LLMOPS_MLFLOW_URI        MLflow Tracking URI
    LLMOPS_MLFLOW_REGISTRY_URI  MLflow Registry URI（默认同 TRACKING_URI）
    LLMOPS_API_PREFIX        API 路由前缀（默认 /api/v1）

    # ---- AI 模式统一开关（P-02）----
    AI_MODE                  全局 AI 模式: mock / real（默认 mock）
                             mock: 使用内存 Mock 仓储，零外部依赖
                             real: 使用 MLflow 真实后端（需 MLflow 服务就绪）
                             设置 AI_MODE=real 时，storeType 自动覆盖为 mlflow
                             设置 AI_MODE=mock 时，storeType 自动覆盖为 mock
                             优先级: AI_MODE > LLMOPS_STORE_TYPE
"""

from __future__ import annotations

from functools import lru_cache
import os
from typing import Literal

from pydantic import Field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """LLMOps 应用配置."""

    model_config = SettingsConfigDict(
        env_prefix="LLMOPS_",
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # ---- server ----
    host: str = Field(default="0.0.0.0", description="监听地址")
    port: int = Field(default=8080, ge=1, le=65535, description="监听端口")
    logLevel: str = Field(default="info", description="日志级别")
    reload: bool = Field(default=False, description="开发模式热重载")

    # ---- store ----
    # P-02: AI_MODE 全局开关优先于 LLMOPS_STORE_TYPE
    #   AI_MODE=mock  → storeType=mock（内存 Mock，零依赖）
    #   AI_MODE=real  → storeType=mlflow（真实 MLflow 后端）
    #   AI_MODE 未设置 → 使用 LLMOPS_STORE_TYPE 显式配置
    storeType: Literal["mock", "mlflow"] = Field(default="mlflow", description="存储类型: mock / mlflow")

    # ---- mlflow ----
    mlflowUri: str = Field(
        default="http://localhost:5000",
        description="MLflow Tracking URI",
    )
    mlflowRegistryUri: str = Field(
        default="",
        description="MLflow Registry URI（空则同 tracking uri）",
    )

    # ---- api ----
    apiPrefix: str = Field(default="/api/v1", description="API 路由前缀")

    @field_validator("logLevel")
    @classmethod
    def _validate_log_level(cls, v: str) -> str:
        allowed = {"debug", "info", "warning", "error", "critical"}
        lv = v.lower()
        if lv not in allowed:
            raise ValueError(f"logLevel 必须为 {allowed} 之一，得到 {v}")
        return lv

    @model_validator(mode="after")
    def _apply_ai_mode(self) -> "Settings":
        """P-02: AI_MODE 全局开关覆盖 storeType.

        AI_MODE=mock  → storeType=mock
        AI_MODE=real  → storeType=mlflow
        AI_MODE 未设置 → 不覆盖（使用显式 LLMOPS_STORE_TYPE）
        """
        ai_mode = os.getenv("AI_MODE", "").lower().strip()
        if ai_mode == "mock":
            self.storeType = "mock"
        elif ai_mode == "real":
            self.storeType = "mlflow"
        return self

    @property
    def effectiveRegistryUri(self) -> str:
        """实际使用的 Registry URI（空则回退到 tracking uri）."""
        return self.mlflowRegistryUri or self.mlflowUri

    @property
    def isMock(self) -> bool:
        """是否 Mock 模式."""
        return self.storeType == "mock"

    @property
    def isMlflow(self) -> bool:
        """是否 MLflow 模式."""
        return self.storeType == "mlflow"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """获取全局配置单例（带缓存）."""
    return Settings()


def reset_settings() -> None:
    """重置配置缓存（测试用）."""
    get_settings.cache_clear()
