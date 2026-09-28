"""合同实体模型.

P-04 合同交付实体 — 合同核心数据模型。

字段说明：
    id:           合同唯一标识（UUID）
    tenantId:     租户 ID（多租户隔离）
    contractNo:   合同编号（业务可读，如 HT-2026-0001）
    partyA:       甲方（平台方/服务提供方）
    partyB:       乙方（客户/租户）
    startDate:    合同生效日期
    endDate:      合同到期日期
    status:       合同状态（草稿/生效/暂停/终止/到期）
    amount:       合同金额（元，含税）
    type:         合同类型（订阅/一次性/溢出计费）
    package:      套餐档位（basic/standard/flagship）
    terms:        合同条款（JSON，灵活扩展）
    attachments:  附件列表（合同扫描件/补充协议 URL）
    signedAt:     签署时间
    approvedBy:   审批人
"""

from __future__ import annotations

from datetime import date, datetime, timezone
from enum import Enum
from typing import Any
from uuid import uuid4

from pydantic import BaseModel, Field


def utc_now() -> datetime:
    """返回当前 UTC 时间."""
    return datetime.now(timezone.utc)


class ContractStatus(str, Enum):
    """合同状态.

    - draft:     草稿（待签署）
    - active:    生效中
    - suspended: 暂停（欠费/违规/运营暂停）
    - terminated:已终止（提前解约）
    - expired:   已到期（自然到期）
    """

    DRAFT = "draft"
    ACTIVE = "active"
    SUSPENDED = "suspended"
    TERMINATED = "terminated"
    EXPIRED = "expired"


class ContractType(str, Enum):
    """合同类型.

    - subscription:  订阅合同（按月/年付费，含套餐额度）
    - one_time:      一次性合同（项目制/交付制）
    - overflow:      溢出计费合同（按实际用量结算）
    - hybrid:        混合合同（订阅 + 溢出）
    """

    SUBSCRIPTION = "subscription"
    ONE_TIME = "one_time"
    OVERFLOW = "overflow"
    HYBRID = "hybrid"


class Contract(BaseModel):
    """合同实体（核心领域模型）."""

    id: str = Field(default_factory=lambda: str(uuid4()), description="合同唯一标识")
    tenantId: str = Field(..., description="租户 ID", min_length=1, max_length=64)
    contractNo: str = Field(..., description="合同编号", min_length=1, max_length=64)
    partyA: str = Field(..., description="甲方", min_length=1, max_length=128)
    partyB: str = Field(..., description="乙方", min_length=1, max_length=128)
    startDate: date = Field(..., description="合同生效日期")
    endDate: date = Field(..., description="合同到期日期")
    status: ContractStatus = Field(default=ContractStatus.DRAFT, description="合同状态")
    amount: float = Field(..., description="合同金额（元）", ge=0)
    type: ContractType = Field(default=ContractType.SUBSCRIPTION, description="合同类型")
    package: str = Field(default="standard", description="套餐档位")
    terms: dict[str, Any] = Field(default_factory=dict, description="合同条款（JSON）")
    attachments: list[str] = Field(default_factory=list, description="附件 URL 列表")
    signedAt: datetime | None = Field(default=None, description="签署时间")
    approvedBy: str | None = Field(default=None, description="审批人")
    createdAt: datetime = Field(default_factory=utc_now, description="创建时间")
    updatedAt: datetime = Field(default_factory=utc_now, description="更新时间")


# ---------------------------------------------------------------------------
# API 请求/响应模型
# ---------------------------------------------------------------------------
class ContractCreateRequest(BaseModel):
    """创建合同请求."""

    tenantId: str = Field(..., description="租户 ID", min_length=1, max_length=64)
    contractNo: str = Field(..., description="合同编号", min_length=1, max_length=64)
    partyA: str = Field(..., description="甲方", min_length=1, max_length=128)
    partyB: str = Field(..., description="乙方", min_length=1, max_length=128)
    startDate: date = Field(..., description="合同生效日期")
    endDate: date = Field(..., description="合同到期日期")
    amount: float = Field(..., description="合同金额（元）", ge=0)
    type: ContractType = Field(default=ContractType.SUBSCRIPTION, description="合同类型")
    package: str = Field(default="standard", description="套餐档位")
    terms: dict[str, Any] = Field(default_factory=dict, description="合同条款")
    attachments: list[str] = Field(default_factory=list, description="附件 URL 列表")


class ContractUpdateRequest(BaseModel):
    """更新合同请求（部分字段可更新）."""

    partyA: str | None = Field(default=None, description="甲方", max_length=128)
    partyB: str | None = Field(default=None, description="乙方", max_length=128)
    startDate: date | None = Field(default=None, description="合同生效日期")
    endDate: date | None = Field(default=None, description="合同到期日期")
    status: ContractStatus | None = Field(default=None, description="合同状态")
    amount: float | None = Field(default=None, description="合同金额", ge=0)
    type: ContractType | None = Field(default=None, description="合同类型")
    package: str | None = Field(default=None, description="套餐档位")
    terms: dict[str, Any] | None = Field(default=None, description="合同条款")
    attachments: list[str] | None = Field(default=None, description="附件 URL 列表")
    signedAt: datetime | None = Field(default=None, description="签署时间")
    approvedBy: str | None = Field(default=None, description="审批人")


class ContractResponse(BaseModel):
    """合同响应（对外 API 标准格式）."""

    code: int = Field(default=0, description="响应码，0=成功")
    message: str = Field(default="ok", description="响应消息")
    data: Contract | None = Field(default=None, description="合同数据")
