"""FastAPI 应用工厂."""

from __future__ import annotations

import os
from typing import Optional

from fastapi import Depends, FastAPI
from fastapi.middleware.cors import CORSMiddleware

from knowledge_engine.api.jwt_auth import getAuthContext
from knowledge_engine.api.routers import health, spaces
from knowledge_engine.config.settings import Settings, get_settings
from knowledge_engine.services.registry import ServiceRegistry, build_services

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
        title="Knowledge Engineering Engine",
        description=(
            "数据引擎大数据平台 · 智能数据层 · 知识工程引擎 (L4.5.2)\n\n"
            "从文本中抽取实体与关系，构建知识图谱（NebulaGraph），并提供图查询 API。\n"
            "复用 governance/lineage-analyzer 的血缘图谱基础设施思路：接口抽象 + Mock 实现。"
        ),
        version="0.1.0",
        docs_url="/docs",
        redoc_url="/redoc",
        openapi_url="/openapi.json",
    )

    # 把 registry 挂到 app.state，路由通过依赖获取
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
    app.include_router(health.router)
    app.include_router(spaces.router, prefix=prefix, dependencies=[Depends(getAuthContext)])

    return app
