"""资产仓储抽象接口."""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, Optional

from asset_exchange.models.asset import Asset, AssetFilter


class AssetRepository(ABC):
    """资产仓储抽象接口.

    职责：资产的持久化、查询、删除、状态变更。
    实现：MockAssetRepository（内存字典）/ 未来可扩展 DB 实现。
    """

    @abstractmethod
    async def save(self, asset: Asset) -> str:
        """保存资产（新增或更新），返回 asset_id.

        Args:
            asset: 资产信息（id 可由实现生成或使用传入值）。

        Returns:
            资产 ID。
        """
        ...

    @abstractmethod
    async def get(self, asset_id: str) -> Asset:
        """根据 ID 获取资产详情.

        Raises:
            AssetNotFoundError: 资产不存在。
        """
        ...

    @abstractmethod
    async def list(self, filter: AssetFilter) -> list[Asset]:
        """按条件列出资产."""
        ...

    @abstractmethod
    async def delete(self, asset_id: str) -> None:
        """删除资产.

        Raises:
            AssetNotFoundError: 资产不存在。
        """
        ...

    @abstractmethod
    async def update(self, asset_id: str, **fields: Any) -> Asset:
        """更新资产字段（部分更新）.

        Raises:
            AssetNotFoundError: 资产不存在。
        """
        ...

    async def find_by_name(self, name: str) -> Optional[Asset]:
        """按名称查找资产（可选实现，默认基于 list）.

        Returns:
            资产信息或 None。
        """
        results = await self.list(AssetFilter(name=name, limit=1))
        return results[0] if results else None
