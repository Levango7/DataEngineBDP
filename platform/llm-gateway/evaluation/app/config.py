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

from dataclasses import dataclass
import os


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

    # 网关不可达时是否回退 Mock 响应（默认关闭，防止故障期间指标失真）
    enable_mock_fallback: bool = False

    # 认证
    # 安全策略：jwt_secret 默认空字符串，生产环境（dev_mode=False）必须通过
    # JWT_SECRET 环境变量显式配置，缺失则 fail-fast 抛异常。
    jwt_secret: str = ""
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
            llm_gateway_url=os.environ.get("LLM_GATEWAY_URL", "http://localhost:18085"),
            llm_gateway_api_key=os.environ.get("LLM_GATEWAY_API_KEY", "dummy"),
            llm_gateway_timeout=int(os.environ.get("LLM_GATEWAY_TIMEOUT", "30")),
            dataset_cache_dir=os.environ.get("DATASET_CACHE_DIR", "./.cache/datasets"),
            max_concurrency=int(os.environ.get("EVAL_MAX_CONCURRENCY", "4")),
            request_timeout=int(os.environ.get("EVAL_REQUEST_TIMEOUT", "30")),
            enable_mock_fallback=_env_bool("EVAL_MOCK_FALLBACK", False),
            jwt_secret=os.environ.get("JWT_SECRET", ""),
            jwt_issuer=os.environ.get("JWT_ISSUER", "shuqing-bigdata"),
            dev_mode=_env_bool("EVAL_DEV_MODE", False),
            token_price_per_1k=float(os.environ.get("TOKEN_PRICE_PER_1K", "0.01")),
        )

    def validate(self) -> "Settings":
        """校验配置安全性：非 dev_mode 下 jwt_secret 必须显式配置。"""
        if not self.dev_mode and not self.jwt_secret:
            raise RuntimeError("JWT_SECRET environment variable is required when EVAL_DEV_MODE=false")
        if not self.dev_mode and len(self.jwt_secret) < 32:
            raise RuntimeError(f"JWT_SECRET must be at least 32 bytes, got {len(self.jwt_secret)}")
        return self


# 全局单例（启动时加载）
_settings: Settings | None = None


def get_settings() -> Settings:
    """获取全局配置单例。"""
    global _settings
    if _settings is None:
        _settings = Settings.from_env().validate()
    return _settings


def reset_settings() -> None:
    """重置全局配置（测试用）。"""
    global _settings
    _settings = None
