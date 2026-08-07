"""配置加载模块。

通过环境变量加载配置，支持 Docker 部署与本地开发。

环境变量：
- EVAL_PORT：服务端口（默认 8086）
- LLM_GATEWAY_URL：T030 LLM 网关地址（默认 http://localhost:18085）
- LLM_GATEWAY_API_KEY：LLM 网关 API Key（Mock 模式可用 dummy）
- DATASET_CACHE_DIR：数据集缓存目录（默认 ./.cache/datasets）
- EVAL_MAX_CONCURRENCY：最大并发评测数（默认 4）
- EVAL_REQUEST_TIMEOUT：单次 LLM 请求超时秒数（默认 30）
- EVAL_JWT_SECRET：JWT 签名密钥（与 LLM 网关一致）
- EVAL_DEV_MODE：开发模式，跳过 JWT 校验（默认 false）
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field


def _env_bool(key: str, default: bool = False) -> bool:
    """从环境变量读取布尔值。"""
    val = os.environ.get(key, "").lower()
    if val in ("true", "1", "yes", "on"):
        return True
    if val in ("false", "0", "no", "off", ""):
        return default
    return default


@dataclass
class Settings:
    """评测平台配置（不可变，启动时加载一次）。"""

    # 服务配置
    service_name: str = "evaluation"
    version: str = "0.1.0"
    port: int = 8086

    # T030 LLM 网关配置
    llm_gateway_url: str = "http://localhost:18085"
    llm_gateway_api_key: str = "dummy"
    llm_gateway_timeout: int = 30

    # 数据集缓存
    dataset_cache_dir: str = "./.cache/datasets"

    # 评测执行
    max_concurrency: int = 4
    request_timeout: int = 30

    # 认证
    jwt_secret: str = "dev-secret-key-change-in-production-at-least-256-bits"
    jwt_issuer: str = "shuqing-bigdata"
    dev_mode: bool = False

    # Token 成本单价（每 1K Token，元）—— 用于 cost 指标折算
    # 默认 0.01 元/1K Token，仅用于评测对比，非真实计费
    token_price_per_1k: float = 0.01

    @classmethod
    def from_env(cls) -> "Settings":
        """从环境变量加载配置。"""
        return cls(
            port=int(os.environ.get("EVAL_PORT", "8086")),
            llm_gateway_url=os.environ.get(
                "LLM_GATEWAY_URL", "http://localhost:18085"
            ),
            llm_gateway_api_key=os.environ.get("LLM_GATEWAY_API_KEY", "dummy"),
            llm_gateway_timeout=int(os.environ.get("LLM_GATEWAY_TIMEOUT", "30")),
            dataset_cache_dir=os.environ.get(
                "DATASET_CACHE_DIR", "./.cache/datasets"
            ),
            max_concurrency=int(os.environ.get("EVAL_MAX_CONCURRENCY", "4")),
            request_timeout=int(os.environ.get("EVAL_REQUEST_TIMEOUT", "30")),
            jwt_secret=os.environ.get(
                "JWT_SECRET",
                "dev-secret-key-change-in-production-at-least-256-bits",
            ),
            jwt_issuer=os.environ.get("JWT_ISSUER", "shuqing-bigdata"),
            dev_mode=_env_bool("EVAL_DEV_MODE", False),
            token_price_per_1k=float(os.environ.get("TOKEN_PRICE_PER_1K", "0.01")),
        )


# 全局单例（启动时加载）
_settings: Settings | None = None


def get_settings() -> Settings:
    """获取全局配置单例。"""
    global _settings
    if _settings is None:
        _settings = Settings.from_env()
    return _settings


def reset_settings() -> None:
    """重置全局配置（测试用）。"""
    global _settings
    _settings = None