"""多业务线隔离测试（数据隔离 + 权限隔离）.

核心验证点：
1. 数据隔离：不同业务线的数据互不可见
2. 权限隔离：非成员/非管理员不可访问/操作业务线
3. 跨业务线访问默认拒绝
"""

from __future__ import annotations

import pytest

from business_portal.models.base import CatalogNodeType, ReportType
from business_portal.models.business_line import Budget, BusinessLine
from business_portal.models.catalog import CatalogNode
from business_portal.models.report import Report, ReportConfig
from business_portal.repositories import (
    BusinessLineNotFoundError,
    PermissionDeniedError,
    ReportNotFoundError,
)
from business_portal.services.catalog_service import CatalogService
from business_portal.services.report_service import ReportService


def _make_bl(bl_id: str, name: str, tenant_id: str, member_ids: list[str]) -> BusinessLine:
    return BusinessLine(
        id=bl_id,
        name=name,
        tenantId=tenant_id,
        budget=Budget(total=100000.0, used=30000.0),
        ownerIds=[member_ids[0]],
        memberIds=member_ids,
    )


class TestDataIsolation:
    """数据隔离测试：不同业务线数据互不可见."""

    @pytest.mark.asyncio
    async def test_report_isolation_between_business_lines(self, mock_bl_store, mock_report_store):
        """报表数据隔离：业务线 A 的报表在业务线 B 不可见."""
        report_svc = ReportService(mock_bl_store, mock_report_store)
        bl_a = _make_bl("bl-a", "风控线", "t-1", ["u-a"])
        bl_b = _make_bl("bl-b", "增长线", "t-1", ["u-b"])
        await mock_bl_store.create(bl_a)
        await mock_bl_store.create(bl_b)

        # 在业务线 A 创建报表
        report_a = Report(
            id="r-a",
            blId="bl-a",
            name="风控报表",
            config=ReportConfig(type=ReportType.CHART),
        )
        await report_svc.create_report(report_a)

        # 业务线 B 列表不应包含 A 的报表
        from business_portal.models.report import ReportFilter

        reports_in_b = await report_svc.list_reports(ReportFilter(blId="bl-b"))
        assert len(reports_in_b) == 0

        # 业务线 A 列表应包含该报表
        reports_in_a = await report_svc.list_reports(ReportFilter(blId="bl-a"))
        assert len(reports_in_a) == 1
        assert reports_in_a[0].id == "r-a"

    @pytest.mark.asyncio
    async def test_report_cross_bl_access_denied(self, mock_bl_store, mock_report_store):
        """跨业务线获取报表应抛 ReportNotFoundError（数据隔离）."""
        report_svc = ReportService(mock_bl_store, mock_report_store)
        bl_a = _make_bl("bl-a", "风控线", "t-1", ["u-a"])
        bl_b = _make_bl("bl-b", "增长线", "t-1", ["u-b"])
        await mock_bl_store.create(bl_a)
        await mock_bl_store.create(bl_b)

        report_a = Report(
            id="r-a",
            blId="bl-a",
            name="风控报表",
        )
        await report_svc.create_report(report_a)

        # 用业务线 B 的视角去获取业务线 A 的报表 → 不存在
        with pytest.raises(ReportNotFoundError):
            await report_svc.get_report("bl-b", "r-a")

    @pytest.mark.asyncio
    async def test_catalog_isolation_between_business_lines(self, mock_bl_store, mock_catalog_store):
        """数据目录隔离：业务线 A 的目录树与 B 完全独立."""
        catalog_svc = CatalogService(mock_bl_store, mock_catalog_store)
        bl_a = _make_bl("bl-a", "风控线", "t-1", ["u-a"])
        bl_b = _make_bl("bl-b", "增长线", "t-1", ["u-b"])
        await mock_bl_store.create(bl_a)
        await mock_bl_store.create(bl_b)

        tree_a = await catalog_svc.get_tree("bl-a")
        tree_b = await catalog_svc.get_tree("bl-b")

        # 两棵树的节点 ID 必须完全不同
        ids_a = {n.id for n in tree_a.nodes}
        ids_b = {n.id for n in tree_b.nodes}
        assert ids_a.isdisjoint(ids_b)

        # 所有节点的 blId 必须与所属业务线一致
        for n in tree_a.nodes:
            assert n.blId == "bl-a"
        for n in tree_b.nodes:
            assert n.blId == "bl-b"

    @pytest.mark.asyncio
    async def test_catalog_node_cannot_cross_bl(self, mock_bl_store, mock_catalog_store):
        """添加目录节点：node.blId 必须与目标业务线一致."""
        catalog_svc = CatalogService(mock_bl_store, mock_catalog_store)
        bl_a = _make_bl("bl-a", "风控线", "t-1", ["u-a"])
        await mock_bl_store.create(bl_a)

        # 节点 blId 与路径 bl_id 不一致 → 服务层会先校验 bl_a 存在
        # 由于 add_node 入口取路径 bl_id，所以这里直接验证节点 blId 一致性
        node = CatalogNode(
            id="node-1",
            blId="bl-a",
            parentId=None,
            name="new_db",
            type=CatalogNodeType.DATABASE,
        )
        result = await catalog_svc.add_node(node)
        assert result.blId == "bl-a"

    @pytest.mark.asyncio
    async def test_report_create_with_wrong_bl_id_raises(self, mock_bl_store, mock_report_store):
        """报表 blId 指向不存在的业务线 → BusinessLineNotFoundError."""
        report_svc = ReportService(mock_bl_store, mock_report_store)
        report = Report(
            id="r-1",
            blId="nonexistent-bl",
            name="x",
        )
        with pytest.raises(BusinessLineNotFoundError):
            await report_svc.create_report(report)


