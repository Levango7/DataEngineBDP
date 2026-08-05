"""FastAPI 应用工厂."""
from __future__ import annotations

from typing import Optional

from fastapi import FastAPI

from asset_exchange.api.routers import assets, subscriptions, health
from asset_exchange.config.settings import Settings, get_settings
from asset_exchange.services.registry import ServiceRegistry, build_services


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
        title="Asset Exchange Platform",
        description=(
            "数擎大数据平台 · L5 多租户产品层 · 数据资产流通平台 (L5.6)\n\n"
            "将平台内数据集、数据服务、数据模型、大模型四类资产统一登记、上架、流通、变现，\n"
            "构建 提供方—平台—消费方 三方市场。"
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
    app.include_router(assets.router, prefix=prefix)
    app.include_router(subscriptions.router, prefix=prefix)

    return app