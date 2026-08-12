"""Mock 订阅仓储 - 内存字典实现."""

from __future__ import annotations

from typing import Any
import uuid

from asset_exchange.interfaces.subscription_repository import SubscriptionRepository
from asset_exchange.models.base import utc_now
from asset_exchange.models.subscription import Subscription, SubscriptionFilter
from asset_exchange.repositories import SubscriptionNotFoundError


class MockSubscriptionRepository(SubscriptionRepository):
    """内存字典订阅仓储."""

    def __init__(self) -> None:
        self._subs: dict[str, Subscription] = {}

    async def save(self, subscription: Subscription) -> str:
        if not subscription.id:
            subscription.id = str(uuid.uuid4())
        now = utc_now()
        if subscription.id not in self._subs:
            subscription.createdAt = now
        subscription.updatedAt = now
        self._subs[subscription.id] = subscription
        return subscription.id

    async def get(self, subscription_id: str) -> Subscription:
        if subscription_id not in self._subs:
            raise SubscriptionNotFoundError(subscription_id)
        return self._subs[subscription_id]

    async def list(self, filter: SubscriptionFilter) -> list[Subscription]:
        result: list[Subscription] = []
        for s in self._subs.values():
            if filter.assetId and s.assetId != filter.assetId:
                continue
            if filter.subscriberId and s.subscriberId != filter.subscriberId:
                continue
            if filter.status and s.status != filter.status:
                continue
            result.append(s)
        result.sort(key=lambda x: x.createdAt, reverse=True)
        return result[filter.offset : filter.offset + filter.limit]

    async def update(self, subscription_id: str, **fields: Any) -> Subscription:
        s = await self.get(subscription_id)
        for k, v in fields.items():
            if hasattr(s, k):
                setattr(s, k, v)
        s.updatedAt = utc_now()
        return s

    async def list_by_asset(self, asset_id: str) -> list[Subscription]:
        return [s for s in self._subs.values() if s.assetId == asset_id]

    # ---------- 测试辅助 ----------

    def clear(self) -> None:
        """清空存储（测试用）."""
        self._subs.clear()

    def __len__(self) -> int:
        return len(self._subs)
