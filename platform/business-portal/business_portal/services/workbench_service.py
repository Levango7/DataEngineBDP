"""工作台服务."""
from __future__ import annotations

from business_portal.interfaces.store import (
    BusinessLineStore,
    WorkbenchStore,
)
from business_portal.models.workbench import Workbench


class WorkbenchService:
    """工作台服务."""

    def __init__(self, bl_store: BusinessLineStore, workbench_store: WorkbenchStore) -> None:
        self._bl_store = bl_store
        self._workbench_store = workbench_store

    async def get_workbench(self, bl_id: str) -> Workbench:
        """获取业务线工作台."""
        await self._bl_store.get(bl_id)
        return await self._workbench_store.get_workbench(bl_id)