"""业务线管理路由."""

from __future__ import annotations

from typing import Any
import uuid

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel, Field

from business_portal.api.routers.deps import get_current_user, get_registry, status_for_error
from business_portal.models.base import BusinessLineStatus
from business_portal.models.business_line import (
    Budget,
    BusinessLine,
    BusinessLineConfig,
    BusinessLineFilter,
)
from business_portal.repositories import PortalError
from business_portal.services.registry import ServiceRegistry

router = APIRouter(prefix="/business-lines", tags=["business-lines"])


# ---------- 请求模型 ----------


class CreateBusinessLineRequest(BaseModel):
    """创建业务线请求."""

    name: str = Field(..., min_length=1, max_length=128)
    tenantId: str = Field(..., min_length=1)
    description: str | None = None
    budget: Budget = Field(default_factory=Budget)
    config: BusinessLineConfig = Field(default_factory=BusinessLineConfig)
    ownerIds: list[str] = Field(default_factory=list)
    teamIds: list[str] = Field(default_factory=list)
    memberIds: list[str] = Field(default_factory=list)


class UpdateBusinessLineRequest(BaseModel):
    """更新业务线请求（部分字段）."""

    name: str | None = Field(default=None, min_length=1, max_length=128)
    description: str | None = None
    status: BusinessLineStatus | None = None
    budget: Budget | None = None
    config: BusinessLineConfig | None = None
    ownerIds: list[str] | None = None
    teamIds: list[str] | None = None
    memberIds: list[str] | None = None


# ---------- 路由 ----------


@router.post(
    "",
    response_model=BusinessLine,
    status_code=status.HTTP_201_CREATED,
    summary="创建业务线",
)
async def create_business_line(
    req: CreateBusinessLineRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> BusinessLine:
    """创建一条新业务线（顶层组织维度）."""
    bl = BusinessLine(
        id=str(uuid.uuid4()),
        name=req.name,
        tenantId=req.tenantId,
        description=req.description,
        budget=req.budget,
        config=req.config,
        ownerIds=req.ownerIds,
        teamIds=req.teamIds,
        memberIds=req.memberIds,
    )
    try:
        return await registry.businessLineService.create_business_line(bl)
    except PortalError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.get(
    "",
    response_model=list[BusinessLine],
    summary="列出业务线",
)
async def list_business_lines(
    tenantId: str | None = Query(default=None, description="按租户过滤"),
    status_: BusinessLineStatus | None = Query(default=None, alias="status", description="按状态过滤"),
    name: str | None = Query(default=None, description="名称模糊匹配"),
    memberId: str | None = Query(default=None, description="按成员过滤（权限隔离：仅返回该成员可见的业务线）"),
    limit: int = Query(default=100, ge=1, le=1000),
    offset: int = Query(default=0, ge=0),
    registry: ServiceRegistry = Depends(get_registry),
) -> list[BusinessLine]:
    """按条件列出业务线."""
    filter_ = BusinessLineFilter(
        tenantId=tenantId,
        status=status_,
        name=name,
        memberId=memberId,
        limit=limit,
        offset=offset,
    )
    return await registry.businessLineService.list_business_lines(filter_)


@router.get(
    "/{bl_id}",
    response_model=BusinessLine,
    summary="业务线详情",
)
async def get_business_line(
    bl_id: str,
    registry: ServiceRegistry = Depends(get_registry),
    user_id: str | None = Depends(get_current_user),
) -> BusinessLine:
    """获取业务线详情（带权限校验）."""
    try:
        return await registry.businessLineService.get_business_line(bl_id, user_id)
    except PortalError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.put(
    "/{bl_id}",
    response_model=BusinessLine,
    summary="更新业务线",
)
async def update_business_line(
    bl_id: str,
    req: UpdateBusinessLineRequest,
    registry: ServiceRegistry = Depends(get_registry),
    user_id: str | None = Depends(get_current_user),
) -> BusinessLine:
    """更新业务线（仅业务线管理员可操作）."""
    # 仅取非 None 字段
    patch: dict[str, Any] = {k: v for k, v in req.model_dump().items() if v is not None}
    try:
        return await registry.businessLineService.update_business_line(bl_id, patch, user_id)
    except PortalError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.delete(
    "/{bl_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="删除业务线",
)
async def delete_business_line(
    bl_id: str,
    registry: ServiceRegistry = Depends(get_registry),
    user_id: str | None = Depends(get_current_user),
) -> None:
    """删除业务线（仅业务线管理员可操作）."""
    try:
        await registry.businessLineService.delete_business_line(bl_id, user_id)
    except PortalError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))
