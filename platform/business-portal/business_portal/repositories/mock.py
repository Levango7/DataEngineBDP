"""Mock 仓储实现（内存存储，测试与开发用）.

设计要点：
1. 多业务线隔离：所有数据按 blId 分桶，跨业务线访问默认拒绝。
2. 权限隔离：通过 memberIds 校验当前用户是否可见该业务线。
3. 业务线名称同租户下唯一。
4. MLflow 指标注入：MockBusinessLineStore 可选接收 MLflowMetricsProvider，
   启用后 jobCount/accuracy 从真实 MLflow 拉取，替换硬编码 120 / 0.875。
"""

from __future__ import annotations

import threading
from typing import Any, Optional
import uuid

from business_portal.interfaces.store import (
    BusinessLineStore,
    CatalogStore,
    DashboardStore,
    ReportStore,
    WorkbenchStore,
)
from business_portal.models.base import (
    CatalogNodeType,
    utc_now,
)
from business_portal.models.business_line import (
    BusinessLine,
    BusinessLineFilter,
    BusinessLineUsage,
)
from business_portal.models.catalog import CatalogNode, CatalogTree
from business_portal.models.dashboard import (
    Dashboard,
    Kpi,
    RealtimeMonitor,
    TopProject,
    Trend,
)
from business_portal.models.report import Report, ReportFilter
from business_portal.models.workbench import (
    RecentTask,
    Task,
    Tool,
    Workbench,
)
from business_portal.repositories import (
    BusinessLineAlreadyExistsError,
    BusinessLineNotFoundError,
    CatalogNodeNotFoundError,
    PermissionDeniedError,
    ReportNotFoundError,
    ValidationError,
)


class _LockableDict:
    """带锁的字典（线程安全）."""

    def __init__(self) -> None:
        self._data: dict[str, Any] = {}
        self._lock = threading.RLock()

    def __getitem__(self, key: str) -> Any:
        with self._lock:
            return self._data[key]

    def __setitem__(self, key: str, value: Any) -> None:
        with self._lock:
            self._data[key] = value

    def __delitem__(self, key: str) -> None:
        with self._lock:
            del self._data[key]

    def __contains__(self, key: str) -> bool:
        with self._lock:
            return key in self._data

    def get(self, key: str, default: Any = None) -> Any:
        with self._lock:
            return self._data.get(key, default)

    def values(self) -> list[Any]:
        with self._lock:
            return list(self._data.values())

    def keys(self) -> list[str]:
        with self._lock:
            return list(self._data.keys())


