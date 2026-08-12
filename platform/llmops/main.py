"""LLMOps Platform entry point.

启动 FastAPI 服务器，根据环境变量 LLMOPS_STORE_TYPE 选择 Mock 或 MLflow 实现。

Usage:
    python main.py                          # 默认 Mock 模式，监听 0.0.0.0:8080
    LLMOPS_STORE_TYPE=mlflow python main.py # MLflow 模式
    LLMOPS_HOST=127.0.0.1 LLMOPS_PORT=9000 python main.py
"""

from __future__ import annotations

import uvicorn

from llmops.config.settings import get_settings


def main() -> None:
    """启动 LLMOps FastAPI 服务."""
    settings = get_settings()
    uvicorn.run(
        "llmops.api.app:create_app",
        factory=True,
        host=settings.host,
        port=settings.port,
        log_level=settings.log_level,
        reload=settings.reload,
    )


if __name__ == "__main__":
    main()
