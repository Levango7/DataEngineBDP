"""Mock 结算仓储 - 内存字典实现."""
from __future__ import annotations

import uuid
from typing import Any, Optional

from asset_exchange.interfaces.settlement_repository import (
    SettlementRepository,
)
from asset_exchange.models.base import utc_now
from asset_exchange.models.settlement import Settlement, SettlementFilter


class MockSettlementRepository(SettlementRepository):
    """内存字典结算仓储."""

    def __init__(self) -> None:
        self._settlements: dict[str, Settlement] = {}

    async def save(self, settlement: Settlement) -> str:
        if not settlement.id:
            settlement.id = str(uuid.uuid4())
        now = utc_now()
        if settlement.id not in self._settlements:
            settlement.createdAt = now
        settlement.updatedAt = now
        self._settlements[settlement.id] = settlement
        return settlement.id

    async def get(self, settlement_id: str) -> Settlement:
        if settlement_id not in self._settlements:
            raise KeyError(f"结算记录不存在: {settlement_id}")
        return self._settlements[settlement_id]

    async def list(self, filter: SettlementFilter) -> list[Settlement]:
        result: list[Settlement] = []
        for s in self._settlements.values():
            if filter.assetId and s.assetId != filter.assetId:
                continue
            if filter.tenantId and s.tenantId != filter.tenantId:
                continue
            if filter.period and s.period != filter.period:
                continue
            if filter.status and s.status != filter.status:
                continue
            result.append(s)
        result.sort(key=lambda x: x.createdAt, reverse=True)
        return result[filter.offset : filter.offset + filter.limit]

    async def list_by_asset(self, asset_id: str) -> list[Settlement]:
        result = [
            s for s in self._settlements.values() if s.assetId == asset_id
        ]
        result.sort(key=lambda x: x.createdAt, reverse=True)
        return result

    async def find_by_asset_period(
        self, asset_id: str, period: str
    ) -> Optional[Settlement]:
        for s in self._settlements.values():
            if s.assetId == asset_id and s.period == period:
                return s
        return None

    async def update(self, settlement_id: str, **fields: Any) -> Settlement:
        s = await self.get(settlement_id)
        for k, v in fields.items():
            if hasattr(s, k):
                setattr(s, k, v)
        s.updatedAt = utc_now()
        return s

    # ---------- 测试辅助 ----------

    def clear(self) -> None:
        self._settlements.clear()

    def __len__(self) -> int:
        return len(self._settlements)