"""ML Platform entry point.

启动 FastAPI 服务器，根据环境变量 ML_BACKEND_TYPE 选择 Mock / Sklearn / Spark 实现。

Usage:
    python main.py                            # 默认 Mock 模式，监听 0.0.0.0:8080
    ML_BACKEND_TYPE=sklearn python main.py   # Scikit-learn 模式
    ML_HOST=127.0.0.1 ML_PORT=9000 python main.py
"""

from __future__ import annotations

import uvicorn

from ml_platform.config.settings import getSettings

# uvicorn 工厂字符串（模块:可调用名）。抽成常量是为了让测试能解析它——
# 本文件原先写的是 `create_app` 与 `get_settings`，而实际导出名是 camelCase
# （createApp / getSettings），于是 `python main.py`（即 Dockerfile 的 CMD）
# 在启动即 ImportError，容器根本起不来，而单测从不 import main，无人拦。
APP_FACTORY = "ml_platform.api.app:createApp"


def main() -> None:
    """启动 ML Platform FastAPI 服务."""
    settings = getSettings()
    uvicorn.run(
        APP_FACTORY,
        factory=True,
        host=settings.host,
        port=settings.port,
        log_level=settings.logLevel,
        reload=settings.reload,
    )


if __name__ == "__main__":
    main()
