"""结算业务逻辑.

对齐设计文档 §4 变现与结算：
- 自动结算：按计费周期汇总某资产的所有计费记录
- 三种定价方式：按次（BY_CALL）/ 按量（BY_DATA）/ 订阅（SUBSCRIPTION）
- 自动计算提供方收益与平台抽成
- 结算状态机：PENDING -> SETTLED / FAILED
"""

from __future__ import annotations

from typing import Optional

from asset_exchange.interfaces.billing_repository import BillingRepository
from asset_exchange.interfaces.settlement_repository import (
    SettlementRepository,
)
from asset_exchange.models.base import SettlementStatus, utc_now
from asset_exchange.models.settlement import (
    Settlement,
    SettlementFilter,
    SettleRequest,
)


class SettlementService:
    """结算服务."""

    def __init__(
        self,
        settlement_repo: SettlementRepository,
        billing_repo: BillingRepository,
        asset_service,
        provider_share: float = 0.8,
        platform_share: float = 0.2,
    ) -> None:
        self._settlement_repo = settlement_repo
        self._billing_repo = billing_repo
        self._asset_service = asset_service
        self._providerShare = provider_share
        self._platformShare = platform_share

    async def settle(
        self,
        asset_id: str,
        req: Optional[SettleRequest] = None,
    ) -> Settlement:
        """对某资产执行结算.

        流程：
        1. 查询该资产在指定周期内的所有计费记录
        2. 汇总总金额
        3. 按分成比例计算提供方收益与平台抽成
        4. 创建/更新结算记录，状态置为 SETTLED

        Args:
            asset_id: 资产 ID。
            req: 结算请求（含周期与分成比例）。

        Returns:
            结算记录。
        """
        req = req or SettleRequest()
        # 周期：优先用请求中的，否则取当前年月
        period = req.period or utc_now().strftime("%Y-%m")
        # 分成比例：优先用请求中的，否则用配置默认值
        provider_share = req.providerShare if req.providerShare is not None else self._providerShare
        platform_share = req.platformShare if req.platformShare is not None else self._platformShare

        # 校验资产存在
        asset = await self._asset_service.get_asset(asset_id)

        # 查询该资产的所有计费记录（简化：不按周期过滤，全量结算）
        records = await self._billing_repo.list_by_asset(asset_id)
        # 按周期过滤
        period_records = [r for r in records if r.period == period]
        billing_record_ids = [r.id for r in period_records]
        total_amount = sum(r.amount for r in period_records)
        provider_revenue = round(total_amount * provider_share, 2)
        platform_revenue = round(total_amount * platform_share, 2)

        # 检查是否已存在该周期的结算记录
        existing = await self._settlement_repo.find_by_asset_period(asset_id, period)
        if existing:
            # 更新已有记录
            existing.totalAmount = total_amount
            existing.providerRevenue = provider_revenue
            existing.platformRevenue = platform_revenue
            existing.billingRecordIds = billing_record_ids
            existing.providerShare = provider_share
            existing.platformShare = platform_share
            existing.status = SettlementStatus.SETTLED
            existing.settledAt = utc_now()
            existing.errorMessage = None
            await self._settlement_repo.save(existing)
            return await self._settlement_repo.get(existing.id)

        # 创建新结算记录
        settlement = Settlement(
            assetId=asset_id,
            tenantId=asset.tenantId,
            period=period,
            status=SettlementStatus.SETTLED,
            totalAmount=total_amount,
            providerRevenue=provider_revenue,
            platformRevenue=platform_revenue,
            billingRecordIds=billing_record_ids,
            providerShare=provider_share,
            platformShare=platform_share,
            settledAt=utc_now(),
        )
        settlement_id = await self._settlement_repo.save(settlement)
        return await self._settlement_repo.get(settlement_id)

    async def get(self, settlement_id: str) -> Settlement:
        """获取结算记录."""
        return await self._settlement_repo.get(settlement_id)

    async def list(self, filter: Optional[SettlementFilter] = None) -> list[Settlement]:
        """列出结算记录."""
        return await self._settlement_repo.list(filter or SettlementFilter())

    async def list_by_asset(self, asset_id: str) -> list[Settlement]:
        """列出某资产的结算记录."""
        return await self._settlement_repo.list_by_asset(asset_id)
