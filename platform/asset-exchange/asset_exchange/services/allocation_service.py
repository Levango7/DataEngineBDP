"""分账业务逻辑.

对齐设计文档 §4 变现与结算：
- 分账到数据提供方与平台
- 分账比例可配置
- 分账状态机：PENDING -> ALLOCATED / FAILED
"""
from __future__ import annotations

from typing import Optional

from asset_exchange.interfaces.allocation_repository import (
    AllocationRepository,
)
from asset_exchange.interfaces.settlement_repository import (
    SettlementRepository,
)
from asset_exchange.models.base import AllocationStatus, utc_now
from asset_exchange.models.settlement import (
    Allocation,
    AllocationFilter,
    AllocateRequest,
)
from asset_exchange.repositories import AssetExchangeError


class AllocationService:
    """分账服务."""

    def __init__(
        self,
        allocation_repo: AllocationRepository,
        settlement_repo: SettlementRepository,
        platform_account_id: str = "platform-main",
    ) -> None:
        self._allocation_repo = allocation_repo
        self._settlement_repo = settlement_repo
        self._platformAccountId = platform_account_id

    async def allocate(
        self,
        settlement_id: str,
        req: Optional[AllocateRequest] = None,
    ) -> Allocation:
        """对某结算执行分账.

        流程：
        1. 获取结算记录
        2. 创建分账记录：提供方金额 + 平台金额
        3. 状态置为 ALLOCATED

        Args:
            settlement_id: 结算 ID。
            req: 分账请求（含账户 ID）。

        Returns:
            分账记录。
        """
        req = req or AllocateRequest()
        settlement = await self._settlement_repo.get(settlement_id)

        # 检查是否已存在分账记录
        existing = await self._allocation_repo.list_by_settlement(settlement_id)
        if existing:
            # 更新已有记录
            alloc = existing[0]
            alloc.providerAmount = settlement.providerRevenue
            alloc.platformAmount = settlement.platformRevenue
            alloc.providerAccountId = req.providerAccountId or alloc.providerAccountId
            alloc.platformAccountId = (
                req.platformAccountId
                or alloc.platformAccountId
                or self._platformAccountId
            )
            alloc.status = AllocationStatus.ALLOCATED
            alloc.allocatedAt = utc_now()
            alloc.errorMessage = None
            await self._allocation_repo.save(alloc)
            return await self._allocation_repo.get(alloc.id)

        # 创建新分账记录
        allocation = Allocation(
            settlementId=settlement_id,
            assetId=settlement.assetId,
            status=AllocationStatus.ALLOCATED,
            providerAmount=settlement.providerRevenue,
            platformAmount=settlement.platformRevenue,
            providerAccountId=req.providerAccountId,
            platformAccountId=req.platformAccountId or self._platformAccountId,
            allocatedAt=utc_now(),
        )
        allocation_id = await self._allocation_repo.save(allocation)
        return await self._allocation_repo.get(allocation_id)

    async def get(self, allocation_id: str) -> Allocation:
        """获取分账记录."""
        return await self._allocation_repo.get(allocation_id)

    async def list(
        self, filter: Optional[AllocationFilter] = None
    ) -> list[Allocation]:
        """列出分账记录."""
        return await self._allocation_repo.list(filter or AllocationFilter())

    async def list_by_asset(self, asset_id: str) -> list[Allocation]:
        """列出某资产的分账记录."""
        return await self._allocation_repo.list_by_asset(asset_id)

    async def list_by_settlement(
        self, settlement_id: str
    ) -> list[Allocation]:
        """列出某结算的分账记录."""
        return await self._allocation_repo.list_by_settlement(settlement_id)