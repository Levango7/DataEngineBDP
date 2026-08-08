"""应用配置（环境变量驱动，前缀 KE_）.

支持配置项：
    KE_HOST                  监听地址（默认 0.0.0.0）
    KE_PORT                  监听端口（默认 8080）
    KE_LOG_LEVEL             日志级别（默认 info）
    KE_RELOAD                开发模式热重载（默认 false）
    KE_STORE_TYPE            图存储类型: mock / nebula（默认 mock）
    KE_EXTRACTOR_TYPE        抽取器类型: mock / llm（默认 mock）
    KE_NEBULA_HOST           NebulaGraph GraphD 主机
    KE_NEBULA_PORT           NebulaGraph GraphD 端口
    KE_NEBULA_USER           NebulaGraph 用户名
    KE_NEBULA_PASSWORD       NebulaGraph 密码
    KE_NEBULA_POOL_SIZE      NebulaGraph 连接池大小
    KE_LLM_GATEWAY_URL       LLM 网关地址
    KE_LLM_MODEL             LLM 模型名
    KE_LLM_API_KEY           LLM 网关 API Key
    KE_API_PREFIX            API 路由前缀（默认 /api/v1）
"""

from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """知识工程引擎应用配置."""

    model_config = SettingsConfigDict(
        env_prefix="KE_",
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
    storeType: Literal["mock", "nebula"] = Field(default="mock", description="图存储类型: mock / nebula")

    # ---- extractor ----
    extractorType: Literal["mock", "llm"] = Field(default="mock", description="抽取器类型: mock / llm")

    # ---- nebula ----
    nebulaHost: str = Field(default="127.0.0.1", description="NebulaGraph GraphD 主机")
    nebulaPort: int = Field(default=9669, ge=1, le=65535, description="NebulaGraph GraphD 端口")
    nebulaUser: str = Field(default="root", description="NebulaGraph 用户名")
    nebulaPassword: str = Field(default="nebula", description="NebulaGraph 密码")
    nebulaPoolSize: int = Field(default=10, ge=1, description="NebulaGraph 连接池大小")

    # ---- llm ----
    llmGatewayUrl: str = Field(
        default="http://localhost:8080",
        description="LLM 网关地址",
    )
    llmModel: str = Field(default="qwen2.5-7b-instruct", description="LLM 模型名")
    llmApiKey: str = Field(default="", description="LLM 网关 API Key")
    llmTimeout: float = Field(default=30.0, ge=1.0, description="LLM 请求超时(秒)")

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

    @property
    def isMockStore(self) -> bool:
        """是否 Mock 图存储."""
        return self.storeType == "mock"

    @property
    def isNebulaStore(self) -> bool:
        """是否 NebulaGraph 图存储."""
        return self.storeType == "nebula"

    @property
    def isMockExtractor(self) -> bool:
        """是否 Mock 抽取器."""
        return self.extractorType == "mock"

    @property
    def isLlmExtractor(self) -> bool:
        """是否 LLM 抽取器."""
        return self.extractorType == "llm"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """获取全局配置单例（带缓存）."""
    return Settings()


def reset_settings() -> None:
    """重置配置缓存（测试用）."""
    get_settings.cache_clear()
