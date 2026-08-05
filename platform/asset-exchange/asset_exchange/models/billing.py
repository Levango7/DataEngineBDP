"""计量计费模型.

对齐设计文档 §4 变现与结算：
- 计费方式：按次 / 按月 / 按量 / 一次性买断
- 收益分成：默认提供方 80% / 平台 20%
- 内部租户间走内部结算：成本系数 0.3，仅记成本不真扣费
"""
from __future__ import annotations

from datetime import datetime
from typing import Optional

from pydantic import BaseModel, Field

from asset_exchange.models.base import BillingMode, TimestampMixin


class BillingRecord(TimestampMixin):
    """计费记录."""

    id: str = Field(default="", description="计费记录 ID")
    subscriptionId: str = Field(..., description="订阅 ID")
    assetId: str = Field(..., description="资产 ID")
    subscriberId: str = Field(..., description="订阅方租户 ID")
    owner: str = Field(..., description="提供方租户 ID")

    # 计费
    mode: BillingMode = Field(..., description="计费方式")
    usage: float = Field(default=0.0, ge=0, description="使用量（次数/行数/月数）")
    unit: str = Field(default="次", description="计费单位")
    unitPrice: float = Field(default=0.0, ge=0, description="单价")

    # 金额
    amount: float = Field(default=0.0, ge=0, description="订单金额（元）")
    providerRevenue: float = Field(
        default=0.0, ge=0, description="提供方收益（元）"
    )
    platformRevenue: float = Field(
        default=0.0, ge=0, description="平台抽成（元）"
    )

    # 周期
    period: str = Field(..., description="计费周期，如 2026-08")
    isInternal: bool = Field(
        default=False, description="是否内部租户间流通（内部结算）"
    )

    # 关联
    deliveryId: Optional[str] = Field(
        default=None, description="关联交付 ID（按量计费时）"
    )


class BillingSummary(BaseModel):
    """计费汇总."""

    assetId: str
    totalAmount: float = Field(default=0.0, ge=0, description="累计订单金额")
    totalProviderRevenue: float = Field(default=0.0, ge=0, description="提供方累计收益")
    totalPlatformRevenue: float = Field(default=0.0, ge=0, description="平台累计抽成")
    records: list[BillingRecord] = Field(default_factory=list)
    recordCount: int = Field(default=0, ge=0)