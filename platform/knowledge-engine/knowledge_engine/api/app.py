"""FastAPI 应用工厂."""

from __future__ import annotations

from typing import Optional

from fastapi import FastAPI

from knowledge_engine.api.routers import health, spaces
from knowledge_engine.config.settings import Settings, get_settings
from knowledge_engine.services.registry import ServiceRegistry, build_services


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

    prefix = settings.apiPrefix
    app.include_router(health.router)
    app.include_router(spaces.router, prefix=prefix)

    return app
