"""健康检查路由."""

from __future__ import annotations

from fastapi import APIRouter, Request

router = APIRouter(tags=["health"])


@router.get("/api/v1/health", summary="健康检查")
async def health(request: Request) -> dict:
    """健康检查端点.

    Returns:
        {"status": "UP", "version": "0.1.0", "deployMode": "mock|helm"}
    """
    registry = request.app.state.registry
    return {
        "status": "UP",
        "version": "0.1.0",
        "deployMode": registry.settings.deployMode,
        "templateCount": len(registry.engine.templates),
    }
