"""闭环编排服务配置.

通过环境变量加载配置，支持 Docker 部署与本地开发。

环境变量：
- LOOP_PORT：服务端口（默认 18088）
- FINETUNE_URL：T032 微调引擎地址（默认 http://localhost:8095）
- EVALUATION_URL：T031 评测平台地址（默认 http://localhost:18086）
- REGISTRY_URL：模型仓库服务地址（默认 http://localhost:18089）
- LOOP_WORK_DIR：工作目录（默认 /tmp/finetune-loop）
- LOOP_MOCK_MODE：Mock 模式（默认 true，不实际调用外部服务）
- JWT_SECRET：JWT 签名密钥
- LOOP_DEV_MODE：开发模式，跳过 JWT 校验（默认 false；本地开发显式设 true）
- AUTH_MODE：jwt_auth 镜像模块的鉴权开关（jwt=强制鉴权；none=匿名放行）
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
    # 安全策略：dev_mode 默认 false——生产/未显式配置时强制 JWT 鉴权
    # （由 jwt_auth.AUTH_MODE 驱动）；本地开发显式 LOOP_DEV_MODE=true 匿名放行。
    jwt_secret: str = ""
    jwt_issuer: str = "shuqing-bigdata"
    dev_mode: bool = False

    # WebSocket 推送
    ws_heartbeat_interval: int = 15

    @classmethod
    def from_env(cls) -> "Settings":
        """从环境变量加载配置."""
        settings = cls(
            port=int(os.environ.get("LOOP_PORT", "18088")),
            finetune_url=os.environ.get("FINETUNE_URL", "http://localhost:8095"),
            evaluation_url=os.environ.get("EVALUATION_URL", "http://localhost:18086"),
            registry_url=os.environ.get("REGISTRY_URL", "http://localhost:18089"),
            work_dir=os.environ.get("LOOP_WORK_DIR", "/tmp/finetune-loop"),
            mock_mode=_env_bool("LOOP_MOCK_MODE", True),
            http_timeout=int(os.environ.get("LOOP_HTTP_TIMEOUT", "30")),
            http_max_retries=int(os.environ.get("LOOP_HTTP_MAX_RETRIES", "3")),
            jwt_secret=os.environ.get("JWT_SECRET", ""),
            jwt_issuer=os.environ.get("JWT_ISSUER", "shuqing-bigdata"),
            dev_mode=_env_bool("LOOP_DEV_MODE", False),
            ws_heartbeat_interval=int(os.environ.get("LOOP_WS_HEARTBEAT", "15")),
        )
        # dev_mode 是 jwt_auth.AUTH_MODE 的服务侧别名：
        # 未显式设置 AUTH_MODE 时按 dev_mode 推导（false→jwt 强制鉴权）。
        if "AUTH_MODE" not in os.environ:
            os.environ["AUTH_MODE"] = "none" if settings.dev_mode else "jwt"
        if settings.dev_mode:
            # 匿名放行模式下 jwt_auth 不读密钥，无需占位
            os.environ.setdefault("JWT_SECRET", settings.jwt_secret)
        else:
            os.environ["JWT_SECRET"] = settings.jwt_secret
        return settings

    def validate(self) -> "Settings":
        """校验配置安全性：非 dev_mode 下 jwt_secret 必须显式配置。"""
        if not self.dev_mode and not self.jwt_secret:
            raise RuntimeError(
                "JWT_SECRET environment variable is required when LOOP_DEV_MODE is false "
                "(default); set JWT_SECRET (>=32 bytes) for JWT auth, or set "
                "LOOP_DEV_MODE=true explicitly for local development only"
            )
        if not self.dev_mode and len(self.jwt_secret) < 32:
            raise RuntimeError(f"JWT_SECRET must be at least 32 bytes, got {len(self.jwt_secret)}")
        return self


# 全局单例
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
