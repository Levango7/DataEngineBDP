"""业务线数据概览路由."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from business_portal.api.routers.deps import get_registry, status_for_error
from business_portal.models.dashboard import Dashboard
from business_portal.repositories import PortalError
from business_portal.services.registry import ServiceRegistry

router = APIRouter(prefix="/business-lines", tags=["dashboard"])


@router.get(
    "/{bl_id}/dashboard",
    response_model=Dashboard,
    summary="业务线数据概览",
)
async def get_dashboard(
    bl_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> Dashboard:
    """获取业务线数据概览（KPI 卡片 + 趋势图 + 实时监控 + TopN 项目）."""
    try:
        return await registry.dashboardService.get_dashboard(bl_id)
    except PortalError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))
