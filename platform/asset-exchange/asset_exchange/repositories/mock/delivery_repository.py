"""Mock 交付仓储 - 内存字典实现."""

from __future__ import annotations

from typing import Any, Optional
import uuid

from asset_exchange.interfaces.delivery_repository import DeliveryRepository
from asset_exchange.models.base import utc_now
from asset_exchange.models.delivery import Delivery
from asset_exchange.repositories import DeliveryNotFoundError


class MockDeliveryRepository(DeliveryRepository):
    """内存字典交付仓储."""

    def __init__(self) -> None:
        self._deliveries: dict[str, Delivery] = {}

    async def save(self, delivery: Delivery) -> str:
        if not delivery.id:
            delivery.id = str(uuid.uuid4())
        now = utc_now()
        if delivery.id not in self._deliveries:
            delivery.createdAt = now
        delivery.updatedAt = now
        self._deliveries[delivery.id] = delivery
        return delivery.id

    async def get(self, delivery_id: str) -> Delivery:
        if delivery_id not in self._deliveries:
            raise DeliveryNotFoundError(delivery_id)
        return self._deliveries[delivery_id]

    async def get_by_subscription(self, subscription_id: str) -> Optional[Delivery]:
        candidates = [d for d in self._deliveries.values() if d.subscriptionId == subscription_id]
        if not candidates:
            return None
        # 返回最新的一条
        candidates.sort(key=lambda x: x.createdAt, reverse=True)
        return candidates[0]

    async def update(self, delivery_id: str, **fields: Any) -> Delivery:
        d = await self.get(delivery_id)
        for k, v in fields.items():
            if hasattr(d, k):
                setattr(d, k, v)
        d.updatedAt = utc_now()
        return d

    async def list_by_subscription(self, subscription_id: str) -> list[Delivery]:
        result = [d for d in self._deliveries.values() if d.subscriptionId == subscription_id]
        result.sort(key=lambda x: x.createdAt, reverse=True)
        return result

    # ---------- 测试辅助 ----------

    def clear(self) -> None:
        """清空存储（测试用）."""
        self._deliveries.clear()

    def __len__(self) -> int:
        return len(self._deliveries)
