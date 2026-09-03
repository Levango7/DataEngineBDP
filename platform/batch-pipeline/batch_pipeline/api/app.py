"""FastAPI 应用工厂（对齐 asset-exchange 服务约定）."""

from __future__ import annotations

from typing import Optional

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from ..helpers import VERSION
from .deps import getAuthContext
from .routers import batches, health
from .runner import BatchRunner
from .settings import Settings, cors_origins


def create_app(
    settings: Optional[Settings] = None,
    runner: Optional[BatchRunner] = None,
) -> FastAPI:
    """创建 FastAPI 应用.

    Args:
        settings: 运行配置，不传则读环境变量。
        runner: 批次执行器，不传则新建（测试可注入）。

    Returns:
        FastAPI 应用。
    """
    if settings is None:
        settings = Settings.fromEnv()
    if runner is None:
        runner = BatchRunner()

    app = FastAPI(
        title="Batch Pipeline API",
        description=(
            "数据引擎大数据平台 · 数据质量与批处理服务（data-quality 实体）\n\n"
            "五阶段批处理流水线（ingest→validate→clean→compute→output）的提交与查询薄壳：\n"
            "批次提交 / 状态查询 / 质量报告 / 批次列表，租户按 JWT/X-Tenant-Id 隔离。"
        ),
        version=VERSION,
        docs_url="/docs",
        redoc_url="/redoc",
        openapi_url="/openapi.json",
    )
    app.state.settings = settings
    app.state.runner = runner

    app.add_middleware(
        CORSMiddleware,
        allow_origins=cors_origins(),
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    prefix = settings.apiPrefix
    app.include_router(health.router, prefix=prefix)
    app.include_router(batches.router, prefix=prefix, dependencies=[Depends(getAuthContext)])

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
                "error": (
                    exc.detail.lower().replace(" ", "_") if isinstance(exc.detail, str) else "error"
                ),
                "message": str(exc.detail),
            },
        )

    return app
