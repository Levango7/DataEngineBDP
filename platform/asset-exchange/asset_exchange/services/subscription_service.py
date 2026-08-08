"""订阅管理业务逻辑.

负责订阅创建、审批、查询。
状态机对齐设计文档 §3：
    PENDING -> APPROVED (审批通过)
    PENDING -> REJECTED (审批驳回)
    APPROVED -> ACTIVE (开始生效)
    ACTIVE -> EXPIRED (到期)
"""

from __future__ import annotations

from datetime import timedelta
from typing import Optional

from asset_exchange.interfaces.subscription_repository import (
    SubscriptionRepository,
)
from asset_exchange.models.base import (
    AssetStatus,
    SubscriptionStatus,
    utc_now,
)
from asset_exchange.models.subscription import (
    SubscribeRequest,
    Subscription,
    SubscriptionFilter,
)
from asset_exchange.repositories import (
    AssetNotListedError,
    SubscriptionNotApprovableError,
    ValidationError,
)
from asset_exchange.services.asset_service import AssetService


class SubscriptionService:
    """订阅管理服务."""

    def __init__(
        self,
        sub_repo: SubscriptionRepository,
        asset_service: AssetService,
    ) -> None:
        self._sub_repo = sub_repo
        self._asset_service = asset_service

    async def subscribe(self, asset_id: str, req: SubscribeRequest) -> Subscription:
        """订阅资产.

        业务校验：
        - 资产必须为 LISTED 状态
        - 不允许订阅自己的资产（tenantId != subscriberId）

        Raises:
            AssetNotFoundError: 资产不存在。
            AssetNotListedError: 资产未上架。
            ValidationError: 业务校验失败。
        """
        asset = await self._asset_service.get_asset(asset_id)
        if asset.status != AssetStatus.LISTED:
            raise AssetNotListedError(asset_id, asset.status.value)
        if asset.tenantId == req.subscriberId:
            raise ValidationError("不允许订阅自己的资产")

        utc_now()
        sub = Subscription(
            assetId=asset_id,
            subscriberId=req.subscriberId,
            status=SubscriptionStatus.PENDING,
            period=req.period,
            pullConfig=req.pullConfig,
        )
        sub_id = await self._sub_repo.save(sub)
        return await self._sub_repo.get(sub_id)

    async def approve(
        self,
        subscription_id: str,
        approver_id: str,
        reason: Optional[str] = None,
    ) -> Subscription:
        """审批订阅（通过）.

        通过后状态置为 ACTIVE，并设置生效时间。
        同时资产订阅者数 +1。

        Raises:
            SubscriptionNotFoundError: 订阅不存在。
            SubscriptionNotApprovableError: 订阅非待审批状态。
        """
        sub = await self._sub_repo.get(subscription_id)
        if sub.status != SubscriptionStatus.PENDING:
            raise SubscriptionNotApprovableError(subscription_id, sub.status.value)

        now = utc_now()
        # 查询订阅请求中的 durationDays（从 pullConfig 中取，或默认 30）
        duration_days = sub.pullConfig.get("_durationDays", 30)
        sub.status = SubscriptionStatus.ACTIVE
        sub.approverId = approver_id
        sub.approvedAt = now
        sub.startTime = now
        sub.endTime = now + timedelta(days=duration_days)
        await self._sub_repo.save(sub)

        # 资产订阅者数 +1
        await self._asset_service._incr_subscriber(sub.assetId)

        return await self._sub_repo.get(subscription_id)

    async def reject(
        self,
        subscription_id: str,
        approver_id: str,
        reason: str,
    ) -> Subscription:
        """驳回订阅.

        Raises:
            SubscriptionNotFoundError: 订阅不存在。
            SubscriptionNotApprovableError: 订阅非待审批状态。
        """
        sub = await self._sub_repo.get(subscription_id)
        if sub.status != SubscriptionStatus.PENDING:
            raise SubscriptionNotApprovableError(subscription_id, sub.status.value)

        sub.status = SubscriptionStatus.REJECTED
        sub.approverId = approver_id
        sub.approvedAt = utc_now()
        sub.rejectReason = reason
        await self._sub_repo.save(sub)
        return await self._sub_repo.get(subscription_id)

    async def get_subscription(self, subscription_id: str) -> Subscription:
        return await self._sub_repo.get(subscription_id)

    async def list_subscriptions(self, filter: Optional[SubscriptionFilter] = None) -> list[Subscription]:
        return await self._sub_repo.list(filter or SubscriptionFilter())

    async def list_by_asset(self, asset_id: str) -> list[Subscription]:
        """列出某资产的所有订阅."""
        return await self._sub_repo.list_by_asset(asset_id)
