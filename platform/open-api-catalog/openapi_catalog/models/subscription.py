"""API 订阅模型."""

from __future__ import annotations

from openapi_catalog.models.base import SubscriptionStatus, TimestampMixin
from pydantic import BaseModel, Field


class APISubscription(TimestampMixin):
    """API 订阅记录.

    对应详细设计 §5 消费者订阅流程：
        消费者浏览目录 → 选中 API → 提交订阅申请(含用途/配额期望)
        → 提供方审批 → 经 Keycloak 发放 AK/SK(范围限定该 API)
        → 消费者持 AK/SK 调用 → 网关认证/限流/计量 → 后端服务
    """

    id: str = Field(..., description="订阅 ID")
    apiId: str = Field(..., description="API ID")
    subscriberId: str = Field(..., description="订阅者 ID")
    subscriberTenantId: str = Field(..., description="订阅者租户 ID")
    providerTenantId: str = Field(..., description="提供方租户 ID")

    purpose: str = Field(..., min_length=1, description="订阅用途")
    quotaExpect: int = Field(..., ge=1, description="期望配额（次/分钟）")

    status: SubscriptionStatus = Field(default=SubscriptionStatus.PENDING, description="订阅状态")

    # AK/SK（审批通过后发放）
    accessKey: str | None = Field(default=None, description="Access Key")
    secretKey: str | None = Field(default=None, description="Secret Key")

    # 审批信息
    approveReason: str | None = Field(default=None, description="审批意见")
    approvedBy: str | None = Field(default=None, description="审批人")

    # 实际配额
    grantedQuota: int = Field(default=0, ge=0, description="实际授予配额（次/分钟）")

    # 调用统计
    callCount: int = Field(default=0, ge=0, description="累计调用次数")
    errorCount: int = Field(default=0, ge=0, description="累计错误次数")
    lastCalledAt: str | None = Field(default=None, description="最后调用时间")


class SubscribeRequest(BaseModel):
    """订阅申请请求."""

    subscriberId: str = Field(..., description="订阅者 ID")
    subscriberTenantId: str = Field(..., description="订阅者租户 ID")
    purpose: str = Field(..., min_length=1, max_length=512, description="订阅用途")
    quotaExpect: int = Field(..., ge=1, le=100000, description="期望配额")


class ApproveRequest(BaseModel):
    """审批订阅请求."""

    approve: bool = Field(..., description="是否通过")
    reason: str | None = Field(default=None, description="审批意见")
    grantedQuota: int | None = Field(default=None, ge=1, description="实际授予配额")
    approver: str = Field(..., description="审批人")


class SubscriptionFilter(BaseModel):
    """订阅过滤条件."""

    apiId: str | None = None
    subscriberId: str | None = None
    subscriberTenantId: str | None = None
    status: SubscriptionStatus | None = None
    limit: int = Field(default=100, ge=1, le=1000)
    offset: int = Field(default=0, ge=0)
