"""业务线工作台路由."""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from business_portal.api.routers.deps import get_registry, status_for_error
from business_portal.models.workbench import Workbench
from business_portal.repositories import PortalError
from business_portal.services.registry import ServiceRegistry

router = APIRouter(prefix="/business-lines", tags=["workbench"])


@router.get(
    "/{bl_id}/workbench",
    response_model=Workbench,
    summary="业务线工作台",
)
async def get_workbench(
    bl_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> Workbench:
    """获取业务线工作台（待办 + 常用工具 + 最近任务）."""
    try:
        return await registry.workbenchService.get_workbench(bl_id)
    except PortalError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))