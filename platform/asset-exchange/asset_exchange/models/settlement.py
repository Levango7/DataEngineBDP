"""结算与分账模型.

对齐设计文档 §4 变现与结算：
- 自动结算：订阅费/按次费/按量费，按计费周期汇总
- 分账：分账到数据提供方与平台，比例可配置
- 结算状态机：PENDING -> SETTLED / FAILED
- 分账状态机：PENDING -> ALLOCATED / FAILED
"""
from __future__ import annotations

from datetime import datetime
from typing import Optional

from pydantic import BaseModel, Field

from asset_exchange.models.base import (
    AllocationStatus,
    BillingMode,
    SettlementStatus,
    TimestampMixin,
)


class Settlement(TimestampMixin):
    """结算记录.

    表示某资产在某计费周期内的结算汇总。
    """

    id: str = Field(default="", description="结算 ID")
    assetId: str = Field(..., description="资产 ID")
    tenantId: str = Field(..., description="提供方租户 ID")
    period: str = Field(..., description="计费周期，如 2026-08")
    status: SettlementStatus = Field(
        default=SettlementStatus.PENDING, description="结算状态"
    )
    # 结算金额
    totalAmount: float = Field(
        default=0.0, ge=0, description="结算总金额（元）"
    )
    providerRevenue: float = Field(
        default=0.0, ge=0, description="提供方收益（元）"
    )
    platformRevenue: float = Field(
        default=0.0, ge=0, description="平台抽成（元）"
    )
    # 关联
    billingRecordIds: list[str] = Field(
        default_factory=list, description="关联计费记录 ID 列表"
    )
    # 配置
    providerShare: float = Field(
        default=0.8, ge=0, le=1, description="提供方分成比例"
    )
    platformShare: float = Field(
        default=0.2, ge=0, le=1, description="平台分成比例"
    )
    # 时间
    settledAt: Optional[datetime] = Field(
        default=None, description="结算完成时间"
    )
    errorMessage: Optional[str] = Field(
        default=None, description="失败原因"
    )


class SettlementFilter(BaseModel):
    """结算过滤条件."""

    assetId: Optional[str] = Field(default=None, description="按资产过滤")
    tenantId: Optional[str] = Field(default=None, description="按租户过滤")
    period: Optional[str] = Field(default=None, description="按周期过滤")
    status: Optional[SettlementStatus] = Field(
        default=None, description="按状态过滤"
    )
    limit: int = Field(default=100, ge=1, le=1000)
    offset: int = Field(default=0, ge=0)


class Allocation(TimestampMixin):
    """分账记录.

    表示结算后向提供方与平台分配收益的记录。
    """

    id: str = Field(default="", description="分账 ID")
    settlementId: str = Field(..., description="关联结算 ID")
    assetId: str = Field(..., description="资产 ID")
    status: AllocationStatus = Field(
        default=AllocationStatus.PENDING, description="分账状态"
    )
    # 分账明细
    providerAmount: float = Field(
        default=0.0, ge=0, description="提供方分账金额"
    )
    platformAmount: float = Field(
        default=0.0, ge=0, description="平台分账金额"
    )
    providerAccountId: Optional[str] = Field(
        default=None, description="提供方账户 ID"
    )
    platformAccountId: Optional[str] = Field(
        default=None, description="平台账户 ID"
    )
    # 时间
    allocatedAt: Optional[datetime] = Field(
        default=None, description="分账完成时间"
    )
    errorMessage: Optional[str] = Field(
        default=None, description="失败原因"
    )


class AllocationFilter(BaseModel):
    """分账过滤条件."""

    assetId: Optional[str] = Field(default=None, description="按资产过滤")
    settlementId: Optional[str] = Field(
        default=None, description="按结算过滤"
    )
    status: Optional[AllocationStatus] = Field(
        default=None, description="按状态过滤"
    )
    limit: int = Field(default=100, ge=1, le=1000)
    offset: int = Field(default=0, ge=0)


class SettleRequest(BaseModel):
    """结算请求."""

    period: Optional[str] = Field(
        default=None, description="计费周期，不传则取当前年月"
    )
    providerShare: Optional[float] = Field(
        default=None, ge=0, le=1, description="提供方分成比例（不传用配置默认值）"
    )
    platformShare: Optional[float] = Field(
        default=None, ge=0, le=1, description="平台分成比例（不传用配置默认值）"
    )


class AllocateRequest(BaseModel):
    """分账请求."""

    providerAccountId: Optional[str] = Field(
        default=None, description="提供方账户 ID"
    )
    platformAccountId: Optional[str] = Field(
        default=None, description="平台账户 ID"
    )