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

    # ---- AI 模式统一开关（P-02）----
    AI_MODE                    全局 AI 模式: mock / real（默认 mock）
                               mock: 使用内存 Mock 后端，零外部依赖
                               real: 使用 sklearn 真实后端（需 scikit-learn 就绪）
                               设置 AI_MODE=real 时，backendType 覆盖为 sklearn
                               设置 AI_MODE=mock 时，backendType 覆盖为 mock
                               优先级: AI_MODE > ML_BACKEND_TYPE
"""

from __future__ import annotations

from functools import lru_cache
import os
from typing import Literal

from pydantic import Field, field_validator, model_validator
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
    # P-02: AI_MODE 全局开关优先于 ML_BACKEND_TYPE
    #   AI_MODE=mock  → backendType=mock（内存 Mock，零依赖）
    #   AI_MODE=real  → backendType=sklearn（真实 sklearn 后端）
    #   AI_MODE 未设置 → 使用 ML_BACKEND_TYPE 显式配置
    backendType: Literal["mock", "sklearn", "spark", "mlflow"] = Field(default="sklearn", description="ML 后端类型")

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

    @model_validator(mode="after")
    def _applyAiMode(self) -> "Settings":
        """P-02: AI_MODE 全局开关覆盖 backendType.

        AI_MODE=mock  → backendType=mock
        AI_MODE=real  → backendType=sklearn
        AI_MODE 未设置 → 不覆盖（使用显式 ML_BACKEND_TYPE）
        """
        ai_mode = os.getenv("AI_MODE", "").lower().strip()
        if ai_mode == "mock":
            self.backendType = "mock"
        elif ai_mode == "real":
            self.backendType = "sklearn"
        return self

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
        return self.backendType == "mlflow" or (self.mlflowEnabled and self.backendType != "mock")

    @property
    def isMockFeatureStore(self) -> bool:
        return self.featureStoreType == "mock"

    @property
    def isMockExperimentStore(self) -> bool:
        return self.experimentStoreType == "mock" and not self.mlflowEnabled

    @property
    def isMlflowExperimentStore(self) -> bool:
        return self.experimentStoreType == "mlflow" or self.mlflowEnabled


def _envKwargs() -> dict:
    """把部署侧使用的 `ML_<UPPER_SNAKE>` 环境变量映射成 camelCase 字段初始化参数.

    为什么必须手工映射（本地实测，pydantic-settings 2.5.2 / pydantic 2.13.4）：

    - 字段名是 camelCase（backendType、featureStoreType、logLevel 等），而本模块
      文档与 deploy/manifests、design/deploy/charts 用的是带下划线的
      `ML_BACKEND_TYPE`、`ML_FEATURE_STORE_TYPE`、`ML_EXPERIMENT_STORE_TYPE` 之类；
      pydantic-settings 默认按字段名匹配环境变量，于是这些名字**一个都绑不上**，
      配置静默回落到默认值 sklearn/redis/mlflow——部署写 `ML_BACKEND_TYPE=mock`
      时，进程实际起的是需要 Redis + MLflow 的后端。
    - 想用 `alias_generator` 修反而更糟：字段一旦带显式 alias，连本来能绑的
      `ML_HOST` 也不绑了（实测 alias_generator 版在 ML_HOST=9.9.9.9 下 host 仍是默认值），
      因为显式 alias 不参与 case_insensitive 归一。

    所以这里显式转换后作为初始化参数传入（InitSettingsSource 一定生效），
    未识别的 `ML_*` 变量继续忽略（与原 `extra="ignore"` 口径一致）。

    Returns:
        字段名 → 环境变量值。
    """
    fields = set(Settings.model_fields.keys())
    prefix = Settings.model_config.get("env_prefix") or ""
    kwargs: dict = {}
    for rawKey, value in os.environ.items():
        key = rawKey.upper()
        if not key.startswith(prefix):
            continue
        tail = key[len(prefix) :].lower()
        head, *rest = tail.split("_")
        name = head + "".join(part[:1].upper() + part[1:] for part in rest)
        if name in fields:
            kwargs[name] = value
    return kwargs


@lru_cache(maxsize=1)
def getSettings() -> Settings:
    """获取全局配置单例（带缓存）.

    Returns:
        应用配置（已合并 `ML_*` 环境变量）。
    """
    return Settings(**_envKwargs())


def resetSettings() -> None:
    """重置配置缓存（测试用）."""
    getSettings.cache_clear()
