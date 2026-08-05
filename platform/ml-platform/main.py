"""ML Platform entry point.

启动 FastAPI 服务器，根据环境变量 ML_BACKEND_TYPE 选择 Mock / Sklearn / Spark 实现。

Usage:
    python main.py                            # 默认 Mock 模式，监听 0.0.0.0:8080
    ML_BACKEND_TYPE=sklearn python main.py   # Scikit-learn 模式
    ML_HOST=127.0.0.1 ML_PORT=9000 python main.py
"""
from __future__ import annotations

import uvicorn

from ml_platform.config.settings import get_settings


def main() -> None:
    """启动 ML Platform FastAPI 服务."""
    settings = get_settings()
    uvicorn.run(
        "ml_platform.api.app:create_app",
        factory=True,
        host=settings.host,
        port=settings.port,
        log_level=settings.logLevel,
        reload=settings.reload,
    )


if __name__ == "__main__":
    main()