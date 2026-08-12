"""业务线门户仓储接口（依赖倒转）.

定义仓储层契约，由 repositories/mock 等具体实现落地。
"""

from __future__ import annotations

from abc import ABC, abstractmethod

from business_portal.models.business_line import (
    BusinessLine,
    BusinessLineFilter,
    BusinessLineUsage,
)
from business_portal.models.catalog import CatalogNode, CatalogTree
from business_portal.models.dashboard import Dashboard
from business_portal.models.report import Report, ReportFilter
from business_portal.models.workbench import Workbench


class BusinessLineStore(ABC):
    """业务线存储接口."""

    @abstractmethod
    async def create(self, bl: BusinessLine) -> BusinessLine:
        """创建业务线."""

    @abstractmethod
    async def get(self, bl_id: str) -> BusinessLine:
        """获取业务线（不存在抛 BusinessLineNotFoundError）."""

    @abstractmethod
    async def list(self, filter_: BusinessLineFilter) -> list[BusinessLine]:
        """按条件列出业务线."""

    @abstractmethod
    async def update(self, bl_id: str, patch: dict) -> BusinessLine:
        """更新业务线（部分字段）."""

    @abstractmethod
    async def delete(self, bl_id: str) -> None:
        """删除业务线."""

    @abstractmethod
    async def get_usage(self, bl_id: str) -> BusinessLineUsage:
        """获取业务线用量概览."""


class DashboardStore(ABC):
    """数据概览存储接口."""

    @abstractmethod
    async def get_dashboard(self, bl_id: str) -> Dashboard:
        """获取业务线仪表盘."""


class WorkbenchStore(ABC):
    """工作台存储接口."""

    @abstractmethod
    async def get_workbench(self, bl_id: str) -> Workbench:
        """获取业务线工作台."""


class CatalogStore(ABC):
    """数据目录存储接口（业务线隔离）."""

    @abstractmethod
    async def get_tree(self, bl_id: str) -> CatalogTree:
        """获取业务线数据目录树."""

    @abstractmethod
    async def add_node(self, node: CatalogNode) -> CatalogNode:
        """添加目录节点."""

    @abstractmethod
    async def remove_node(self, bl_id: str, node_id: str) -> None:
        """删除目录节点."""


class ReportStore(ABC):
    """BI 报表存储接口（业务线隔离）."""

    @abstractmethod
    async def create(self, report: Report) -> Report:
        """创建报表."""

    @abstractmethod
    async def get(self, bl_id: str, report_id: str) -> Report:
        """获取报表."""

    @abstractmethod
    async def list(self, filter_: ReportFilter) -> list[Report]:
        """列出报表."""

    @abstractmethod
    async def update(self, bl_id: str, report_id: str, patch: dict) -> Report:
        """更新报表."""

    @abstractmethod
    async def delete(self, bl_id: str, report_id: str) -> None:
        """删除报表."""
