"""订阅仓储抽象接口."""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any

from asset_exchange.models.subscription import Subscription, SubscriptionFilter


class SubscriptionRepository(ABC):
    """订阅仓储抽象接口."""

    @abstractmethod
    async def save(self, subscription: Subscription) -> str:
        """保存订阅（新增或更新），返回 subscription_id."""
        ...

    @abstractmethod
    async def get(self, subscription_id: str) -> Subscription:
        """根据 ID 获取订阅.

        Raises:
            SubscriptionNotFoundError: 订阅不存在。
        """
        ...

    @abstractmethod
    async def list(self, filter: SubscriptionFilter) -> list[Subscription]:
        """按条件列出订阅."""
        ...

    @abstractmethod
    async def update(self, subscription_id: str, **fields: Any) -> Subscription:
        """更新订阅字段.

        Raises:
            SubscriptionNotFoundError: 订阅不存在。
        """
        ...

    @abstractmethod
    async def list_by_asset(self, asset_id: str) -> list[Subscription]:
        """列出某资产的所有订阅."""
        ...
