"""交付仓储抽象接口."""
from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, Optional

from asset_exchange.models.delivery import Delivery


class DeliveryRepository(ABC):
    """交付仓储抽象接口."""

    @abstractmethod
    async def save(self, delivery: Delivery) -> str:
        """保存交付记录，返回 delivery_id."""
        ...

    @abstractmethod
    async def get(self, delivery_id: str) -> Delivery:
        """根据 ID 获取交付.

        Raises:
            DeliveryNotFoundError: 交付不存在。
        """
        ...

    @abstractmethod
    async def get_by_subscription(
        self, subscription_id: str
    ) -> Optional[Delivery]:
        """根据订阅 ID 获取最新交付记录.

        Returns:
            交付记录或 None。
        """
        ...

    @abstractmethod
    async def update(self, delivery_id: str, **fields: Any) -> Delivery:
        """更新交付字段.

        Raises:
            DeliveryNotFoundError: 交付不存在。
        """
        ...

    @abstractmethod
    async def list_by_subscription(
        self, subscription_id: str
    ) -> list[Delivery]:
        """列出某订阅的所有交付记录."""
        ...