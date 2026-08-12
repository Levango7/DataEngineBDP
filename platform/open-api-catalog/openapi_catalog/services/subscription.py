"""订阅服务 - 处理消费者订阅申请、审批、激活、吊销.

对应详细设计 §5 消费者订阅与调用流程.
"""

from __future__ import annotations

from datetime import datetime
import uuid

from openapi_catalog.models import (
    APISubscription,
    ApproveRequest,
    SubscriptionFilter,
    SubscriptionStatus,
)
from openapi_catalog.repositories import (
    InvalidAPIKeyError,
    SubscriptionStatusError,
)
from openapi_catalog.repositories.mock import MockCatalogStore, generate_ak_sk


class SubscriptionService:
    """订阅服务."""

    def __init__(self, store: MockCatalogStore) -> None:
        self.store = store

    async def apply_subscription(self, api_id: str, request: APISubscription) -> APISubscription:
        """提交订阅申请.

        Args:
            api_id: API ID.
            request: 订阅申请（subscriberId/purpose/quotaExpect）.

        Returns:
            创建后的订阅记录（状态 PENDING）.
        """
        # 校验 API 存在
        await self.store.get_api(api_id)

        if not request.id:
            request.id = str(uuid.uuid4())
        request.apiId = api_id
        request.status = SubscriptionStatus.PENDING
        request.updatedAt = datetime.now()
        return await self.store.save_subscription(request)

    async def approve_subscription(self, subscription_id: str, request: ApproveRequest) -> APISubscription:
        """审批订阅.

        Args:
            subscription_id: 订阅 ID.
            request: 审批请求.

        Returns:
            更新后的订阅记录.
        """
        sub = await self.store.get_subscription(subscription_id)
        if sub.status != SubscriptionStatus.PENDING:
            raise SubscriptionStatusError(subscription_id, sub.status.value)

        if request.approve:
            sub.status = SubscriptionStatus.ACTIVE
            sub.approvedBy = request.approver
            sub.approveReason = request.reason
            sub.grantedQuota = request.grantedQuota if request.grantedQuota is not None else sub.quotaExpect
            # 发放 AK/SK
            ak, sk = generate_ak_sk()
            sub.accessKey = ak
            sub.secretKey = sk
        else:
            sub.status = SubscriptionStatus.REJECTED
            sub.approvedBy = request.approver
            sub.approveReason = request.reason

        sub.updatedAt = datetime.now()
        return await self.store.save_subscription(sub)

    async def get_subscription(self, subscription_id: str) -> APISubscription:
        """获取订阅详情."""
        return await self.store.get_subscription(subscription_id)

    async def list_subscriptions(self, filter_: SubscriptionFilter) -> list[APISubscription]:
        """列出订阅."""
        return await self.store.list_subscriptions(filter_)

    async def list_subscribers(self, api_id: str) -> list[APISubscription]:
        """列出某 API 的所有订阅者."""
        return await self.store.list_subscriptions(SubscriptionFilter(apiId=api_id, limit=1000))

    async def suspend_subscription(self, subscription_id: str) -> APISubscription:
        """暂停订阅."""
        sub = await self.store.get_subscription(subscription_id)
        if sub.status != SubscriptionStatus.ACTIVE:
            raise SubscriptionStatusError(subscription_id, sub.status.value)
        sub.status = SubscriptionStatus.SUSPENDED
        sub.updatedAt = datetime.now()
        return await self.store.save_subscription(sub)

    async def resume_subscription(self, subscription_id: str) -> APISubscription:
        """恢复订阅."""
        sub = await self.store.get_subscription(subscription_id)
        if sub.status != SubscriptionStatus.SUSPENDED:
            raise SubscriptionStatusError(subscription_id, sub.status.value)
        sub.status = SubscriptionStatus.ACTIVE
        sub.updatedAt = datetime.now()
        return await self.store.save_subscription(sub)

    async def revoke_subscription(self, subscription_id: str) -> APISubscription:
        """吊销订阅."""
        sub = await self.store.get_subscription(subscription_id)
        if sub.status not in (SubscriptionStatus.ACTIVE, SubscriptionStatus.SUSPENDED):
            raise SubscriptionStatusError(subscription_id, sub.status.value)
        sub.status = SubscriptionStatus.REVOKED
        # 吊销后清空 AK/SK
        sub.accessKey = None
        sub.secretKey = None
        sub.updatedAt = datetime.now()
        return await self.store.save_subscription(sub)

    async def authenticate(self, access_key: str) -> APISubscription:
        """根据 AK 鉴权.

        Args:
            access_key: Access Key.

        Returns:
            订阅记录.

        Raises:
            InvalidAPIKeyError: AK 无效或已吊销.
        """
        sub = await self.store.find_subscription_by_key(access_key)
        if sub is None or sub.status != SubscriptionStatus.ACTIVE:
            raise InvalidAPIKeyError()
        return sub
