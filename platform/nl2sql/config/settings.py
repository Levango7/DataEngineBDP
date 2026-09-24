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

    # ---- LLM 对接 ----
    # 三种模式：
    #   mock      规则化生成，无外部依赖（演示/单测）
    #   langchain 经 langchain-openai ChatOpenAI 调 OpenAI 兼容 API（需安装 langchain）
    #   http      httpx 直连 OpenAI 兼容 /v1/chat/completions（无 langchain 依赖，推荐）
    # P-02: AI_MODE 全局开关优先于 NL2SQL_LLM_MODE
    #   AI_MODE=mock  → llmMode=mock（无外部依赖）
    #   AI_MODE=real  → llmMode=langchain（经 llm-gateway 真实 LLM）
    #   AI_MODE 未设置 → 使用 NL2SQL_LLM_MODE 显式配置
    # 通用 LLM 环境变量（不带 NL2SQL_ 前缀，OpenAI/华为云等兼容 API 通用约定）：
    #   LLM_API_BASE_URL → llmGatewayUrl（如 https://api.openai.com/v1）
    #   LLM_API_KEY      → llmApiKey
    #   LLM_MODEL        → llmModel（如 gpt-4 / qwen-72b）
    # NL2SQL_LLM_MODE 与 AI_MODE 均未显式设置，但配置了 LLM_API_BASE_URL/LLM_API_KEY
    # 之一时，自动切换 http 模式（用户意图 = 接真实 LLM API）。
    NL2SQL_LLM_MODE             LLM 模式: mock / langchain / http（未配置 LLM_API_* 时默认 langchain）
    NL2SQL_LLM_GATEWAY_URL      LLM 网关地址（OpenAI 兼容，默认 http://localhost:8084）
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
    NL2SQL_MAX_SESSIONS         内存会话容量上限 LRU 驱逐（默认 500）
    NL2SQL_SESSION_TTL_SECONDS  会话空闲过期 TTL 秒（默认 1800）
"""

from __future__ import annotations

from functools import lru_cache
import os
from typing import Literal

from pydantic import Field, field_validator, model_validator
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
    defaultEngine: Literal["trino", "doris"] = Field(default="trino", description="默认查询引擎")
    defaultLimit: int = Field(default=100, ge=1, le=10000, description="默认结果行数上限")

    # ---- llm ----
    llmMode: Literal["mock", "langchain", "http"] = Field(
        default="langchain",
        description="LLM 模式: mock（无外部依赖）/ langchain（LangChain SDK）/ http（httpx 直连 OpenAI 兼容 API）",
    )
    llmGatewayUrl: str = Field(
        default="http://localhost:8084",
        description="LLM 网关地址（OpenAI 兼容 /v1/chat/completions）",
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
    maxSessions: int = Field(default=500, ge=1, description="内存会话容量上限（LRU 驱逐）")
    sessionTtlSeconds: float = Field(default=1800.0, ge=1.0, description="会话空闲过期 TTL（秒）")

    @field_validator("logLevel")
    @classmethod
    def _validate_log_level(cls, v: str) -> str:
        allowed = {"debug", "info", "warning", "error", "critical"}
        lv = v.lower()
        if lv not in allowed:
            raise ValueError(f"logLevel 必须为 {allowed} 之一，得到 {v}")
        return lv

    @model_validator(mode="after")
    def _apply_llm_env(self) -> "Settings":
        """P-02: AI_MODE 全局开关 + 通用 LLM 环境变量适配.

        优先级：
        1. AI_MODE 全局开关：
           AI_MODE=mock  → llmMode=mock
           AI_MODE=real  → llmMode=langchain
        2. 通用环境变量（OpenAI/华为云等 API 通用约定，无 NL2SQL_ 前缀）：
           LLM_API_BASE_URL → llmGatewayUrl
           LLM_API_KEY      → llmApiKey
           LLM_MODEL        → llmModel
        3. 自动模式推断：NL2SQL_LLM_MODE 与 AI_MODE 均未设置，但配置了
           LLM_API_BASE_URL 或 LLM_API_KEY 之一 → llmMode=http
           （用户意图明确 = 直连真实 LLM API，且 httpx 无 langchain 依赖）
        """
        ai_mode = os.getenv("AI_MODE", "").lower().strip()
        if ai_mode == "mock":
            self.llmMode = "mock"
        elif ai_mode == "real" and "NL2SQL_LLM_MODE" not in os.environ:
            self.llmMode = "langchain"

        # 通用 LLM 环境变量（显式 NL2SQL_ 前缀配置优先，不覆盖）
        llm_api_base = os.getenv("LLM_API_BASE_URL", "").strip()
        llm_api_key = os.getenv("LLM_API_KEY", "").strip()
        llm_model = os.getenv("LLM_MODEL", "").strip()
        if llm_api_base:
            self.llmGatewayUrl = llm_api_base
        if llm_api_key:
            self.llmApiKey = llm_api_key
        if llm_model:
            self.llmModel = llm_model

        # 自动模式推断：仅在用户未显式选择模式时生效
        mode_env = os.getenv("NL2SQL_LLM_MODE", "").strip()
        if not mode_env and not ai_mode and (llm_api_base or llm_api_key):
            self.llmMode = "http"
        return self

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
    def isHttpLlm(self) -> bool:
        """是否 httpx 直连 OpenAI 兼容 API 模式."""
        return self.llmMode == "http"

    @property
    def llmConfigured(self) -> bool:
        """LLM 是否已配置（http 模式要求 API Key；mock 模式恒为 True）.

        langchain 模式历史上允许无 Key 网关（openai_api_key="not-required"），
        故仅 http 模式强制要求 Key。
        """
        if self.isMockLlm:
            return True
        if self.isHttpLlm:
            return bool(self.llmApiKey)
        # langchain：网关可能免鉴权，视为已配置
        return True

    @property
    def llmEndpoint(self) -> str:
        """OpenAI 兼容 endpoint（llm-gateway /v1 挂载点）.

        兼容两种 LLM_API_BASE_URL 写法：
        - https://api.openai.com/v1  → 保持不变
        - https://api.openai.com     → 追加 /v1
        """
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
