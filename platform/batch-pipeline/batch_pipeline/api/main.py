"""uvicorn 入口：``python -m batch_pipeline.api.main``.

环境变量：API_HOST / API_PORT / API_PREFIX / RUN_ROOT / PIPELINE_CONFIG /
AUTH_MODE / JWT_SECRET（鉴权语义见同包 jwt_auth.py）。
"""

from __future__ import annotations

import uvicorn

from .settings import Settings


def main() -> None:
    settings = Settings.fromEnv()
    uvicorn.run(
        "batch_pipeline.api.app:create_app",
        factory=True,
        host=settings.host,
        port=settings.port,
    )


if __name__ == "__main__":
    main()
