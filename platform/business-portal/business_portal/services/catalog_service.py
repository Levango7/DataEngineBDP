"""数据目录服务（业务线隔离）."""

from __future__ import annotations

from business_portal.interfaces.store import (
    BusinessLineStore,
    CatalogStore,
)
from business_portal.models.catalog import CatalogNode, CatalogTree


class CatalogService:
    """数据目录服务."""

    def __init__(self, bl_store: BusinessLineStore, catalog_store: CatalogStore) -> None:
        self._bl_store = bl_store
        self._catalog_store = catalog_store

    async def get_tree(self, bl_id: str) -> CatalogTree:
        """获取业务线数据目录树."""
        await self._bl_store.get(bl_id)
        return await self._catalog_store.get_tree(bl_id)

    async def add_node(self, node: CatalogNode) -> CatalogNode:
        """添加目录节点（强制隔离：node.blId 必须与目标业务线一致）."""
        await self._bl_store.get(node.blId)
        return await self._catalog_store.add_node(node)

    async def remove_node(self, bl_id: str, node_id: str) -> None:
        """删除目录节点."""
        await self._bl_store.get(bl_id)
        await self._catalog_store.remove_node(bl_id, node_id)
