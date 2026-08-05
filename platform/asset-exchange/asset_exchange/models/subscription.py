"""订阅模型."""
from __future__ import annotations

from datetime import datetime
from typing import Any, Optional

from pydantic import BaseModel, Field

from asset_exchange.models.base import (
    SubscriptionStatus,
    TimestampMixin,
    utc_now,
)


class SubscriptionFilter(BaseModel):
    """订阅过滤条件."""

    assetId: Optional[str] = Field(default=None, description="按资产过滤")
    subscriberId: Optional[str] = Field(default=None, description="按订阅方过滤")
    status: Optional[SubscriptionStatus] = Field(
        default=None, description="按状态过滤"
    )
    limit: int = Field(default=100, ge=1, le=1000)
    offset: int = Field(default=0, ge=0)


class Subscription(TimestampMixin):
    """订阅记录.

    对齐设计文档 §3 流通模式 ①订阅。
    """

    id: str = Field(default="", description="订阅 ID")
    assetId: str = Field(..., description="资产 ID")
    subscriberId: str = Field(..., min_length=1, description="订阅方（租户 ID）")
    status: SubscriptionStatus = Field(
        default=SubscriptionStatus.PENDING, description="订阅状态"
    )

    # 时间
    startTime: Optional[datetime] = Field(default=None, description="生效开始时间")
    endTime: Optional[datetime] = Field(default=None, description="生效结束时间")
    period: Optional[str] = Field(
        default=None, description="订阅周期: monthly/quarterly/yearly"
    )

    # 拉取配置（订阅模式用）
    pullConfig: dict[str, Any] = Field(
        default_factory=dict, description="拉取配置（如 cron、增量字段）"
    )

    # 审批
    approverId: Optional[str] = Field(default=None, description="审批人 ID")
    approvedAt: Optional[datetime] = Field(default=None, description="审批时间")
    rejectReason: Optional[str] = Field(default=None, description="驳回原因")


class ApprovalRequest(BaseModel):
    """审批请求."""

    action: str = Field(..., description="审批动作: approve / reject")
    approverId: str = Field(..., description="审批人 ID")
    reason: Optional[str] = Field(default=None, description="驳回原因（reject 时填）")


class SubscribeRequest(BaseModel):
    """订阅请求."""

    subscriberId: str = Field(..., min_length=1, description="订阅方租户 ID")
    period: Optional[str] = Field(
        default="monthly", description="订阅周期: monthly/quarterly/yearly"
    )
    durationDays: int = Field(
        default=30, ge=1, le=3650, description="订阅时长（天）"
    )
    pullConfig: dict[str, Any] = Field(
        default_factory=dict, description="拉取配置"
    )