"""FastAPI 应用工厂."""

from __future__ import annotations

import os
from typing import Optional

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from industry_templates.api.jwt_auth import getAuthContext
from industry_templates.api.routers import categories, health, templates
from industry_templates.config.settings import Settings, get_settings
from industry_templates.services.registry import ServiceRegistry, build_services

# CORS 白名单允许的方法与请求头（与 APISIX cors 插件配置对齐）
_ALLOWED_METHODS = ["GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"]
_ALLOWED_HEADERS = ["Authorization", "Content-Type", "X-Tenant-Id", "X-Request-Id"]


def _corsOrigins() -> list[str]:
    """读取跨域白名单：CORS_ORIGINS 优先，兼容 CORS_ALLOWED_ORIGINS。

    未配置时返回空列表 → 不挂载 CORS 中间件（fail-closed）；
    生产环境跨域统一由 APISIX cors 插件处理，服务级 CORS 仅供本地/直连调试。
    """
    raw = os.environ.get("CORS_ORIGINS") or os.environ.get("CORS_ALLOWED_ORIGINS") or ""
    return [o.strip() for o in raw.split(",") if o.strip()]


def create_app(
    settings: Optional[Settings] = None,
    registry: Optional[ServiceRegistry] = None,
) -> FastAPI:
    """创建 FastAPI 应用实例.

    Args:
        settings: 配置，不传则使用全局单例
        registry: 服务注册表，不传则根据 settings 构建（便于测试注入）

    Returns:
        FastAPI 应用
    """
    if settings is None:
        settings = get_settings()
    if registry is None:
        registry = build_services(settings)

    app = FastAPI(
        title="Industry Templates Platform",
        description=(
            "数据引擎大数据平台 · L5.3 行业应用模板平台\n\n"
            "面向外部客户的预置分析模板（金融风控/零售画像/制造质检），"
            "让客户开箱即用而非从零搭建。\n"
            "核心能力：TemplateEngine（模板解析 + 参数注入 + 一键部署）。"
        ),
        version="0.1.0",
        docs_url="/docs",
        redoc_url="/redoc",
        openapi_url="/openapi.json",
    )

    app.state.settings = settings
    app.state.registry = registry

    # CORS：仅当显式配置白名单时挂载（fail-closed，避免与网关 cors 插件双重设置）
    origins = _corsOrigins()
    if origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=origins,
            allow_credentials=True,
            allow_methods=_ALLOWED_METHODS,
            allow_headers=_ALLOWED_HEADERS,
            max_age=3600,
        )

    prefix = settings.apiPrefix
    # 健康检查保持匿名可探活；业务路由统一 JWT 鉴权（对齐 llmops / ml-platform / business-portal 模式）
    app.include_router(health.router)
    # 业务端点统一挂 JWT 鉴权依赖（MIRRORED jwt_auth.py）；
    # AUTH_MODE=none（本地/测试默认）时匿名放行，生产设 jwt 强制校验。
    authDeps = [Depends(getAuthContext)]
    # categories 必须在 templates 之前注册，避免 /templates/categories
    # 被 /templates/{template_id} 抢先匹配
    app.include_router(categories.router, prefix=prefix, dependencies=authDeps)
    app.include_router(templates.router, prefix=prefix, dependencies=authDeps)

    @app.exception_handler(Exception)
    async def global_exception_handler(request: Request, exc: Exception) -> JSONResponse:
        """全局异常处理器：统一 500 错误响应格式."""
        return JSONResponse(
            status_code=500,
            content={"error": "internal_error", "message": str(exc)},
        )

    @app.exception_handler(HTTPException)
    async def http_exception_handler(request: Request, exc: HTTPException) -> JSONResponse:
        """HTTPException 处理器：统一错误响应格式为 {error, message}."""
        return JSONResponse(
            status_code=exc.status_code,
            content={
                "error": (exc.detail.lower().replace(" ", "_") if isinstance(exc.detail, str) else "error"),
                "message": str(exc.detail),
            },
        )

    return app
