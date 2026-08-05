"""Industry Templates Platform entry point.

启动 FastAPI 服务器。

Usage:
    python main.py                                       # 默认 Mock 模式，监听 0.0.0.0:8090
    INDUSTRY_TEMPLATES_DEPLOY_MODE=helm python main.py   # Helm 模式
    INDUSTRY_TEMPLATES_HOST=127.0.0.1 INDUSTRY_TEMPLATES_PORT=9000 python main.py
"""
from __future__ import annotations

import uvicorn

from industry_templates.config.settings import get_settings


def main() -> None:
    """启动行业应用模板 FastAPI 服务."""
    settings = get_settings()
    uvicorn.run(
        "industry_templates.api.app:create_app",
        factory=True,
        host=settings.host,
        port=settings.port,
        log_level=settings.logLevel,
        reload=settings.reload,
    )


if __name__ == "__main__":
    main()