"""审计留痕模型.

对齐设计文档 §6 审计留痕：
- 全过程审计日志（登记/上架/流通/变现/分账）
- 不可篡改：基于哈希链（每条日志包含前一条的哈希）
- 与 Phase 1 SecurityFacade T021 集成
"""
from __future__ import annotations

from datetime import datetime
from typing import Any, Optional

from pydantic import BaseModel, Field

from asset_exchange.models.base import (
    AuditAction,
    AuditResult,
    TimestampMixin,
)


class AuditLog(TimestampMixin):
    """审计日志（不可篡改）.

    采用哈希链保证不可篡改：
    - 每条日志包含 prevHash（前一条日志的 hash）
    - 自身 hash = SHA256(prevHash + action + assetId + actorId + timestamp + payload)
    - 任何一条日志被修改，后续所有日志的 hash 校验都会失败
    """

    id: str = Field(default="", description="审计日志 ID")
    action: AuditAction = Field(..., description="审计动作")
    assetId: Optional[str] = Field(default=None, description="关联资产 ID")
    subscriptionId: Optional[str] = Field(
        default=None, description="关联订阅 ID"
    )
    settlementId: Optional[str] = Field(
        default=None, description="关联结算 ID"
    )
    actorId: str = Field(..., min_length=1, description="操作者 ID")
    actorRole: Optional[str] = Field(default=None, description="操作者角色")
    tenantId: Optional[str] = Field(
        default=None, description="租户 ID"
    )
    result: AuditResult = Field(
        default=AuditResult.SUCCESS, description="审计结果"
    )
    detail: dict[str, Any] = Field(
        default_factory=dict, description="审计详情"
    )
    # 不可篡改：哈希链
    prevHash: str = Field(default="", description="前一条日志的哈希")
    hash: str = Field(default="", description="本条日志的哈希")


class AuditLogFilter(BaseModel):
    """审计日志过滤条件."""

    assetId: Optional[str] = Field(default=None, description="按资产过滤")
    action: Optional[AuditAction] = Field(
        default=None, description="按动作过滤"
    )
    actorId: Optional[str] = Field(default=None, description="按操作者过滤")
    tenantId: Optional[str] = Field(default=None, description="按租户过滤")
    startTime: Optional[datetime] = Field(
        default=None, description="起始时间"
    )
    endTime: Optional[datetime] = Field(
        default=None, description="结束时间"
    )
    limit: int = Field(default=100, ge=1, le=1000)
    offset: int = Field(default=0, ge=0)


class AuditIntegrityReport(BaseModel):
    """审计日志完整性校验报告."""

    totalLogs: int = Field(default=0, ge=0, description="日志总数")
    verified: bool = Field(default=True, description="是否通过校验")
    brokenAt: Optional[str] = Field(
        default=None, description="首个断裂点的日志 ID"
    )
    message: str = Field(default="OK", description="校验消息")