"""结算仓储抽象接口."""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, Optional

from asset_exchange.models.settlement import Settlement, SettlementFilter


class SettlementRepository(ABC):
    """结算仓储抽象接口."""

    @abstractmethod
    async def save(self, settlement: Settlement) -> str:
        """保存结算记录，返回 settlement_id."""
        ...

    @abstractmethod
    async def get(self, settlement_id: str) -> Settlement:
        """根据 ID 获取结算记录."""
        ...

    @abstractmethod
    async def list(self, filter: SettlementFilter) -> list[Settlement]:
        """按条件列出结算记录."""
        ...

    @abstractmethod
    async def list_by_asset(self, asset_id: str) -> list[Settlement]:
        """列出某资产的结算记录."""
        ...

    @abstractmethod
    async def find_by_asset_period(self, asset_id: str, period: str) -> Optional[Settlement]:
        """按资产与周期查找结算记录.

        Returns:
            结算记录或 None。
        """
        ...

    @abstractmethod
    async def update(self, settlement_id: str, **fields: Any) -> Settlement:
        """更新结算字段."""
        ...
