"""NL2SQL 引擎应用配置（环境变量驱动，前缀 NL2SQL_）.

配置项一览：
    NL2SQL_HOST                 监听地址（默认 0.0.0.0）
    NL2SQL_PORT                 监听端口（默认 8093）
    NL2SQL_LOG_LEVEL            日志级别（默认 info）
    NL2SQL_RELOAD               开发模式热重载（默认 false）
    NL2SQL_API_PREFIX           API 路由前缀（默认 /api/v1）

    # ---- Catalog 元数据对接 ----
    NL2SQL_CATALOG_URL          Catalog 服务地址（默认 http://localhost:8082）
    NL2SQL_CATALOG_TIMEOUT      Catalog 请求超时秒（默认 10.0）

    # ---- SQL 网关对接 ----
    NL2SQL_SQL_GATEWAY_URL      SQL 网关地址（默认 http://localhost:8081）
    NL2SQL_SQL_GATEWAY_TIMEOUT  SQL 网关请求超时秒（默认 30.0）
    NL2SQL_DEFAULT_ENGINE       默认查询引擎 trino / doris（默认 trino）
    NL2SQL_DEFAULT_LIMIT        默认结果行数上限（默认 100）

    # ---- LLM 对接（经 llm-gateway :8084，OpenAI 兼容协议）----
    NL2SQL_LLM_MODE             LLM 模式: mock / langchain（默认 mock，无外部依赖）
    NL2SQL_LLM_GATEWAY_URL      LLM 网关地址（默认 http://localhost:8084）
    NL2SQL_LLM_MODEL            模型名（默认 qwen2.5-7b-instruct）
    NL2SQL_LLM_API_KEY          LLM 网关 API Key
    NL2SQL_LLM_TEMPERATURE      采样温度（默认 0.0，SQL 生成需确定性）
    NL2SQL_LLM_TIMEOUT          LLM 请求超时秒（默认 30.0）
    NL2SQL_LLM_MAX_TOKENS       最大生成 token 数（默认 1024）

    # ---- 业务约束 ----
    NL2SQL_SELECT_ONLY          是否仅允许 SELECT 语句（默认 true，安全护栏）
    NL2SQL_MAX_TABLES           单次上下文最大表数（默认 20）
    NL2SQL_MAX_DIALOGUE_TURNS   多轮对话最大轮次（默认 5）
    NL2SQL_TENANT_ID            默认租户 ID（默认 default）
"""
from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """NL2SQL 引擎应用配置."""

    model_config = SettingsConfigDict(
        env_prefix="NL2SQL_",
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # ---- server ----
    host: str = Field(default="0.0.0.0", description="监听地址")
    port: int = Field(default=8093, ge=1, le=65535, description="监听端口")
    logLevel: str = Field(default="info", description="日志级别")
    reload: bool = Field(default=False, description="开发模式热重载")

    # ---- api ----
    apiPrefix: str = Field(default="/api/v1", description="API 路由前缀")

    # ---- catalog ----
    catalogUrl: str = Field(
        default="http://localhost:8082",
        description="Catalog 元数据服务地址",
    )
    catalogTimeout: float = Field(default=10.0, ge=0.1, description="Catalog 请求超时(秒)")

    # ---- sql gateway ----
    sqlGatewayUrl: str = Field(
        default="http://localhost:8081",
        description="SQL 网关地址",
    )
    sqlGatewayTimeout: float = Field(default=30.0, ge=0.1, description="SQL 网关请求超时(秒)")
    defaultEngine: Literal["trino", "doris"] = Field(
        default="trino", description="默认查询引擎"
    )
    defaultLimit: int = Field(default=100, ge=1, le=10000, description="默认结果行数上限")

    # ---- llm ----
    llmMode: Literal["mock", "langchain"] = Field(
        default="mock",
        description="LLM 模式: mock（无外部依赖）/ langchain（经 llm-gateway）",
    )
    llmGatewayUrl: str = Field(
        default="http://localhost:8084",
        description="LLM 网关地址（OpenAI 兼容）",
    )
    llmModel: str = Field(default="qwen2.5-7b-instruct", description="LLM 模型名")
    llmApiKey: str = Field(default="", description="LLM 网关 API Key")
    llmTemperature: float = Field(default=0.0, ge=0.0, le=2.0, description="采样温度")
    llmTimeout: float = Field(default=30.0, ge=1.0, description="LLM 请求超时(秒)")
    llmMaxTokens: int = Field(default=1024, ge=1, description="最大生成 token 数")

    # ---- 业务约束 ----
    selectOnly: bool = Field(default=True, description="是否仅允许 SELECT 语句")
    maxTables: int = Field(default=20, ge=1, description="单次上下文最大表数")
    maxDialogueTurns: int = Field(default=5, ge=1, le=20, description="多轮对话最大轮次")
    tenantId: str = Field(default="default", description="默认租户 ID")

    @field_validator("logLevel")
    @classmethod
    def _validate_log_level(cls, v: str) -> str:
        allowed = {"debug", "info", "warning", "error", "critical"}
        lv = v.lower()
        if lv not in allowed:
            raise ValueError(f"logLevel 必须为 {allowed} 之一，得到 {v}")
        return lv

    # ---- 便捷属性 ----
    @property
    def isMockLlm(self) -> bool:
        """是否 Mock LLM 模式（无需外部模型服务）."""
        return self.llmMode == "mock"

    @property
    def isLangchainLlm(self) -> bool:
        """是否 LangChain LLM 模式."""
        return self.llmMode == "langchain"

    @property
    def llmEndpoint(self) -> str:
        """OpenAI 兼容 endpoint（llm-gateway /v1 挂载点）."""
        base = self.llmGatewayUrl.rstrip("/")
        if base.endswith("/v1"):
            return base
        return f"{base}/v1"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """获取全局配置单例（带缓存）."""
    return Settings()


def reset_settings() -> None:
    """重置配置缓存（测试用）."""
    get_settings.cache_clear()