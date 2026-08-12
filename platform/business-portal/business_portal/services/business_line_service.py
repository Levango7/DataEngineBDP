"""业务线服务.

封装业务线 CRUD + 用量概览，并强制多业务线隔离与权限隔离。
"""

from __future__ import annotations

from typing import Any
import uuid

from business_portal.interfaces.store import BusinessLineStore
from business_portal.models.business_line import (
    BusinessLine,
    BusinessLineFilter,
    BusinessLineUsage,
)
from business_portal.repositories import (
    PermissionDeniedError,
)


class BusinessLineService:
    """业务线服务."""

    def __init__(self, store: BusinessLineStore) -> None:
        self._store = store

    async def create_business_line(self, bl: BusinessLine) -> BusinessLine:
        """创建业务线."""
        if not bl.id:
            bl.id = str(uuid.uuid4())
        return await self._store.create(bl)

    async def get_business_line(self, bl_id: str, user_id: str | None = None) -> BusinessLine:
        """获取业务线详情（带权限校验）."""
        bl = await self._store.get(bl_id)
        # 权限隔离：若指定 user_id，必须在该业务线成员列表中
        # （ownerIds / memberIds 任一即可）
        if user_id and not self._has_access(bl, user_id):
            raise PermissionDeniedError(bl_id, user_id)
        return bl

    @staticmethod
    def _has_access(bl: BusinessLine, user_id: str) -> bool:
        """检查用户是否可访问该业务线（沿树继承 + 越级授权）."""
        return user_id in bl.ownerIds or user_id in bl.memberIds or user_id in bl.teamIds

    async def list_business_lines(self, filter_: BusinessLineFilter) -> list[BusinessLine]:
        """列出业务线（按 memberId 过滤即权限隔离）."""
        return await self._store.list(filter_)

    async def update_business_line(self, bl_id: str, patch: dict[str, Any], user_id: str | None = None) -> BusinessLine:
        """更新业务线（仅业务线管理员可操作）."""
        bl = await self._store.get(bl_id)
        if user_id and user_id not in bl.ownerIds:
            raise PermissionDeniedError(bl_id, user_id)
        return await self._store.update(bl_id, patch)

    async def delete_business_line(self, bl_id: str, user_id: str | None = None) -> None:
        """删除业务线（仅业务线管理员可操作）."""
        bl = await self._store.get(bl_id)
        if user_id and user_id not in bl.ownerIds:
            raise PermissionDeniedError(bl_id, user_id)
        await self._store.delete(bl_id)

    async def get_usage(self, bl_id: str) -> BusinessLineUsage:
        """获取业务线用量概览."""
        # 先校验业务线存在
        await self._store.get(bl_id)
        return await self._store.get_usage(bl_id)
