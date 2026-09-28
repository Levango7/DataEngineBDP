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

    async def get_dashboard(self, bl_id: str, tenant_id: str | None = None) -> Dashboard:
        """获取业务线仪表盘（先校验业务线存在）.

        租户隔离：若传入 tenant_id，校验业务线归属该租户，防止跨租户访问仪表盘。
        """
        bl = await self._bl_store.get(bl_id)
        if tenant_id and getattr(bl, "tenantId", None) != tenant_id:
            from business_portal.repositories import BusinessLineNotFoundError

            raise BusinessLineNotFoundError(bl_id)
        return await self._dashboard_store.get_dashboard(bl_id)
