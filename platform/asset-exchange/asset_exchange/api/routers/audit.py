"""审计日志路由.

端点：
    GET  /audit-logs                列出审计日志
    GET  /audit-logs/{id}           获取审计日志详情
    GET  /audit-logs/integrity      审计日志完整性校验
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status
from pydantic import BaseModel

from asset_exchange.api.routers.deps import get_registry
from asset_exchange.models.audit import (
    AuditIntegrityReport,
    AuditLog,
    AuditLogFilter,
)
from asset_exchange.models.base import AuditAction
from asset_exchange.services.registry import ServiceRegistry

router = APIRouter(prefix="/audit-logs", tags=["audit"])


@router.get(
    "",
    response_model=list[AuditLog],
    summary="列出审计日志",
)
async def list_audit_logs(
    assetId: str | None = Query(default=None, description="按资产过滤"),
    action: AuditAction | None = Query(default=None, description="按动作过滤"),
    actorId: str | None = Query(default=None, description="按操作者过滤"),
    tenantId: str | None = Query(default=None, description="按租户过滤"),
    limit: int = Query(default=100, ge=1, le=1000),
    offset: int = Query(default=0, ge=0),
    registry: ServiceRegistry = Depends(get_registry),
) -> list[AuditLog]:
    """列出审计日志（按时间升序）."""
    filter_ = AuditLogFilter(
        assetId=assetId,
        action=action,
        actorId=actorId,
        tenantId=tenantId,
        limit=limit,
        offset=offset,
    )
    return await registry.auditService.list(filter_)


@router.get(
    "/integrity",
    response_model=AuditIntegrityReport,
    summary="审计日志完整性校验",
)
async def verify_integrity(
    registry: ServiceRegistry = Depends(get_registry),
) -> AuditIntegrityReport:
    """校验审计日志哈希链完整性.

    Returns:
        完整性校验报告。
    """
    return await registry.auditService.verify_integrity()


@router.get(
    "/{log_id}",
    response_model=AuditLog,
    summary="审计日志详情",
)
async def get_audit_log(
    log_id: str,
    registry: ServiceRegistry = Depends(get_registry),
) -> AuditLog:
    """获取审计日志详情."""
    try:
        return await registry.auditService.get(log_id)
    except Exception as exc:
        raise HTTPException(status_code=404, detail=str(exc))