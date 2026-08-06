"""健康检查路由."""
from __future__ import annotations

from fastapi import APIRouter, Request

router = APIRouter(tags=["health"])


@router.get("/health", summary="健康检查")
async def health(request: Request) -> dict:
    """健康检查端点.

    Returns:
        {"status": "UP", "store": "mock"}
    """
    registry = request.app.state.registry
    return {
        "status": "UP",
        "store": registry.settings.storeType,
        "version": "0.1.0",
    }