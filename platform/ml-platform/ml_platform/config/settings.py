"""应用配置（环境变量驱动，前缀 ML_）.

支持配置项：
    ML_HOST                    监听地址（默认 0.0.0.0）
    ML_PORT                    监听端口（默认 8080）
    ML_LOG_LEVEL               日志级别（默认 info）
    ML_RELOAD                  开发模式热重载（默认 false）
    ML_BACKEND_TYPE            ML 后端类型: mock / sklearn / spark / mlflow（默认 sklearn）
    ML_FEATURE_STORE_TYPE      特征存储类型: mock / redis（默认 redis）
    ML_EXPERIMENT_STORE_TYPE   实验存储类型: mock / mlflow（默认 mlflow）
    ML_MLFLOW_URI              MLflow Tracking URI
    ML_MLFLOW_REGISTRY_URI     MLflow Registry URI（默认同 TRACKING_URI）
    ML_MLFLOW_ENABLED          MLflow 总开关: true / false（默认 false）
                               true 时 backend 与 experiment_store 优先使用 mlflow
    ML_REDIS_URI               Redis URI（特征存储后端）
    ML_API_PREFIX              API 路由前缀（默认 /api/v1）
"""

from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """ML Platform 应用配置."""

    model_config = SettingsConfigDict(
        env_prefix="ML_",
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

    # ---- backend ----
    backendType: Literal["mock", "sklearn", "spark", "mlflow"] = Field(
        default="sklearn", description="ML 后端类型"
    )

    # ---- feature store ----
    featureStoreType: Literal["mock", "redis"] = Field(default="redis", description="特征存储类型")

    # ---- experiment store ----
    experimentStoreType: Literal["mock", "mlflow"] = Field(default="mlflow", description="实验存储类型")

    # ---- mlflow ----
    mlflowUri: str = Field(
        default="http://localhost:5000",
        description="MLflow Tracking URI",
    )
    mlflowRegistryUri: str = Field(
        default="",
        description="MLflow Registry URI（空则同 tracking uri）",
    )
    mlflowEnabled: bool = Field(
        default=False,
        description="MLflow 总开关：true 时 backend 与 experiment_store 优先使用 mlflow",
    )

    # ---- redis ----
    redisUri: str = Field(
        default="redis://localhost:6379/0",
        description="Redis URI（特征存储后端）",
    )

    # ---- api ----
    apiPrefix: str = Field(default="/api/v1", description="API 路由前缀")

    @field_validator("logLevel")
    @classmethod
    def _validateLogLevel(cls, v: str) -> str:
        allowed = {"debug", "info", "warning", "error", "critical"}
        lv = v.lower()
        if lv not in allowed:
            raise ValueError(f"logLevel 必须为 {allowed} 之一，得到 {v}")
        return lv

    @property
    def effectiveRegistryUri(self) -> str:
        """实际使用的 Registry URI（空则回退到 tracking uri）."""
        return self.mlflowRegistryUri or self.mlflowUri

    @property
    def isMockBackend(self) -> bool:
        return self.backendType == "mock"

    @property
    def isSklearnBackend(self) -> bool:
        return self.backendType == "sklearn"

    @property
    def isSparkBackend(self) -> bool:
        return self.backendType == "spark"

    @property
    def isMlflowBackend(self) -> bool:
        return self.backendType == "mlflow" or (
            self.mlflowEnabled and self.backendType != "mock"
        )

    @property
    def isMockFeatureStore(self) -> bool:
        return self.featureStoreType == "mock"

    @property
    def isMockExperimentStore(self) -> bool:
        return self.experimentStoreType == "mock" and not self.mlflowEnabled

    @property
    def isMlflowExperimentStore(self) -> bool:
        return self.experimentStoreType == "mlflow" or self.mlflowEnabled


@lru_cache(maxsize=1)
def getSettings() -> Settings:
    """获取全局配置单例（带缓存）."""
    return Settings()


def resetSettings() -> None:
    """重置配置缓存（测试用）."""
    getSettings.cache_clear()
