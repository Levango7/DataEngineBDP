"""Open API Service Catalog entry point.

启动 FastAPI 服务器，对外提供开放 API 服务目录（L5.5）。

Usage:
    python main.py                          # 默认监听 0.0.0.0:8090
    OPENAPI_CATALOG_HOST=127.0.0.1 OPENAPI_CATALOG_PORT=9000 python main.py
"""

from __future__ import annotations

from openapi_catalog.config.settings import get_settings
import uvicorn


def main() -> None:
    """启动开放 API 服务目录 FastAPI 服务."""
    settings = get_settings()
    uvicorn.run(
        "openapi_catalog.api.app:create_app",
        factory=True,
        host=settings.host,
        port=settings.port,
        log_level=settings.logLevel,
        reload=settings.reload,
    )


if __name__ == "__main__":
    main()
