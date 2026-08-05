"""计费仓储抽象接口."""
from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Optional

from asset_exchange.models.billing import BillingRecord


class BillingRepository(ABC):
    """计费仓储抽象接口."""

    @abstractmethod
    async def save(self, record: BillingRecord) -> str:
        """保存计费记录，返回 record_id."""
        ...

    @abstractmethod
    async def get(self, record_id: str) -> BillingRecord:
        """根据 ID 获取计费记录.

        Raises:
            AssetExchangeError: 记录不存在。
        """
        ...

    @abstractmethod
    async def list_by_asset(self, asset_id: str) -> list[BillingRecord]:
        """列出某资产的所有计费记录."""
        ...

    @abstractmethod
    async def list_by_subscription(
        self, subscription_id: str
    ) -> list[BillingRecord]:
        """列出某订阅的所有计费记录."""
        ...

    @abstractmethod
    async def sum_by_asset(self, asset_id: str) -> dict[str, float]:
        """汇总某资产的计费.

        Returns:
            {"totalAmount": ..., "totalProviderRevenue": ..., "totalPlatformRevenue": ...}
        """
        ...