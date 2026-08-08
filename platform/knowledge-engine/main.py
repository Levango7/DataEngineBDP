"""Knowledge Engineering Engine entry point.

启动 FastAPI 服务器，根据环境变量选择 Mock / NebulaGraph / LLM 实现。

Usage:
    python main.py                              # 默认 Mock 模式，监听 0.0.0.0:8080
    KE_STORE_TYPE=nebula python main.py         # NebulaGraph 模式
    KE_EXTRACTOR_TYPE=llm python main.py        # LLM 抽取模式
    KE_HOST=127.0.0.1 KE_PORT=9000 python main.py
"""

from __future__ import annotations

import uvicorn

from knowledge_engine.config.settings import get_settings


def main() -> None:
    """启动知识工程引擎 FastAPI 服务."""
    settings = get_settings()
    uvicorn.run(
        "knowledge_engine.api.app:create_app",
        factory=True,
        host=settings.host,
        port=settings.port,
        log_level=settings.logLevel,
        reload=settings.reload,
    )


if __name__ == "__main__":
    main()
