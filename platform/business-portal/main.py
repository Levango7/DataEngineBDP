"""Business Portal entry point.

启动 FastAPI 服务器，根据环境变量 BP_STORE_TYPE 选择 Mock 或 SQLite 实现。

Usage:
    python main.py                       # 默认 Mock 模式，监听 0.0.0.0:8084
    BP_STORE_TYPE=sqlite python main.py  # SQLite 模式
    BP_HOST=127.0.0.1 BP_PORT=9000 python main.py
"""

from __future__ import annotations

import uvicorn

from business_portal.config.settings import get_settings


def main() -> None:
    """启动 Business Portal FastAPI 服务."""
    settings = get_settings()
    uvicorn.run(
        "business_portal.api.app:create_app",
        factory=True,
        host=settings.host,
        port=settings.port,
        log_level=settings.logLevel,
        reload=settings.reload,
    )


if __name__ == "__main__":
    main()
