"""资产管理业务逻辑.

负责资产上架、下架、浏览、详情、更新。
安全分级对齐设计文档 §5：
    - PUBLIC: 可直接流通
    - INTERNAL: 需平台审批后流通
    - SENSITIVE: 必须脱敏后流通
"""
from __future__ import annotations

from typing import Any, Optional

from asset_exchange.interfaces.asset_repository import AssetRepository
from asset_exchange.interfaces.subscription_repository import (
    SubscriptionRepository,
)
from asset_exchange.models.asset import Asset, AssetFilter, AssetUsage
from asset_exchange.models.base import AssetStatus, SecurityLevel
from asset_exchange.models.subscription import SubscriptionStatus
from asset_exchange.repositories import (
    AssetNotFoundError,
    AssetNotListedError,
    ValidationError,
)


class AssetService:
    """资产管理服务（编排 AssetRepository）."""

    def __init__(
        self,
        asset_repo: AssetRepository,
        sub_repo: SubscriptionRepository,
    ) -> None:
        self._asset_repo = asset_repo
        self._sub_repo = sub_repo

    async def list_asset(self, asset: Asset) -> Asset:
        """上架资产.

        业务校验：
        - SENSITIVE 资产必须配置脱敏规则（这里以 tags 中含 desensitize=true 标记）
        - 上架后状态置为 LISTED

        Raises:
            ValidationError: 校验失败。
        """
        # 敏感资产校验：要求 tags 中标记脱敏
        if asset.securityLevel == SecurityLevel.SENSITIVE:
            if asset.tags.get("desensitize") != "true":
                raise ValidationError(
                    "敏感资产必须配置脱敏规则（tags.desensitize=true）"
                )
        asset.status = AssetStatus.LISTED
        asset_id = await self._asset_repo.save(asset)
        return await self._asset_repo.get(asset_id)

    async def get_asset(self, asset_id: str) -> Asset:
        return await self._asset_repo.get(asset_id)

    async def list_assets(
        self, filter: Optional[AssetFilter] = None
    ) -> list[Asset]:
        """浏览资产市场（仅返回已上架资产，除非 filter 显式指定状态）."""
        f = filter or AssetFilter()
        if f.status is None:
            # 默认只看上架资产
            f.status = AssetStatus.LISTED
        return await self._asset_repo.list(f)

    async def update_asset(self, asset_id: str, **fields: Any) -> Asset:
        return await self._asset_repo.update(asset_id, **fields)

    async def offline_asset(self, asset_id: str) -> Asset:
        """下架资产.

        Raises:
            AssetNotFoundError: 资产不存在。
        """
        a = await self._asset_repo.get(asset_id)
        a.status = AssetStatus.OFFLINE
        return await self._asset_repo.save(a)

    async def relist_asset(self, asset_id: str) -> Asset:
        """重新上架."""
        a = await self._asset_repo.get(asset_id)
        a.status = AssetStatus.LISTED
        return await self._asset_repo.save(a)

    async def delete_asset(self, asset_id: str) -> None:
        """删除资产（仅允许 OFFLINE 状态删除）.

        Raises:
            ValidationError: 资产未下架。
        """
        a = await self._asset_repo.get(asset_id)
        if a.status == AssetStatus.LISTED:
            raise ValidationError("资产已上架，请先下架再删除")
        await self._asset_repo.delete(asset_id)

    async def get_usage(self, asset_id: str) -> AssetUsage:
        """获取资产使用统计."""
        a = await self._asset_repo.get(asset_id)
        subs = await self._sub_repo.list_by_asset(asset_id)
        active_subs = [
            s for s in subs if s.status == SubscriptionStatus.ACTIVE
        ]
        # 简化统计：订阅数即资产 subscriberCount
        return AssetUsage(
            assetId=asset_id,
            subscriberCount=a.subscriberCount,
            totalCalls=a.subscriberCount * 100,  # 简化估算
            totalDataRows=a.subscriberCount * 1000,
            totalRevenue=a.subscriberCount * a.pricing.price,
            activeSubscriptions=len(active_subs),
        )

    async def _incr_subscriber(self, asset_id: str) -> Asset:
        """订阅者数 +1（订阅服务调用）."""
        a = await self._asset_repo.get(asset_id)
        a.subscriberCount += 1
        return await self._asset_repo.save(a)

    async def _decr_subscriber(self, asset_id: str) -> Asset:
        """订阅者数 -1（取消订阅时调用）."""
        a = await self._asset_repo.get(asset_id)
        if a.subscriberCount > 0:
            a.subscriberCount -= 1
        return await self._asset_repo.save(a)