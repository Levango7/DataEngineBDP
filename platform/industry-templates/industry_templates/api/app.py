"""FastAPI 应用工厂."""

from __future__ import annotations

from typing import Optional

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse

from industry_templates.api.routers import categories, health, templates
from industry_templates.config.settings import Settings, get_settings
from industry_templates.services.registry import ServiceRegistry, build_services


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
            "数擎大数据平台 · L5.3 行业应用模板平台\n\n"
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

    prefix = settings.apiPrefix
    app.include_router(health.router)
    # categories 必须在 templates 之前注册，避免 /templates/categories
    # 被 /templates/{template_id} 抢先匹配
    app.include_router(categories.router, prefix=prefix)
    app.include_router(templates.router, prefix=prefix)

    @app.exception_handler(Exception)
    async def global_exception_handler(request: Request, exc: Exception):
        """全局异常处理器：统一 500 错误响应格式."""
        return JSONResponse(
            status_code=500,
            content={"error": "internal_error", "message": str(exc)},
        )

    @app.exception_handler(HTTPException)
    async def http_exception_handler(request: Request, exc: HTTPException):
        """HTTPException 处理器：统一错误响应格式为 {error, message}."""
        return JSONResponse(
            status_code=exc.status_code,
            content={
                "error": (exc.detail.lower().replace(" ", "_") if isinstance(exc.detail, str) else "error"),
                "message": str(exc.detail),
            },
        )

    return app
