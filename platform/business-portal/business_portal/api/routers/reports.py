"""业务线 BI 报表路由."""
from __future__ import annotations

import uuid
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel, Field

from business_portal.api.routers.deps import get_current_user, get_registry, status_for_error
from business_portal.models.base import ReportStatus, ReportType
from business_portal.models.report import (
    DataSourceRef,
    Report,
    ReportConfig,
    ReportFilter,
)
from business_portal.repositories import PortalError
from business_portal.services.registry import ServiceRegistry

router = APIRouter(prefix="/business-lines", tags=["reports"])


# ---------- 请求模型 ----------

class CreateReportRequest(BaseModel):
    """创建报表请求."""

    name: str = Field(..., min_length=1, max_length=256)
    description: str | None = None
    config: ReportConfig = Field(default_factory=ReportConfig)
    dataSource: DataSourceRef | None = None
    tags: dict[str, str] = Field(default_factory=dict)


class UpdateReportRequest(BaseModel):
    """更新报表请求."""

    name: str | None = Field(default=None, min_length=1, max_length=256)
    description: str | None = None
    status: ReportStatus | None = None
    config: ReportConfig | None = None
    dataSource: DataSourceRef | None = None
    tags: dict[str, str] | None = None


# ---------- 路由 ----------

@router.get(
    "/{bl_id}/reports",
    response_model=list[Report],
    summary="BI 报表列表",
)
async def list_reports(
    bl_id: str,
    status_: ReportStatus | None = Query(
        default=None, alias="status", description="按状态过滤"
    ),
    type: ReportType | None = Query(default=None, description="按类型过滤"),
    name: str | None = Query(default=None, description="名称模糊匹配"),
    creatorId: str | None = Query(default=None, description="按创建人过滤"),
    limit: int = Query(default=100, ge=1, le=1000),
    offset: int = Query(default=0, ge=0),
    registry: ServiceRegistry = Depends(get_registry),
) -> list[Report]:
    """列出业务线 BI 报表（业务线隔离）."""
    filter_ = ReportFilter(
        blId=bl_id,
        status=status_,
        type=type,
        name=name,
        creatorId=creatorId,
        limit=limit,
        offset=offset,
    )
    try:
        return await registry.reportService.list_reports(filter_)
    except PortalError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.post(
    "/{bl_id}/reports",
    response_model=Report,
    status_code=status.HTTP_201_CREATED,
    summary="创建 BI 报表",
)
async def create_report(
    bl_id: str,
    req: CreateReportRequest,
    registry: ServiceRegistry = Depends(get_registry),
    user_id: str | None = Depends(get_current_user),
) -> Report:
    """创建 BI 报表（强制隔离：blId 取路径 bl_id）."""
    report = Report(
        id=str(uuid.uuid4()),
        blId=bl_id,
        name=req.name,
        description=req.description,
        config=req.config,
        dataSource=req.dataSource,
        creatorId=user_id or "",
        tags=req.tags,
    )
    try:
        return await registry.reportService.create_report(report)
    except PortalError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.get(
    "/{bl_id}/reports/{report_id}",
    response_model=Report,
    summary="报表详情",
)
async def get_report(
    bl_id: str,
    report_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> Report:
    """获取报表详情（业务线隔离）."""
    try:
        return await registry.reportService.get_report(bl_id, report_id)
    except PortalError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.put(
    "/{bl_id}/reports/{report_id}",
    response_model=Report,
    summary="更新报表",
)
async def update_report(
    bl_id: str,
    report_id: str,
    req: UpdateReportRequest,
    registry: ServiceRegistry = Depends(get_registry),
) -> Report:
    """更新报表."""
    patch: dict[str, Any] = {k: v for k, v in req.model_dump().items() if v is not None}
    try:
        return await registry.reportService.update_report(bl_id, report_id, patch)
    except PortalError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))


@router.delete(
    "/{bl_id}/reports/{report_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="删除报表",
)
async def delete_report(
    bl_id: str,
    report_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> None:
    """删除报表."""
    try:
        await registry.reportService.delete_report(bl_id, report_id)
    except PortalError as exc:
        raise HTTPException(status_code=status_for_error(exc), detail=str(exc))