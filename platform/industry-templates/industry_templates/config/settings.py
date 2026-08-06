"""应用配置（环境变量驱动，前缀 INDUSTRY_TEMPLATES_）.

支持配置项：
    INDUSTRY_TEMPLATES_HOST          监听地址（默认 0.0.0.0）
    INDUSTRY_TEMPLATES_PORT          监听端口（默认 8091）
    INDUSTRY_TEMPLATES_LOG_LEVEL     日志级别（默认 info）
    INDUSTRY_TEMPLATES_RELOAD        开发模式热重载（默认 false）
    INDUSTRY_TEMPLATES_API_PREFIX    API 路由前缀（默认 /api/v1）
    INDUSTRY_TEMPLATES_DEPLOY_MODE   部署模式: mock / helm（默认 mock）
    INDUSTRY_TEMPLATES_HELM_BIN      helm 二进制路径（默认 helm）
    INDUSTRY_TEMPLATES_HELM_KUBECONFIG KUBECONFIG 路径（可选）
    INDUSTRY_TEMPLATES_HELM_TIMEOUT  helm 命令超时秒数（默认 600）
    INDUSTRY_TEMPLATES_CHART_BASE    Chart 查找基础路径（默认 ./charts）
"""
from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """行业应用模板平台配置."""

    model_config = SettingsConfigDict(
        env_prefix="INDUSTRY_TEMPLATES_",
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # ---- server ----
    host: str = Field(default="0.0.0.0", description="监听地址")
    port: int = Field(default=8091, ge=1, le=65535, description="监听端口")
    logLevel: str = Field(default="info", description="日志级别")
    reload: bool = Field(default=False, description="开发模式热重载")

    # ---- api ----
    apiPrefix: str = Field(default="/api/v1", description="API 路由前缀")

    # ---- deploy ----
    deployMode: Literal["mock", "helm"] = Field(
        default="mock", description="部署模式: mock / helm"
    )
    # ---- helm ----
    helmBin: str = Field(default="helm", description="helm 二进制路径")
    helmKubeconfig: str = Field(
        default="", description="KUBECONFIG 路径（空表示使用默认）"
    )
    helmTimeout: int = Field(
        default=600, ge=1, description="helm 命令超时秒数"
    )
    chartBase: str = Field(
        default="./charts", description="Chart 查找基础路径"
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
        """是否 Mock 模式（不真正调用 Helm）."""
        return self.deployMode == "mock"

    @property
    def isHelm(self) -> bool:
        """是否 Helm 模式（真实执行 helm install）."""
        return self.deployMode == "helm"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """获取全局配置单例（带缓存）."""
    return Settings()


def reset_settings() -> None:
    """重置配置缓存（测试用）."""
    get_settings.cache_clear()