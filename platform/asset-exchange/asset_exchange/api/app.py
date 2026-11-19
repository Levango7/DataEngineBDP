"""FastAPI 应用工厂."""

from __future__ import annotations

import os
from typing import Optional

from fastapi import Depends, FastAPI, Request
from fastapi.encoders import jsonable_encoder
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from asset_exchange.api.jwt_auth import getAuthContext
from asset_exchange.api.routers import (
    assets,
    audit,
    health,
    settlements,
    subscriptions,
)
from asset_exchange.config.settings import Settings, get_settings
from asset_exchange.services.registry import ServiceRegistry, build_services


def _corsOrigins() -> list[str]:
    raw = os.environ.get("CORS_ORIGINS", "http://localhost:5173")
    return [o.strip() for o in raw.split(",") if o.strip()]


# error 字段是供客户端分派的机器码，必须稳定、可枚举；人读详情只放 message。
# 不再用 exc.detail.lower().replace(" ", "_") 生成含中文/资产名的动态伪错误码。
_HTTP_ERROR_CODES = {
    400: "BAD_REQUEST",
    401: "UNAUTHORIZED",
    403: "FORBIDDEN",
    404: "NOT_FOUND",
    405: "METHOD_NOT_ALLOWED",
    409: "CONFLICT",
    413: "PAYLOAD_TOO_LARGE",
    422: "UNPROCESSABLE_ENTITY",
    429: "RATE_LIMITED",
    500: "INTERNAL_ERROR",
    502: "BAD_GATEWAY",
    503: "SERVICE_UNAVAILABLE",
    504: "GATEWAY_TIMEOUT",
}


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
    app.include_router(
        assets.router,
        prefix=prefix,
        dependencies=[Depends(getAuthContext)],
    )
    app.include_router(
        subscriptions.router,
        prefix=prefix,
        dependencies=[Depends(getAuthContext)],
    )
    app.include_router(
        audit.router,
        prefix=prefix,
        dependencies=[Depends(getAuthContext)],
    )
    # 出账闭环：基于 finops 账单的结算端点
    app.include_router(
        settlements.router,
        prefix=prefix,
        dependencies=[Depends(getAuthContext)],
    )

    # 全局异常处理器：error=稳定机器码，message=人读详情，
    # details=可选结构明细。
    @app.exception_handler(Exception)
    async def global_exception_handler(request: Request, exc: Exception):
        # 不向客户端回显异常原文（可能包含 SQL、路径、连接串或内部实现细节）。
        return JSONResponse(
            status_code=500,
            content={"error": "INTERNAL_ERROR", "message": "服务器内部错误，请联系管理员"},
        )

    @app.exception_handler(StarletteHTTPException)
    async def http_exception_handler(
        request: Request,
        exc: StarletteHTTPException,
    ):
        content = {
            "error": _HTTP_ERROR_CODES.get(exc.status_code, "HTTP_ERROR"),
            "message": exc.detail if isinstance(exc.detail, str) else "请求失败",
        }
        if not isinstance(exc.detail, str):
            content["details"] = jsonable_encoder(exc.detail)
        return JSONResponse(status_code=exc.status_code, content=content)

    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(
        request: Request,
        exc: RequestValidationError,
    ):
        # FastAPI 默认 {detail:[...]} 与业务 {error,message} 并存，客户端需要三套解析；
        # 统一到同一顶层形状，字段级错误放 details，不再塞入 error 机器码。
        return JSONResponse(
            status_code=422,
            content={
                "error": "VALIDATION_ERROR",
                "message": "请求参数校验失败",
                "details": jsonable_encoder(exc.errors()),
            },
        )

    return app