class MockBusinessLineStore(BusinessLineStore):
    """Mock 业务线存储."""

    def __init__(
        self,
        mlflowProvider: Optional[Any] = None,
    ) -> None:
        """
        Args:
            mlflowProvider: 可选的 MLflowMetricsProvider 实例。
                注入后 get_usage 的 jobCount 从真实 MLflow 拉取，
                替换硬编码 jobCount=120。
        """
        self._store: _LockableDict = _LockableDict()
        # 名称索引：(tenantId, name) -> blId，保证同租户下名称唯一
        self._name_index: _LockableDict = _LockableDict()
        self._mlflowProvider = mlflowProvider

    async def create(self, bl: BusinessLine) -> BusinessLine:
        # 校验同租户下名称唯一
        name_key = f"{bl.tenantId}::{bl.name}"
        if name_key in self._name_index:
            raise BusinessLineAlreadyExistsError(bl.name)
        if bl.id in self._store:
            raise BusinessLineAlreadyExistsError(bl.id)
        self._store[bl.id] = bl
        self._name_index[name_key] = bl.id
        return bl

    async def get(self, bl_id: str) -> BusinessLine:
        bl = self._store.get(bl_id)
        if bl is None:
            raise BusinessLineNotFoundError(bl_id)
        return bl

    async def list(self, filter_: BusinessLineFilter) -> list[BusinessLine]:
        result: list[BusinessLine] = []
        for bl in self._store.values():
            if filter_.tenantId and bl.tenantId != filter_.tenantId:
                continue
            if filter_.status and bl.status != filter_.status:
                continue
            if filter_.name and filter_.name.lower() not in bl.name.lower():
                continue
            if filter_.memberId and filter_.memberId not in bl.memberIds:
                continue
            result.append(bl)
        result.sort(key=lambda x: x.createdAt)
        return result[filter_.offset : filter_.offset + filter_.limit]

    async def update(self, bl_id: str, patch: dict) -> BusinessLine:
        bl = await self.get(bl_id)
        # 名称变更需校验唯一性
        new_name = patch.get("name")
        if new_name and new_name != bl.name:
            name_key = f"{bl.tenantId}::{new_name}"
            if name_key in self._name_index and self._name_index[name_key] != bl_id:
                raise BusinessLineAlreadyExistsError(new_name)
            # 更新名称索引
            old_key = f"{bl.tenantId}::{bl.name}"
            if old_key in self._name_index:
                del self._name_index[old_key]
            self._name_index[name_key] = bl_id

        # 部分更新
        data = bl.model_dump()
        data.update(patch)
        data["updatedAt"] = utc_now()
        updated = BusinessLine(**data)
        self._store[bl_id] = updated
        return updated

    async def delete(self, bl_id: str) -> None:
        bl = await self.get(bl_id)
        name_key = f"{bl.tenantId}::{bl.name}"
        if name_key in self._name_index:
            del self._name_index[name_key]
        del self._store[bl_id]

    async def get_usage(self, bl_id: str) -> BusinessLineUsage:
        # Mock：返回基于业务线成员/团队数的概览
        bl = await self.get(bl_id)
        # 默认硬编码值（与原实现一致）
        jobCount = 120
        jobSuccessToday = 98
        jobFailToday = 4
        # 若注入了 MLflow 指标提供者，从真实 MLflow 拉取 jobCount
        if self._mlflowProvider is not None:
            try:
                mlflowJobCount = await self._mlflowProvider.getJobCount()
                # 用真实 MLflow run 总数替换硬编码 120
                jobCount = mlflowJobCount
                # 同步调整成功/失败数（保持比例）
                if jobCount > 0:
                    jobSuccessToday = int(jobCount * 0.82)
                    jobFailToday = max(0, jobCount - jobSuccessToday - int(jobCount * 0.15))
                else:
                    jobSuccessToday = 0
                    jobFailToday = 0
            except Exception:
                # MLflow 不可用时回退硬编码
                pass
        return BusinessLineUsage(
            blId=bl_id,
            projectCount=len(bl.teamIds) * 3,  # 假设每团队 3 个项目
            teamCount=len(bl.teamIds),
            memberCount=len(bl.memberIds),
            jobCount=jobCount,
            jobSuccessToday=jobSuccessToday,
            jobFailToday=jobFailToday,
            storageUsed=12.5,
            costToday=bl.budget.used * 0.1,
            costMonth=bl.budget.used,
        )


class MockDashboardStore(DashboardStore):
    """Mock 仪表盘存储."""

    def __init__(
        self,
        bl_store: MockBusinessLineStore,
        mlflowProvider: Optional[Any] = None,
    ) -> None:
        """
        Args:
            bl_store: 业务线存储
            mlflowProvider: 可选的 MLflowMetricsProvider。
                注入后仪表盘新增 accuracy KPI，从真实 MLflow best run 拉取。
        """
        self._bl_store = bl_store
        self._mlflowProvider = mlflowProvider

    async def get_dashboard(self, bl_id: str) -> Dashboard:
        usage = await self._bl_store.get_usage(bl_id)
        # 构造 KPI 卡片
        kpis = [
            Kpi(key="projectCount", label="数据项目", value=usage.projectCount, unit=""),
            Kpi(key="jobCount", label="调度作业", value=usage.jobCount, unit=""),
            Kpi(
                key="jobSuccessRate",
                label="作业成功率",
                value=round(usage.jobSuccessToday / max(1, usage.jobCount) * 100, 1),
                unit="%",
            ),
            Kpi(
                key="storageUsed",
                label="存储用量",
                value=usage.storageUsed,
                unit="TB",
            ),
            Kpi(key="costMonth", label="本月成本", value=usage.costMonth, unit="元"),
        ]
        # 若注入了 MLflow 指标提供者，新增 accuracy KPI（真实指标）
        accuracyValue: Optional[float] = None
        if self._mlflowProvider is not None:
            try:
                accuracyValue = await self._mlflowProvider.getAccuracy()
            except Exception:
                accuracyValue = None
        if accuracyValue is not None:
            kpis.append(
                Kpi(
                    key="accuracy",
                    label="模型准确率",
                    value=round(accuracyValue, 4),
                    unit="",
                    description="来自 MLflow best run 的真实指标",
                )
            )
        # 趋势图（近 7 日 CPU/内存）
        cpu_bars = [42, 55, 48, 67, 71, 63, 58]
        mem_bars = [50, 62, 60, 70, 75, 68, 65]
        cost_bars = [30, 40, 35, 50, 55, 48, 45]
        trends = [
            Trend(key="cpuTrend", label="CPU 趋势", unit="%", bars=cpu_bars),
            Trend(key="memTrend", label="内存趋势", unit="%", bars=mem_bars),
            Trend(key="costTrend", label="成本趋势", unit="元", bars=cost_bars),
        ]
        # 实时监控
        realtime = [
            RealtimeMonitor(key="cpu", label="CPU 实时", status="ok", value=58.0, unit="%", threshold=80.0),
            RealtimeMonitor(key="mem", label="内存实时", status="ok", value=65.0, unit="%", threshold=85.0),
            RealtimeMonitor(key="jobFail", label="今日失败作业", status="warn", value=4.0, threshold=10.0),
        ]
        # TopN 项目
        top_projects = [
            TopProject(projectId="p1", projectName="风控-主项目", cost=3200.0, usageRatio=0.78, jobCount=45),
            TopProject(projectId="p2", projectName="风控-特征工程", cost=2100.0, usageRatio=0.55, jobCount=32),
            TopProject(projectId="p3", projectName="风控-模型训练", cost=1800.0, usageRatio=0.42, jobCount=28),
        ]
        return Dashboard(
            blId=bl_id,
            kpis=kpis,
            trends=trends,
            realtime=realtime,
            topProjects=top_projects,
        )


