"""闭环编排服务配置.

通过环境变量加载配置，支持 Docker 部署与本地开发。

环境变量：
- LOOP_PORT：服务端口（默认 18088）
- FINETUNE_URL：T032 微调引擎地址（默认 http://localhost:8095）
- EVALUATION_URL：T031 评测平台地址（默认 http://localhost:18086）
- REGISTRY_URL：模型仓库服务地址（默认 http://localhost:18089）
- LOOP_WORK_DIR：工作目录（默认 /tmp/finetune-loop）
- LOOP_MOCK_MODE：Mock 模式（默认 true，不实际调用外部服务）
- LOOP_JWT_SECRET：JWT 签名密钥
- LOOP_DEV_MODE：开发模式，跳过 JWT 校验（默认 true）
"""

from __future__ import annotations

import os
from dataclasses import dataclass


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
    """闭环编排服务配置（不可变，启动时加载一次）。"""

    # 服务配置
    service_name: str = "finetuning-loop"
    version: str = "0.1.0"
    port: int = 18088

    # 上游服务地址
    finetune_url: str = "http://localhost:8095"
    evaluation_url: str = "http://localhost:18086"
    registry_url: str = "http://localhost:18089"

    # 工作目录（存放闭环任务状态、产物元数据）
    work_dir: str = "/tmp/finetune-loop"

    # Mock 模式：不实际调用上游服务，使用模拟数据
    mock_mode: bool = True

    # HTTP 客户端
    http_timeout: int = 30
    http_max_retries: int = 3

    # 认证
    jwt_secret: str = "dev-secret-key-change-in-production-at-least-256-bits"
    jwt_issuer: str = "shuqing-bigdata"
    dev_mode: bool = True

    # WebSocket 推送
    ws_heartbeat_interval: int = 15

    @classmethod
    def from_env(cls) -> "Settings":
        """从环境变量加载配置。"""
        return cls(
            port=int(os.environ.get("LOOP_PORT", "18088")),
            finetune_url=os.environ.get(
                "FINETUNE_URL", "http://localhost:8095"
            ),
            evaluation_url=os.environ.get(
                "EVALUATION_URL", "http://localhost:18086"
            ),
            registry_url=os.environ.get(
                "REGISTRY_URL", "http://localhost:18089"
            ),
            work_dir=os.environ.get("LOOP_WORK_DIR", "/tmp/finetune-loop"),
            mock_mode=_env_bool("LOOP_MOCK_MODE", True),
            http_timeout=int(os.environ.get("LOOP_HTTP_TIMEOUT", "30")),
            http_max_retries=int(os.environ.get("LOOP_HTTP_MAX_RETRIES", "3")),
            jwt_secret=os.environ.get(
                "JWT_SECRET",
                "dev-secret-key-change-in-production-at-least-256-bits",
            ),
            jwt_issuer=os.environ.get("JWT_ISSUER", "shuqing-bigdata"),
            dev_mode=_env_bool("LOOP_DEV_MODE", True),
            ws_heartbeat_interval=int(
                os.environ.get("LOOP_WS_HEARTBEAT", "15")
            ),
        )


# 全局单例
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