"""FastAPI 应用工厂."""
from __future__ import annotations

from typing import Optional

from fastapi import FastAPI

from openapi_catalog.api.routers import (
    apis,
    health,
    invoke,
    metrics_docs,
    subscriptions,
)
from openapi_catalog.config.settings import Settings, get_settings
from openapi_catalog.services.registry import ServiceRegistry, build_services


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
        title="Open API Service Catalog",
        description=(
            "数擎大数据平台 · L5 多租户产品层 · 开放 API 服务目录 (L5.5)\n\n"
            "将平台数据能力封装为 REST/gRPC API，经 APISIX 网关对外暴露；\n"
            "配套服务目录支持浏览、搜索、订阅、评分，形成"
            "\"数据即 API、API 即资产\"的开放生态。\n\n"
            "对标：AWS API Gateway + Marketplace / 阿里云 API 市场 / Kong Dev Portal"
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
    app.include_router(apis.router, prefix=prefix)
    app.include_router(subscriptions.router, prefix=prefix)
    app.include_router(subscriptions.subscriptions_router, prefix=prefix)
    app.include_router(invoke.router, prefix=prefix)
    app.include_router(metrics_docs.router, prefix=prefix)

    return app