"""模型仓库注册部署服务 FastAPI 主入口.

端口：18089
路由前缀：/api/v1/registry

端点：
    GET  /health                              健康检查
    POST /api/v1/registry/models              注册模型
    GET  /api/v1/registry/models              查询模型列表
    GET  /api/v1/registry/models/{name}       查询模型详情
    POST /api/v1/registry/deployments         创建部署
    GET  /api/v1/registry/deployments         查询部署列表
    DELETE /api/v1/registry/deployments/{id}  停止部署
    GET  /api/v1/registry/deployments/{id}/health 健康检查
"""

from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.registry_routes import SimpleModelRegistry, create_router
from app.core.deployment_manager import DeploymentManager
from app.core.health_checker import HealthChecker

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
)
logger = logging.getLogger(__name__)


def _env_bool(name: str, default: bool = False) -> bool:
    val = os.environ.get(name, "").lower()
    if val in ("true", "1", "yes", "on"):
        return True
    if val in ("false", "0", "no", "off", ""):
        return default
    return default


class AppState:
    """应用状态容器."""

    deployment_manager: DeploymentManager
    health_checker: HealthChecker
    model_registry: SimpleModelRegistry


_state = AppState()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期管理."""
    mock_mode = _env_bool("REGISTRY_MOCK_MODE", True)
    port = int(os.environ.get("REGISTRY_PORT", "18089"))
    logger.info(
        "启动模型仓库服务: port=%s, mock=%s", port, mock_mode,
    )

    _state.deployment_manager = DeploymentManager(mock_mode=mock_mode)
    _state.health_checker = HealthChecker(mock_mode=mock_mode)
    _state.model_registry = SimpleModelRegistry()

    router = create_router(
        deployment_manager=_state.deployment_manager,
        health_checker=_state.health_checker,
        model_registry=_state.model_registry,
    )
    app.include_router(router)

    yield

    logger.info("模型仓库服务已关闭")


def create_app() -> FastAPI:
    """创建 FastAPI 应用."""
    app = FastAPI(
        title="数据引擎大数据平台 · 模型仓库注册部署",
        description=(
            "T033 模型仓库：微调后模型注册 + 一键部署 + "
            "部署管理 + 健康检查"
        ),
        version="0.1.0",
        lifespan=lifespan,
        docs_url="/docs",
        redoc_url="/redoc",
    )

    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    @app.get("/health", tags=["health"])
    async def health():
        """健康检查."""
        return {
            "status": "UP",
            "service": "model-registry",
            "version": "0.1.0",
            "mockMode": _env_bool("REGISTRY_MOCK_MODE", True),
        }

    @app.get("/", include_in_schema=False)
    async def root():
        return {
            "service": "model-registry",
            "version": "0.1.0",
            "docs": "/docs",
            "health": "/health",
        }

    return app


app = create_app()


if __name__ == "__main__":
    import uvicorn

    port = int(os.environ.get("REGISTRY_PORT", "18089"))
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=port,
        log_level="info",
    )