class MockWorkbenchStore(WorkbenchStore):
    """Mock 工作台存储."""

    def __init__(self) -> None:
        self._todos: _LockableDict = _LockableDict()  # blId -> list[Task]

    async def get_workbench(self, bl_id: str) -> Workbench:
        todos = self._todos.get(bl_id, [])
        if not todos:
            todos = [
                Task(
                    id=str(uuid.uuid4()),
                    type="approval",
                    title="张三 申请 CPU 32核 / 内存 64G",
                    applicant="zhangsan",
                    priority="high",
                ),
                Task(
                    id=str(uuid.uuid4()),
                    type="share",
                    title="李四 申请共享 user_label 表到 营销项目",
                    applicant="lisi",
                    priority="normal",
                ),
                Task(
                    id=str(uuid.uuid4()),
                    type="alert",
                    title="项目 风控-模型训练 存储用量超过 80%",
                    applicant="system",
                    priority="urgent",
                ),
            ]
            self._todos[bl_id] = todos

        tools = [
            Tool(key="newProject", label="新建项目", icon="plus", url="/projects"),
            Tool(key="newJob", label="新建作业", icon="job", url="/develop"),
            Tool(key="newReport", label="新建报表", icon="chart", url="/analyze"),
            Tool(key="shareData", label="申请共享", icon="share", url="/ops-portal"),
            Tool(key="costReport", label="成本看板", icon="cost", url="/ops-portal"),
        ]
        recent_tasks = [
            RecentTask(id="j1", name="风控特征离线计算", kind="job", status="succeeded"),
            RecentTask(id="j2", name="营销实时画像", kind="job", status="running"),
            RecentTask(id="t1", name="风控-领域-1.3B 微调", kind="training", status="succeeded"),
            RecentTask(id="d1", name="风控模型部署", kind="deployment", status="running"),
        ]
        return Workbench(
            blId=bl_id,
            todos=todos,
            tools=tools,
            recentTasks=recent_tasks,
        )


