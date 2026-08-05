"""BI 报表服务（业务线隔离）."""
from __future__ import annotations

import uuid
from typing import Any

from business_portal.interfaces.store import (
    BusinessLineStore,
    ReportStore,
)
from business_portal.models.report import Report, ReportFilter
from business_portal.repositories import PermissionDeniedError, ValidationError


class ReportService:
    """BI 报表服务."""

    def __init__(self, bl_store: BusinessLineStore, report_store: ReportStore) -> None:
        self._bl_store = bl_store
        self._report_store = report_store

    async def create_report(self, report: Report) -> Report:
        """创建报表（强制隔离：report.blId 决定归属）."""
        await self._bl_store.get(report.blId)
        if not report.id:
            report.id = str(uuid.uuid4())
        return await self._report_store.create(report)

    async def get_report(self, bl_id: str, report_id: str) -> Report:
        """获取报表（业务线隔离：bl_id 必须与报表归属一致）."""
        await self._bl_store.get(bl_id)
        report = await self._report_store.get(bl_id, report_id)
        # 二次校验：报表的 blId 必须与请求 bl_id 一致
        if report.blId != bl_id:
            raise PermissionDeniedError(bl_id, "get_report")
        return report

    async def list_reports(self, filter_: ReportFilter) -> list[Report]:
        """列出报表（按 blId 隔离）."""
        await self._bl_store.get(filter_.blId)
        return await self._report_store.list(filter_)

    async def update_report(
        self, bl_id: str, report_id: str, patch: dict[str, Any]
    ) -> Report:
        """更新报表."""
        await self._bl_store.get(bl_id)
        return await self._report_store.update(bl_id, report_id, patch)

    async def delete_report(self, bl_id: str, report_id: str) -> None:
        """删除报表."""
        await self._bl_store.get(bl_id)
        await self._report_store.delete(bl_id, report_id)