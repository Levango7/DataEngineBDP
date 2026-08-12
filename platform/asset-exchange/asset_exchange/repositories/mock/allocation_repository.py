"""Mock 分账仓储 - 内存字典实现."""

from __future__ import annotations

from typing import Any
import uuid

from asset_exchange.interfaces.allocation_repository import (
    AllocationRepository,
)
from asset_exchange.models.base import utc_now
from asset_exchange.models.settlement import Allocation, AllocationFilter


class MockAllocationRepository(AllocationRepository):
    """内存字典分账仓储."""

    def __init__(self) -> None:
        self._allocations: dict[str, Allocation] = {}

    async def save(self, allocation: Allocation) -> str:
        if not allocation.id:
            allocation.id = str(uuid.uuid4())
        now = utc_now()
        if allocation.id not in self._allocations:
            allocation.createdAt = now
        allocation.updatedAt = now
        self._allocations[allocation.id] = allocation
        return allocation.id

    async def get(self, allocation_id: str) -> Allocation:
        if allocation_id not in self._allocations:
            raise KeyError(f"分账记录不存在: {allocation_id}")
        return self._allocations[allocation_id]

    async def list(self, filter: AllocationFilter) -> list[Allocation]:
        result: list[Allocation] = []
        for a in self._allocations.values():
            if filter.assetId and a.assetId != filter.assetId:
                continue
            if filter.settlementId and a.settlementId != filter.settlementId:
                continue
            if filter.status and a.status != filter.status:
                continue
            result.append(a)
        result.sort(key=lambda x: x.createdAt, reverse=True)
        return result[filter.offset : filter.offset + filter.limit]

    async def list_by_asset(self, asset_id: str) -> list[Allocation]:
        result = [a for a in self._allocations.values() if a.assetId == asset_id]
        result.sort(key=lambda x: x.createdAt, reverse=True)
        return result

    async def list_by_settlement(self, settlement_id: str) -> list[Allocation]:
        result = [a for a in self._allocations.values() if a.settlementId == settlement_id]
        result.sort(key=lambda x: x.createdAt, reverse=True)
        return result

    async def update(self, allocation_id: str, **fields: Any) -> Allocation:
        a = await self.get(allocation_id)
        for k, v in fields.items():
            if hasattr(a, k):
                setattr(a, k, v)
        a.updatedAt = utc_now()
        return a

    # ---------- 测试辅助 ----------

    def clear(self) -> None:
        self._allocations.clear()

    def __len__(self) -> int:
        return len(self._allocations)
