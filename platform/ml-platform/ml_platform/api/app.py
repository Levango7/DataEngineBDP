"""FastAPI 应用工厂."""

from __future__ import annotations

from typing import Optional

from fastapi import FastAPI

from ml_platform.api.routers import (
    experiments,
    features,
    health,
    models,
    training,
)
from ml_platform.config.settings import Settings, getSettings
from ml_platform.services.registry import (
    ServiceRegistry,
    buildServices,
)


def createApp(
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
        settings = getSettings()
    if registry is None:
        registry = buildServices(settings)

    app = FastAPI(
        title="ML Platform",
        description=(
            "数据引擎大数据平台 · 智能数据层 · 机器学习平台 (L4.5.6)\n\n"
            "实验追踪 → 训练 → 评估 → 预测 一体化 MLOps；\n"
            "对齐 MLflow（开源 MLOps 标准）/ Spark MLlib / Scikit-learn。"
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
    app.include_router(experiments.router, prefix=prefix)
    app.include_router(training.router, prefix=prefix)
    app.include_router(models.router, prefix=prefix)
    app.include_router(features.router, prefix=prefix)

    return app