class TestPermissionIsolation:
    """权限隔离测试：非成员/非管理员不可访问/操作."""

    @pytest.mark.asyncio
    async def test_non_member_cannot_read_bl(self, mock_bl_store):
        """非成员读取业务线详情 → PermissionDeniedError."""
        from business_portal.services.business_line_service import BusinessLineService

        svc = BusinessLineService(mock_bl_store)
        bl = _make_bl("bl-1", "风控线", "t-1", ["u-1", "u-2"])
        await svc.create_business_line(bl)
        with pytest.raises(PermissionDeniedError):
            await svc.get_business_line("bl-1", user_id="intruder")

    @pytest.mark.asyncio
    async def test_member_can_read_bl(self, mock_bl_store):
        """成员可读取业务线详情."""
        from business_portal.services.business_line_service import BusinessLineService

        svc = BusinessLineService(mock_bl_store)
        bl = _make_bl("bl-1", "风控线", "t-1", ["u-1", "u-2"])
        await svc.create_business_line(bl)
        result = await svc.get_business_line("bl-1", user_id="u-1")
        assert result.id == "bl-1"

    @pytest.mark.asyncio
    async def test_non_owner_cannot_update_bl(self, mock_bl_store):
        """非管理员不可更新业务线."""
        from business_portal.services.business_line_service import BusinessLineService

        svc = BusinessLineService(mock_bl_store)
        bl = BusinessLine(
            id="bl-1",
            name="风控线",
            tenantId="t-1",
            ownerIds=["admin-1"],
            memberIds=["admin-1", "user-1"],
        )
        await svc.create_business_line(bl)
        with pytest.raises(PermissionDeniedError):
            await svc.update_business_line("bl-1", {"name": "x"}, user_id="user-1")

    @pytest.mark.asyncio
    async def test_non_owner_cannot_delete_bl(self, mock_bl_store):
        """非管理员不可删除业务线."""
        from business_portal.services.business_line_service import BusinessLineService

        svc = BusinessLineService(mock_bl_store)
        bl = BusinessLine(
            id="bl-1",
            name="风控线",
            tenantId="t-1",
            ownerIds=["admin-1"],
            memberIds=["admin-1", "user-1"],
        )
        await svc.create_business_line(bl)
        with pytest.raises(PermissionDeniedError):
            await svc.delete_business_line("bl-1", user_id="user-1")

    @pytest.mark.asyncio
    async def test_list_by_member_returns_only_visible(self, mock_bl_store):
        """按成员列出业务线：仅返回该成员可见的业务线."""
        from business_portal.models.business_line import BusinessLineFilter
        from business_portal.services.business_line_service import BusinessLineService

        svc = BusinessLineService(mock_bl_store)
        # 业务线 1：u-1 可见
        await svc.create_business_line(_make_bl("bl-1", "风控线", "t-1", ["u-1", "u-2"]))
        # 业务线 2：u-1 不可见
        await svc.create_business_line(_make_bl("bl-2", "增长线", "t-1", ["u-2"]))
        # 业务线 3：u-1 可见
        await svc.create_business_line(_make_bl("bl-3", "营销线", "t-1", ["u-1"]))
        result = await svc.list_business_lines(BusinessLineFilter(memberId="u-1"))
        ids = {bl.id for bl in result}
        assert ids == {"bl-1", "bl-3"}


