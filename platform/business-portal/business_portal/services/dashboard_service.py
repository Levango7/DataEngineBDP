"""数据概览服务."""
from __future__ import annotations

from business_portal.interfaces.store import (
    BusinessLineStore,
    DashboardStore,
)
from business_portal.models.dashboard import Dashboard


class DashboardService:
    """数据概览服务."""

    def __init__(self, bl_store: BusinessLineStore, dashboard_store: DashboardStore) -> None:
        self._bl_store = bl_store
        self._dashboard_store = dashboard_store

    async def get_dashboard(self, bl_id: str) -> Dashboard:
        """获取业务线仪表盘（先校验业务线存在）."""
        await self._bl_store.get(bl_id)
        return await self._dashboard_store.get_dashboard(bl_id)