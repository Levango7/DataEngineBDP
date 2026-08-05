"""Asset Exchange Platform entry point.

启动 FastAPI 服务器，根据环境变量 ASSET_EXCHANGE_STORE_TYPE 选择 Mock 或外部实现。

Usage:
    python main.py                                       # 默认 Mock 模式，监听 0.0.0.0:8086
    ASSET_EXCHANGE_STORE_TYPE=mock python main.py        # Mock 模式
    ASSET_EXCHANGE_HOST=127.0.0.1 ASSET_EXCHANGE_PORT=9000 python main.py
"""
from __future__ import annotations

import uvicorn

from asset_exchange.config.settings import get_settings


def main() -> None:
    """启动 AssetExchange FastAPI 服务."""
    settings = get_settings()
    uvicorn.run(
        "asset_exchange.api.app:create_app",
        factory=True,
        host=settings.host,
        port=settings.port,
        log_level=settings.logLevel,
        reload=settings.reload,
    )


if __name__ == "__main__":
    main()