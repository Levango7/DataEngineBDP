"""分账仓储抽象接口."""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any

from asset_exchange.models.settlement import Allocation, AllocationFilter


class AllocationRepository(ABC):
    """分账仓储抽象接口."""

    @abstractmethod
    async def save(self, allocation: Allocation) -> str:
        """保存分账记录，返回 allocation_id."""
        ...

    @abstractmethod
    async def get(self, allocation_id: str) -> Allocation:
        """根据 ID 获取分账记录."""
        ...

    @abstractmethod
    async def list(self, filter: AllocationFilter) -> list[Allocation]:
        """按条件列出分账记录."""
        ...

    @abstractmethod
    async def list_by_asset(self, asset_id: str) -> list[Allocation]:
        """列出某资产的分账记录."""
        ...

    @abstractmethod
    async def list_by_settlement(self, settlement_id: str) -> list[Allocation]:
        """列出某结算的分账记录."""
        ...

    @abstractmethod
    async def update(self, allocation_id: str, **fields: Any) -> Allocation:
        """更新分账字段."""
        ...
