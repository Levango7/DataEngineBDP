"""FastAPI 应用工厂."""

from __future__ import annotations

from typing import Optional

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse

from business_portal.api.jwt_auth import getAuthContext
from business_portal.api.routers import (
    business_lines,
    catalog,
    dashboard,
    dashboards,
    health,
    reports,
    workbench,
)
from business_portal.config.settings import Settings, get_settings
from business_portal.services.bi_dashboard_store import build_bi_dashboard_store
from business_portal.services.registry import ServiceRegistry, build_services


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
        title="Business Portal",
        description=(
            "数据引擎大数据平台 · L5.4 对内业务线门户\n\n"
            '以"业务线-团队-项目"组织视图复用平台全部能力，免计费或走内部结算，'
            "不承诺 SLA，资源受部门预算软约束。仅适用于标准版+旗舰版。\n"
            "多业务线隔离（数据隔离 + 权限隔离）。"
        ),
        version="0.1.0",
        docs_url="/docs",
        redoc_url="/redoc",
        openapi_url="/openapi.json",
    )

    # 把 registry 挂到 app.state，路由通过依赖获取
    app.state.settings = settings
    app.state.registry = registry
    # BI 看板仓储（独立于业务线概览；前端 /dashboards 契约）
    registry.biDashboardStore = build_bi_dashboard_store()

    prefix = settings.apiPrefix
    # 健康检查保持匿名可探活；业务路由统一 JWT 鉴权（对齐 open-api-catalog 模式）
    app.include_router(health.router, prefix=prefix)
    app.include_router(business_lines.router, prefix=prefix, dependencies=[Depends(getAuthContext)])
    app.include_router(dashboard.router, prefix=prefix, dependencies=[Depends(getAuthContext)])
    app.include_router(workbench.router, prefix=prefix, dependencies=[Depends(getAuthContext)])
    app.include_router(catalog.router, prefix=prefix, dependencies=[Depends(getAuthContext)])
    app.include_router(reports.router, prefix=prefix, dependencies=[Depends(getAuthContext)])
    app.include_router(dashboards.router, prefix=prefix, dependencies=[Depends(getAuthContext)])

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
