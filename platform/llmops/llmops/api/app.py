"""FastAPI 应用工厂."""

from __future__ import annotations

from typing import Optional

from fastapi import FastAPI

from llmops.api.routers import deployments, health, models, monitor, training
from llmops.config.settings import Settings, get_settings
from llmops.services.registry import ServiceRegistry, build_services


def create_app(
    settings: Optional[Settings] = None,
    registry: Optional[ServiceRegistry] = None,
) -> FastAPI:
    """创建 FastAPI 应用实例.

    Args:
        settings: 配置，不传则使用全局单例。
        registry: 服务注册表，不传则根据 settings 构建（便于测试注入）。

    Returns:
        FastAPI 应用。
    """
    if settings is None:
        settings = get_settings()
    if registry is None:
        registry = build_services(settings)

    app = FastAPI(
        title="LLMOps Platform",
        description=(
            "数据引擎大数据平台 · 智能数据层 · LLMOps 运营平台 (L4.5.3)\n\n"
            "从微调、评估到部署的一体化大模型运营；基座模型与领域模型统一纳管。\n"
            "复用 L4.5.2 机器学习 MLflow Tracking/Registry 底座。"
        ),
        version="0.1.0",
        docs_url="/docs",
        redoc_url="/redoc",
        openapi_url="/openapi.json",
    )

    # 把 registry 挂到 app.state，路由通过依赖获取
    app.state.settings = settings
    app.state.registry = registry

    prefix = settings.apiPrefix
    app.include_router(health.router)
    app.include_router(models.router, prefix=prefix)
    app.include_router(training.router, prefix=prefix)
    app.include_router(deployments.router, prefix=prefix)
    app.include_router(monitor.router, prefix=prefix)

    return app
