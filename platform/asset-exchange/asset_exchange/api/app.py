"""FastAPI 应用工厂."""

from __future__ import annotations

import os
from typing import Optional

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from asset_exchange.api.jwt_auth import getAuthContext
from asset_exchange.api.routers import assets, audit, health, subscriptions
from asset_exchange.config.settings import Settings, get_settings
from asset_exchange.services.registry import ServiceRegistry, build_services


def _corsOrigins() -> list[str]:
    raw = os.environ.get("CORS_ORIGINS", "http://localhost:5173")
    return [o.strip() for o in raw.split(",") if o.strip()]


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
            "数据引擎大数据平台 · L5 多租户产品层 · 数据资产流通平台 (L5.6)\n\n"
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

    app.add_middleware(
        CORSMiddleware,
        allow_origins=_corsOrigins(),
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    prefix = settings.apiPrefix
    app.include_router(health.router, prefix=prefix)
    app.include_router(assets.router, prefix=prefix, dependencies=[Depends(getAuthContext)])
    app.include_router(subscriptions.router, prefix=prefix, dependencies=[Depends(getAuthContext)])
    app.include_router(audit.router, prefix=prefix, dependencies=[Depends(getAuthContext)])

    # ---- 全局异常处理器：统一错误响应格式 {error, message} ----
    @app.exception_handler(Exception)
    async def global_exception_handler(request: Request, exc: Exception):
        return JSONResponse(
            status_code=500,
            content={"error": "internal_error", "message": str(exc)},
        )

    @app.exception_handler(HTTPException)
    async def http_exception_handler(request: Request, exc: HTTPException):
        return JSONResponse(
            status_code=exc.status_code,
            content={
                "error": exc.detail.lower().replace(" ", "_") if isinstance(exc.detail, str) else "error",
                "message": str(exc.detail),
            },
        )

    return app
