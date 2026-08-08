"""Mock 资产仓储 - 内存字典实现."""

from __future__ import annotations

from typing import Any
import uuid

from asset_exchange.interfaces.asset_repository import AssetRepository
from asset_exchange.models.asset import Asset, AssetFilter
from asset_exchange.models.base import utc_now
from asset_exchange.repositories import (
    AssetAlreadyExistsError,
    AssetNotFoundError,
)


class MockAssetRepository(AssetRepository):
    """内存字典资产仓储.

    线程安全说明：单进程内存态，配合 asyncio 单线程事件循环无需加锁。
    跨进程场景请使用 DB 实现。
    """

    def __init__(self) -> None:
        self._assets: dict[str, Asset] = {}
        # name -> asset_id 索引，保证同名资产唯一
        self._name_index: dict[str, str] = {}

    async def save(self, asset: Asset) -> str:
        # 若未带 id 则生成
        if not asset.id:
            asset.id = str(uuid.uuid4())
        # 同名校验（新增时）
        if asset.id not in self._assets and asset.name in self._name_index:
            raise AssetAlreadyExistsError(asset.name)
        # 写入
        now = utc_now()
        if asset.id not in self._assets:
            asset.createdAt = now
        asset.updatedAt = now
        self._assets[asset.id] = asset
        self._name_index[asset.name] = asset.id
        return asset.id

    async def get(self, asset_id: str) -> Asset:
        if asset_id not in self._assets:
            raise AssetNotFoundError(asset_id)
        return self._assets[asset_id]

    async def list(self, filter: AssetFilter) -> list[Asset]:
        result: list[Asset] = []
        for a in self._assets.values():
            if filter.name and filter.name not in a.name:
                continue
            if filter.type and a.type != filter.type:
                continue
            if filter.status and a.status != filter.status:
                continue
            if filter.securityLevel and a.securityLevel != filter.securityLevel:
                continue
            if filter.tenantId and a.tenantId != filter.tenantId:
                continue
            result.append(a)
        # 按 createdAt 倒序
        result.sort(key=lambda x: x.createdAt, reverse=True)
        return result[filter.offset : filter.offset + filter.limit]

    async def delete(self, asset_id: str) -> None:
        if asset_id not in self._assets:
            raise AssetNotFoundError(asset_id)
        a = self._assets.pop(asset_id)
        self._name_index.pop(a.name, None)

    async def update(self, asset_id: str, **fields: Any) -> Asset:
        a = await self.get(asset_id)
        # 名称变更需同步索引
        if "name" in fields and fields["name"] != a.name:
            if fields["name"] in self._name_index:
                raise AssetAlreadyExistsError(fields["name"])
            self._name_index.pop(a.name, None)
            self._name_index[fields["name"]] = asset_id
        for k, v in fields.items():
            if hasattr(a, k):
                setattr(a, k, v)
        a.updatedAt = utc_now()
        return a

    # ---------- 测试辅助 ----------

    def clear(self) -> None:
        """清空存储（测试用）."""
        self._assets.clear()
        self._name_index.clear()

    def __len__(self) -> int:
        return len(self._assets)