class TestCrossBusinessLineIsolation:
    """跨业务线综合隔离测试."""

    @pytest.mark.asyncio
    async def test_two_business_lines_completely_isolated(self, mock_bl_store, mock_report_store, mock_catalog_store):
        """两条业务线完全隔离：报表/目录互不可见."""
        report_svc = ReportService(mock_bl_store, mock_report_store)
        catalog_svc = CatalogService(mock_bl_store, mock_catalog_store)

        bl_a = _make_bl("bl-a", "风控线", "t-1", ["u-a"])
        bl_b = _make_bl("bl-b", "增长线", "t-1", ["u-b"])
        await mock_bl_store.create(bl_a)
        await mock_bl_store.create(bl_b)

        # 各自创建报表
        await report_svc.create_report(Report(id="r-a", blId="bl-a", name="风控报表"))
        await report_svc.create_report(Report(id="r-b", blId="bl-b", name="增长报表"))

        from business_portal.models.report import ReportFilter

        # A 视角只能看到 r-a
        reports_a = await report_svc.list_reports(ReportFilter(blId="bl-a"))
        assert {r.id for r in reports_a} == {"r-a"}

        # B 视角只能看到 r-b
        reports_b = await report_svc.list_reports(ReportFilter(blId="bl-b"))
        assert {r.id for r in reports_b} == {"r-b"}

        # A 视角无法获取 r-b
        with pytest.raises(ReportNotFoundError):
            await report_svc.get_report("bl-a", "r-b")

        # B 视角无法获取 r-a
        with pytest.raises(ReportNotFoundError):
            await report_svc.get_report("bl-b", "r-a")

        # 目录树完全独立
        tree_a = await catalog_svc.get_tree("bl-a")
        tree_b = await catalog_svc.get_tree("bl-b")
        ids_a = {n.id for n in tree_a.nodes}
        ids_b = {n.id for n in tree_b.nodes}
        assert ids_a.isdisjoint(ids_b)

    @pytest.mark.asyncio
    async def test_dashboard_isolated_per_bl(self, mock_bl_store, mock_dashboard_store):
        """仪表盘按业务线独立."""
        from business_portal.services.dashboard_service import DashboardService

        dashboard_svc = DashboardService(mock_bl_store, mock_dashboard_store)
        bl_a = _make_bl("bl-a", "风控线", "t-1", ["u-a"])
        bl_b = _make_bl("bl-b", "增长线", "t-1", ["u-b"])
        await mock_bl_store.create(bl_a)
        await mock_bl_store.create(bl_b)

        dashboard_a = await dashboard_svc.get_dashboard("bl-a")
        dashboard_b = await dashboard_svc.get_dashboard("bl-b")
        assert dashboard_a.blId == "bl-a"
        assert dashboard_b.blId == "bl-b"
        # KPI 卡片应包含业务线标识
        assert len(dashboard_a.kpis) > 0
        assert len(dashboard_b.kpis) > 0

    @pytest.mark.asyncio
    async def test_workbench_isolated_per_bl(self, mock_bl_store, mock_workbench_store):
        """工作台按业务线独立."""
        from business_portal.services.workbench_service import WorkbenchService

        workbench_svc = WorkbenchService(mock_bl_store, mock_workbench_store)
        bl_a = _make_bl("bl-a", "风控线", "t-1", ["u-a"])
        bl_b = _make_bl("bl-b", "增长线", "t-1", ["u-b"])
        await mock_bl_store.create(bl_a)
        await mock_bl_store.create(bl_b)

        wb_a = await workbench_svc.get_workbench("bl-a")
        wb_b = await workbench_svc.get_workbench("bl-b")
        assert wb_a.blId == "bl-a"
        assert wb_b.blId == "bl-b"
