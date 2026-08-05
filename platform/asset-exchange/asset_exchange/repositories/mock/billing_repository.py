"""Mock 计费仓储 - 内存字典实现."""
from __future__ import annotations

import uuid
from typing import Any

from asset_exchange.interfaces.billing_repository import BillingRepository
from asset_exchange.models.base import utc_now
from asset_exchange.models.billing import BillingRecord
from asset_exchange.repositories import AssetExchangeError


class MockBillingRepository(BillingRepository):
    """内存字典计费仓储."""

    def __init__(self) -> None:
        self._records: dict[str, BillingRecord] = {}

    async def save(self, record: BillingRecord) -> str:
        if not record.id:
            record.id = str(uuid.uuid4())
        now = utc_now()
        if record.id not in self._records:
            record.createdAt = now
        record.updatedAt = now
        self._records[record.id] = record
        return record.id

    async def get(self, record_id: str) -> BillingRecord:
        if record_id not in self._records:
            raise AssetExchangeError(f"计费记录不存在: {record_id}")
        return self._records[record_id]

    async def list_by_asset(self, asset_id: str) -> list[BillingRecord]:
        result = [
            r for r in self._records.values() if r.assetId == asset_id
        ]
        result.sort(key=lambda x: x.createdAt, reverse=True)
        return result

    async def list_by_subscription(
        self, subscription_id: str
    ) -> list[BillingRecord]:
        result = [
            r for r in self._records.values() if r.subscriptionId == subscription_id
        ]
        result.sort(key=lambda x: x.createdAt, reverse=True)
        return result

    async def sum_by_asset(self, asset_id: str) -> dict[str, float]:
        records = await self.list_by_asset(asset_id)
        return {
            "totalAmount": sum(r.amount for r in records),
            "totalProviderRevenue": sum(r.providerRevenue for r in records),
            "totalPlatformRevenue": sum(r.platformRevenue for r in records),
        }

    # ---------- 测试辅助 ----------

    def clear(self) -> None:
        """清空存储（测试用）."""
        self._records.clear()

    def __len__(self) -> int:
        return len(self._records)