"""API 服务配置（环境变量驱动，对齐平台服务约定）."""

from __future__ import annotations

import os
from dataclasses import dataclass, field

from ..helpers import ROOT, abs_path


def _corsOrigins() -> list[str]:
    raw = os.environ.get("CORS_ORIGINS", "http://localhost:5173")
    return [o.strip() for o in raw.split(",") if o.strip()]


@dataclass
class Settings:
    """API 运行配置；测试可直接构造实例注入 create_app."""

    apiPrefix: str = "/api/v1"
    host: str = "0.0.0.0"
    port: int = 8080
    # 批次运行根目录（租户分区 run/<tenant>/<batch>/ 挂在其下）
    runRoot: str = field(default_factory=lambda: os.path.join(ROOT, "run"))
    # 提交批次未携带 config 时的基础配置文件
    configPath: str = field(default_factory=lambda: abs_path("config/pipeline.json"))

    @classmethod
    def fromEnv(cls) -> Settings:
        return cls(
            apiPrefix=os.environ.get("API_PREFIX", "/api/v1"),
            host=os.environ.get("API_HOST", "0.0.0.0"),
            port=int(os.environ.get("API_PORT", "8080")),
            runRoot=os.environ.get("RUN_ROOT", os.path.join(ROOT, "run")),
            configPath=os.environ.get("PIPELINE_CONFIG", abs_path("config/pipeline.json")),
        )


def cors_origins() -> list[str]:
    return _corsOrigins()
