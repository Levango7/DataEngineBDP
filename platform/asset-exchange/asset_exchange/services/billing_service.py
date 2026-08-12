"""计量计费业务逻辑.

对齐设计文档 §4 变现与结算：
- 计费方式：按次 / 按月 / 按量 / 一次性买断
- 收益分成：默认提供方 80% / 平台 20%
- 内部租户间走内部结算：成本系数 0.3，仅记成本不真扣费

结算流程：
    消费方扣费 ──▶ 平台分账 ──▶ 提供方入账 ──▶ 月度对账单
"""

from __future__ import annotations

from typing import Optional

from asset_exchange.interfaces.billing_repository import BillingRepository
from asset_exchange.interfaces.subscription_repository import (
    SubscriptionRepository,
)
from asset_exchange.models.base import BillingMode, utc_now
from asset_exchange.models.billing import BillingRecord, BillingSummary
from asset_exchange.services.asset_service import AssetService


class BillingService:
    """计量计费服务."""

    def __init__(
        self,
        billing_repo: BillingRepository,
        asset_service: AssetService,
        sub_repo: SubscriptionRepository,
        provider_share: float = 0.8,
        platform_share: float = 0.2,
        internal_factor: float = 0.3,
    ) -> None:
        self._billing_repo = billing_repo
        self._asset_service = asset_service
        self._sub_repo = sub_repo
        self._providerShare = provider_share
        self._platformShare = platform_share
        self._internalFactor = internal_factor

    async def charge(
        self,
        subscription_id: str,
        usage: float,
        period: Optional[str] = None,
        delivery_id: Optional[str] = None,
    ) -> BillingRecord:
        """计费.

        Args:
            subscription_id: 订阅 ID。
            usage: 使用量（次数/行数/月数）。
            period: 计费周期，如 "2026-08"，不传则取当前年月。
            delivery_id: 关联交付 ID（按量计费时）。

        Returns:
            计费记录。
        """
        sub = await self._sub_repo.get(subscription_id)
        asset = await self._asset_service.get_asset(sub.assetId)

        # 计算金额
        unit_price = asset.pricing.price
        amount = self._calc_amount(asset.pricing.mode, unit_price, usage)

        # 判断是否内部租户间流通（简化：以 owner 与 subscriber 是否同租户前缀判定）
        # 这里以 owner == subscriber 视为内部（实际场景需更精细判定）
        is_internal = self._is_internal(asset.tenantId, sub.subscriberId)

        if is_internal:
            # 内部结算：成本系数 0.3，仅记成本不真扣费
            provider_revenue = amount * self._internalFactor * self._providerShare
            platform_revenue = amount * self._internalFactor * self._platformShare
        else:
            provider_revenue = amount * self._providerShare
            platform_revenue = amount * self._platformShare

        if period is None:
            period = utc_now().strftime("%Y-%m")

        record = BillingRecord(
            subscriptionId=subscription_id,
            assetId=asset.id,
            subscriberId=sub.subscriberId,
            tenantId=asset.tenantId,
            mode=asset.pricing.mode,
            usage=usage,
            unit=asset.pricing.unit,
            unitPrice=unit_price,
            amount=amount,
            providerRevenue=provider_revenue,
            platformRevenue=platform_revenue,
            period=period,
            isInternal=is_internal,
            deliveryId=delivery_id,
        )
        record_id = await self._billing_repo.save(record)
        return await self._billing_repo.get(record_id)

    def _calc_amount(self, mode: BillingMode, unit_price: float, usage: float) -> float:
        """计算订单金额.

        - BY_CALL:       按次计费，amount = unit_price * usage
        - BY_DATA:       按数据量计费（千行），amount = unit_price * usage / 1000
        - BY_TIME:       按时间计费（月），amount = unit_price * usage
        - SUBSCRIPTION:  订阅计费，amount = unit_price * usage（usage 为订阅期数）
        - ONE_TIME:      一次性买断，amount = unit_price
        """
        if mode == BillingMode.BY_CALL:
            return round(unit_price * usage, 2)
        elif mode == BillingMode.BY_DATA:
            return round(unit_price * usage / 1000, 2)
        elif mode == BillingMode.BY_TIME:
            return round(unit_price * usage, 2)
        elif mode == BillingMode.SUBSCRIPTION:
            # 订阅计费：单价 * 订阅期数
            return round(unit_price * usage, 2)
        elif mode == BillingMode.ONE_TIME:
            return round(unit_price, 2)
        else:
            return round(unit_price * usage, 2)

    def _is_internal(self, tenant_id: str, subscriber: str) -> bool:
        """判断是否内部租户间流通.

        判定规则：租户 ID 以 ":" 分隔组织前缀与租户 ID，
        同组织前缀视为内部（如 "org1:001" 与 "org1:002"），
        不同组织或无 ":" 分隔视为外部。
        实际场景需对接 L5.4 多租户计费。
        """
        if ":" not in tenant_id or ":" not in subscriber:
            return False
        owner_org = tenant_id.split(":", 1)[0]
        subscriber_org = subscriber.split(":", 1)[0]
        return owner_org == subscriber_org and tenant_id != subscriber

    async def list_by_asset(self, asset_id: str) -> BillingSummary:
        """列出某资产的计费记录汇总."""
        records = await self._billing_repo.list_by_asset(asset_id)
        sums = await self._billing_repo.sum_by_asset(asset_id)
        return BillingSummary(
            assetId=asset_id,
            totalAmount=sums["totalAmount"],
            totalProviderRevenue=sums["totalProviderRevenue"],
            totalPlatformRevenue=sums["totalPlatformRevenue"],
            records=records,
            recordCount=len(records),
        )

    async def list_by_subscription(self, subscription_id: str) -> list[BillingRecord]:
        """列出某订阅的计费记录."""
        return await self._billing_repo.list_by_subscription(subscription_id)