class MockCatalogStore(CatalogStore):
    """Mock 数据目录存储（业务线隔离）."""

    def __init__(self) -> None:
        # blId -> {nodeId -> CatalogNode}
        self._trees: _LockableDict = _LockableDict()

    def _ensure_bl(self, bl_id: str) -> dict[str, CatalogNode]:
        if bl_id not in self._trees:
            # 初始化默认目录树
            root_db = CatalogNode(
                id=f"{bl_id}_db",
                blId=bl_id,
                parentId=None,
                name="default_db",
                type=CatalogNodeType.DATABASE,
                children=[f"{bl_id}_schema"],
                assetCount=0,
            )
            schema = CatalogNode(
                id=f"{bl_id}_schema",
                blId=bl_id,
                parentId=f"{bl_id}_db",
                name="public",
                type=CatalogNodeType.SCHEMA,
                children=[f"{bl_id}_t1", f"{bl_id}_t2"],
                assetCount=2,
            )
            t1 = CatalogNode(
                id=f"{bl_id}_t1",
                blId=bl_id,
                parentId=f"{bl_id}_schema",
                name="user_label",
                type=CatalogNodeType.TABLE,
                assetCount=1,
                tags={"domain": "risk"},
            )
            t2 = CatalogNode(
                id=f"{bl_id}_t2",
                blId=bl_id,
                parentId=f"{bl_id}_schema",
                name="event_log",
                type=CatalogNodeType.TABLE,
                assetCount=1,
                tags={"domain": "growth"},
            )
            self._trees[bl_id] = {
                root_db.id: root_db,
                schema.id: schema,
                t1.id: t1,
                t2.id: t2,
            }
        return self._trees[bl_id]

    async def get_tree(self, bl_id: str) -> CatalogTree:
        nodes_map = self._ensure_bl(bl_id)
        nodes = list(nodes_map.values())
        root_ids = [n.id for n in nodes if n.parentId is None]
        return CatalogTree(blId=bl_id, nodes=nodes, rootIds=root_ids)

    async def add_node(self, node: CatalogNode) -> CatalogNode:
        # 强制隔离：node.blId 必须与目标树一致
        nodes_map = self._ensure_bl(node.blId)
        if node.id in nodes_map:
            raise ValidationError(f"节点已存在: {node.id}")
        # 父节点必须存在且属于同一业务线
        if node.parentId is not None:
            if node.parentId not in nodes_map:
                raise CatalogNodeNotFoundError(node.parentId)
            parent = nodes_map[node.parentId]
            if parent.blId != node.blId:
                raise PermissionDeniedError(node.blId, "add_node")
            if node.id not in parent.children:
                parent.children.append(node.id)
                nodes_map[parent.id] = parent
        nodes_map[node.id] = node
        self._trees[node.blId] = nodes_map
        return node

    async def remove_node(self, bl_id: str, node_id: str) -> None:
        nodes_map = self._ensure_bl(bl_id)
        if node_id not in nodes_map:
            raise CatalogNodeNotFoundError(node_id)
        node = nodes_map[node_id]
        # 从父节点 children 中移除
        if node.parentId and node.parentId in nodes_map:
            parent = nodes_map[node.parentId]
            if node_id in parent.children:
                parent.children.remove(node_id)
                nodes_map[parent.id] = parent
        # 递归删除子节点
        for child_id in list(node.children):
            if child_id in nodes_map:
                await self.remove_node(bl_id, child_id)
        del nodes_map[node_id]
        self._trees[bl_id] = nodes_map


class MockReportStore(ReportStore):
    """Mock BI 报表存储（业务线隔离）."""

    def __init__(self) -> None:
        # blId -> {reportId -> Report}
        self._store: _LockableDict = _LockableDict()

    def _ensure_bl(self, bl_id: str) -> dict[str, Report]:
        if bl_id not in self._store:
            self._store[bl_id] = {}
        return self._store[bl_id]

    async def create(self, report: Report) -> Report:
        # 强制隔离：report.blId 决定存储桶
        reports_map = self._ensure_bl(report.blId)
        if report.id in reports_map:
            raise ValidationError(f"报表已存在: {report.id}")
        reports_map[report.id] = report
        self._store[report.blId] = reports_map
        return report

    async def get(self, bl_id: str, report_id: str) -> Report:
        reports_map = self._ensure_bl(bl_id)
        r = reports_map.get(report_id)
        if r is None:
            raise ReportNotFoundError(report_id)
        return r

    async def list(self, filter_: ReportFilter) -> list[Report]:
        # 强制隔离：只返回 filter_.blId 桶内的报表
        reports_map = self._ensure_bl(filter_.blId)
        result: list[Report] = []
        for r in reports_map.values():
            if filter_.status and r.status != filter_.status:
                continue
            if filter_.type and r.config.type != filter_.type:
                continue
            if filter_.name and filter_.name.lower() not in r.name.lower():
                continue
            if filter_.creatorId and r.creatorId != filter_.creatorId:
                continue
            result.append(r)
        result.sort(key=lambda x: x.createdAt)
        return result[filter_.offset : filter_.offset + filter_.limit]

    async def update(self, bl_id: str, report_id: str, patch: dict) -> Report:
        r = await self.get(bl_id, report_id)
        data = r.model_dump()
        # config 单独合并
        if "config" in patch:
            cfg = data["config"]
            cfg.update(patch["config"])
            patch = {**patch, "config": cfg}
        data.update(patch)
        data["updatedAt"] = utc_now()
        updated = Report(**data)
        reports_map = self._ensure_bl(bl_id)
        reports_map[report_id] = updated
        self._store[bl_id] = reports_map
        return updated

    async def delete(self, bl_id: str, report_id: str) -> None:
        reports_map = self._ensure_bl(bl_id)
        if report_id not in reports_map:
            raise ReportNotFoundError(report_id)
        del reports_map[report_id]
        self._store[bl_id] = reports_map


__all__ = [
    "MockBusinessLineStore",
    "MockCatalogStore",
    "MockDashboardStore",
    "MockReportStore",
    "MockWorkbenchStore",
]
