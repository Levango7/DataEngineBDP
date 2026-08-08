"""SQLite 仓储实现 - 基于标准库 sqlite3.

提供 Business Portal 全套 SQLite 实现，用于开发与本地持久化。
生产环境可替换为 PostgreSQL 实现（接口契约一致）。

设计要点：
- 多业务线隔离：所有表带 bl_id 列 + 索引，跨业务线访问由服务层校验
- 复杂字段（嵌套对象 / dict / list）以 JSON 文本列存储
- 时间戳以 ISO 8601 字符串存储
- DashboardStore 沿用 Mock 聚合逻辑（基于 BusinessLineStore 计算）
- WorkbenchStore 持久化待办任务，工具/最近任务保留默认示例
"""

from __future__ import annotations

import json
from pathlib import Path
import sqlite3
from typing import Any
import uuid

from business_portal.interfaces.store import (
    BusinessLineStore,
    CatalogStore,
    DashboardStore,
    ReportStore,
    WorkbenchStore,
)
from business_portal.models.base import (
    BusinessLineStatus,
    CatalogNodeType,
    utc_now,
)
from business_portal.models.business_line import (
    Budget,
    BusinessLine,
    BusinessLineConfig,
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
from business_portal.models.report import (
    DataSourceRef,
    Report,
    ReportConfig,
    ReportFilter,
)
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

DEFAULT_DB_PATH = "data/business_portal.db"


# ---------------------------------------------------------------------------
# 连接管理
# ---------------------------------------------------------------------------


class SQLiteConnection:
    """SQLite 连接封装."""

    def __init__(self, db_path: str = DEFAULT_DB_PATH) -> None:
        path = Path(db_path)
        if path.parent and not path.parent.exists():
            path.parent.mkdir(parents=True, exist_ok=True)
        self.dbPath = db_path
        self._conn = sqlite3.connect(
            db_path,
            check_same_thread=False,
            isolation_level=None,
        )
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA foreign_keys = ON;")
        self._conn.execute("PRAGMA journal_mode = WAL;")

    @property
    def conn(self) -> sqlite3.Connection:
        return self._conn

    def close(self) -> None:
        self._conn.close()

    def init_schema(self) -> None:
        """初始化全部表 schema."""
        self._conn.executescript("""
            CREATE TABLE IF NOT EXISTS business_lines (
                id              TEXT PRIMARY KEY,
                name            TEXT NOT NULL,
                tenant_id       TEXT NOT NULL,
                description     TEXT,
                status          TEXT NOT NULL,
                budget_json     TEXT NOT NULL,
                config_json     TEXT NOT NULL,
                owner_ids_json  TEXT NOT NULL DEFAULT '[]',
                team_ids_json   TEXT NOT NULL DEFAULT '[]',
                member_ids_json TEXT NOT NULL DEFAULT '[]',
                created_at      TEXT NOT NULL,
                updated_at      TEXT NOT NULL,
                UNIQUE(tenant_id, name)
            );
            CREATE INDEX IF NOT EXISTS idx_bl_tenant ON business_lines(tenant_id);
            CREATE INDEX IF NOT EXISTS idx_bl_status ON business_lines(status);

            CREATE TABLE IF NOT EXISTS catalog_nodes (
                id              TEXT PRIMARY KEY,
                bl_id           TEXT NOT NULL,
                parent_id       TEXT,
                name            TEXT NOT NULL,
                type            TEXT NOT NULL,
                children_json   TEXT NOT NULL DEFAULT '[]',
                asset_count     INTEGER NOT NULL DEFAULT 0,
                description     TEXT,
                tags_json       TEXT NOT NULL DEFAULT '{}',
                extra_json      TEXT NOT NULL DEFAULT '{}',
                created_at      TEXT NOT NULL,
                updated_at      TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_catalog_bl ON catalog_nodes(bl_id);
            CREATE INDEX IF NOT EXISTS idx_catalog_parent ON catalog_nodes(parent_id);

            CREATE TABLE IF NOT EXISTS reports (
                id              TEXT PRIMARY KEY,
                bl_id           TEXT NOT NULL,
                name            TEXT NOT NULL,
                description     TEXT,
                status          TEXT NOT NULL,
                config_json     TEXT NOT NULL,
                datasource_json TEXT,
                creator_id      TEXT NOT NULL DEFAULT '',
                tags_json       TEXT NOT NULL DEFAULT '{}',
                created_at      TEXT NOT NULL,
                updated_at      TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_reports_bl ON reports(bl_id);
            CREATE INDEX IF NOT EXISTS idx_reports_status ON reports(status);

            CREATE TABLE IF NOT EXISTS workbench_todos (
                id              TEXT PRIMARY KEY,
                bl_id           TEXT NOT NULL,
                type            TEXT NOT NULL,
                title           TEXT NOT NULL,
                applicant       TEXT NOT NULL DEFAULT '',
                status          TEXT NOT NULL DEFAULT 'pending',
                priority        TEXT NOT NULL DEFAULT 'normal',
                extra_json      TEXT NOT NULL DEFAULT '{}',
                created_at      TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_todos_bl ON workbench_todos(bl_id);
            """)


# ---------------------------------------------------------------------------
# BusinessLineStore
# ---------------------------------------------------------------------------


class SQLiteBusinessLineStore(BusinessLineStore):
    """SQLite 业务线存储."""

    def __init__(self, conn: SQLiteConnection) -> None:
        self._conn = conn

    async def create(self, bl: BusinessLine) -> BusinessLine:
        try:
            self._conn.conn.execute(
                """
                INSERT INTO business_lines (
                    id, name, tenant_id, description, status,
                    budget_json, config_json, owner_ids_json, team_ids_json,
                    member_ids_json, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                """,
                (
                    bl.id,
                    bl.name,
                    bl.tenantId,
                    bl.description,
                    bl.status.value,
                    bl.budget.model_dump_json(),
                    bl.config.model_dump_json(),
                    json.dumps(bl.ownerIds),
                    json.dumps(bl.teamIds),
                    json.dumps(bl.memberIds),
                    bl.createdAt.isoformat(),
                    bl.updatedAt.isoformat(),
                ),
            )
        except sqlite3.IntegrityError as e:
            raise BusinessLineAlreadyExistsError(bl.name) from e
        return bl

    async def get(self, bl_id: str) -> BusinessLine:
        cur = self._conn.conn.execute("SELECT * FROM business_lines WHERE id = ?;", (bl_id,))
        row = cur.fetchone()
        if row is None:
            raise BusinessLineNotFoundError(bl_id)
        return self._row_to_bl(row)

    async def list(self, filter_: BusinessLineFilter) -> list[BusinessLine]:
        clauses: list[str] = []
        params: list[Any] = []
        if filter_.tenantId:
            clauses.append("tenant_id = ?")
            params.append(filter_.tenantId)
        if filter_.status:
            clauses.append("status = ?")
            params.append(filter_.status.value)
        if filter_.name:
            clauses.append("LOWER(name) LIKE ?")
            params.append(f"%{filter_.name.lower()}%")
        where = (" WHERE " + " AND ".join(clauses)) if clauses else ""
        sql = f"SELECT * FROM business_lines{where} ORDER BY created_at ASC LIMIT ? OFFSET ?;"
        params.extend([filter_.limit, filter_.offset])
        cur = self._conn.conn.execute(sql, params)
        rows = cur.fetchall()
        result = [self._row_to_bl(r) for r in rows]
        if filter_.memberId:
            result = [bl for bl in result if filter_.memberId in bl.memberIds]
        return result

    async def update(self, bl_id: str, patch: dict) -> BusinessLine:
        bl = await self.get(bl_id)
        # 名称变更需校验唯一性
        new_name = patch.get("name")
        if new_name and new_name != bl.name:
            cur = self._conn.conn.execute(
                "SELECT id FROM business_lines WHERE tenant_id = ? AND name = ? AND id != ?;",
                (bl.tenantId, new_name, bl_id),
            )
            if cur.fetchone() is not None:
                raise BusinessLineAlreadyExistsError(new_name)
        data = bl.model_dump()
        data.update(patch)
        data["updatedAt"] = utc_now()
        updated = BusinessLine(**data)
        try:
            self._conn.conn.execute(
                """
                UPDATE business_lines SET
                    name = ?, tenant_id = ?, description = ?, status = ?,
                    budget_json = ?, config_json = ?, owner_ids_json = ?,
                    team_ids_json = ?, member_ids_json = ?, updated_at = ?
                WHERE id = ?;
                """,
                (
                    updated.name,
                    updated.tenantId,
                    updated.description,
                    updated.status.value,
                    updated.budget.model_dump_json(),
                    updated.config.model_dump_json(),
                    json.dumps(updated.ownerIds),
                    json.dumps(updated.teamIds),
                    json.dumps(updated.memberIds),
                    updated.updatedAt.isoformat(),
                    bl_id,
                ),
            )
        except sqlite3.IntegrityError as e:
            raise BusinessLineAlreadyExistsError(updated.name) from e
        return updated

    async def delete(self, bl_id: str) -> None:
        cur = self._conn.conn.execute("DELETE FROM business_lines WHERE id = ?;", (bl_id,))
        if cur.rowcount == 0:
            raise BusinessLineNotFoundError(bl_id)

    async def get_usage(self, bl_id: str) -> BusinessLineUsage:
        bl = await self.get(bl_id)
        return BusinessLineUsage(
            blId=bl_id,
            projectCount=len(bl.teamIds) * 3,
            teamCount=len(bl.teamIds),
            memberCount=len(bl.memberIds),
            jobCount=120,
            jobSuccessToday=98,
            jobFailToday=4,
            storageUsed=12.5,
            costToday=bl.budget.used * 0.1,
            costMonth=bl.budget.used,
        )

    @staticmethod
    def _row_to_bl(row) -> BusinessLine:
        return BusinessLine(
            id=row["id"],
            name=row["name"],
            tenantId=row["tenant_id"],
            description=row["description"],
            status=BusinessLineStatus(row["status"]),
            budget=Budget.model_validate_json(row["budget_json"]),
            config=BusinessLineConfig.model_validate_json(row["config_json"]),
            ownerIds=json.loads(row["owner_ids_json"]),
            teamIds=json.loads(row["team_ids_json"]),
            memberIds=json.loads(row["member_ids_json"]),
            createdAt=row["created_at"],
            updatedAt=row["updated_at"],
        )


# ---------------------------------------------------------------------------
# DashboardStore - 沿用 Mock 聚合逻辑（基于 BusinessLineStore 计算）
# ---------------------------------------------------------------------------


class SQLiteDashboardStore(DashboardStore):
    """SQLite 仪表盘存储（聚合视图，基于 BusinessLineStore 计算）."""

    def __init__(self, bl_store: SQLiteBusinessLineStore) -> None:
        self._bl_store = bl_store

    async def get_dashboard(self, bl_id: str) -> Dashboard:
        usage = await self._bl_store.get_usage(bl_id)
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
        trends = [
            Trend(key="cpuTrend", label="CPU 趋势", unit="%", bars=[42, 55, 48, 67, 71, 63, 58]),
            Trend(key="memTrend", label="内存趋势", unit="%", bars=[50, 62, 60, 70, 75, 68, 65]),
            Trend(key="costTrend", label="成本趋势", unit="元", bars=[30, 40, 35, 50, 55, 48, 45]),
        ]
        realtime = [
            RealtimeMonitor(key="cpu", label="CPU 实时", status="ok", value=58.0, unit="%", threshold=80.0),
            RealtimeMonitor(key="mem", label="内存实时", status="ok", value=65.0, unit="%", threshold=85.0),
            RealtimeMonitor(key="jobFail", label="今日失败作业", status="warn", value=4.0, threshold=10.0),
        ]
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


# ---------------------------------------------------------------------------
# WorkbenchStore - 持久化待办，工具/最近任务保留默认示例
# ---------------------------------------------------------------------------


class SQLiteWorkbenchStore(WorkbenchStore):
    """SQLite 工作台存储."""

    def __init__(self, conn: SQLiteConnection) -> None:
        self._conn = conn

    async def get_workbench(self, bl_id: str) -> Workbench:
        cur = self._conn.conn.execute(
            "SELECT * FROM workbench_todos WHERE bl_id = ? ORDER BY created_at DESC;",
            (bl_id,),
        )
        rows = cur.fetchall()
        if rows:
            todos = [
                Task(
                    id=r["id"],
                    type=r["type"],
                    title=r["title"],
                    applicant=r["applicant"],
                    status=r["status"],
                    priority=r["priority"],
                    createdAt=r["created_at"],
                    extra=json.loads(r["extra_json"]),
                )
                for r in rows
            ]
        else:
            # 首次访问注入默认待办
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
            for t in todos:
                self._conn.conn.execute(
                    """
                    INSERT INTO workbench_todos (
                        id, bl_id, type, title, applicant, status, priority,
                        extra_json, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);
                    """,
                    (
                        t.id,
                        bl_id,
                        t.type,
                        t.title,
                        t.applicant,
                        t.status,
                        t.priority,
                        json.dumps(t.extra),
                        t.createdAt.isoformat(),
                    ),
                )

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


# ---------------------------------------------------------------------------
# CatalogStore
# ---------------------------------------------------------------------------


class SQLiteCatalogStore(CatalogStore):
    """SQLite 数据目录存储（业务线隔离）."""

    def __init__(self, conn: SQLiteConnection) -> None:
        self._conn = conn

    def _ensure_bl(self, bl_id: str) -> None:
        """首次访问时初始化默认目录树."""
        cur = self._conn.conn.execute("SELECT COUNT(*) AS c FROM catalog_nodes WHERE bl_id = ?;", (bl_id,))
        if cur.fetchone()["c"] > 0:
            return
        nodes = [
            CatalogNode(
                id=f"{bl_id}_db",
                blId=bl_id,
                parentId=None,
                name="default_db",
                type=CatalogNodeType.DATABASE,
                children=[f"{bl_id}_schema"],
                assetCount=0,
            ),
            CatalogNode(
                id=f"{bl_id}_schema",
                blId=bl_id,
                parentId=f"{bl_id}_db",
                name="public",
                type=CatalogNodeType.SCHEMA,
                children=[f"{bl_id}_t1", f"{bl_id}_t2"],
                assetCount=2,
            ),
            CatalogNode(
                id=f"{bl_id}_t1",
                blId=bl_id,
                parentId=f"{bl_id}_schema",
                name="user_label",
                type=CatalogNodeType.TABLE,
                assetCount=1,
                tags={"domain": "risk"},
            ),
            CatalogNode(
                id=f"{bl_id}_t2",
                blId=bl_id,
                parentId=f"{bl_id}_schema",
                name="event_log",
                type=CatalogNodeType.TABLE,
                assetCount=1,
                tags={"domain": "growth"},
            ),
        ]
        for n in nodes:
            self._insert_node(n)

    def _insert_node(self, node: CatalogNode) -> None:
        self._conn.conn.execute(
            """
            INSERT INTO catalog_nodes (
                id, bl_id, parent_id, name, type, children_json, asset_count,
                description, tags_json, extra_json, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
            """,
            (
                node.id,
                node.blId,
                node.parentId,
                node.name,
                node.type.value,
                json.dumps(node.children),
                node.assetCount,
                node.description,
                json.dumps(node.tags),
                json.dumps(node.extra),
                node.createdAt.isoformat(),
                node.updatedAt.isoformat(),
            ),
        )

    @staticmethod
    def _row_to_node(row) -> CatalogNode:
        return CatalogNode(
            id=row["id"],
            blId=row["bl_id"],
            parentId=row["parent_id"],
            name=row["name"],
            type=CatalogNodeType(row["type"]),
            children=json.loads(row["children_json"]),
            assetCount=row["asset_count"],
            description=row["description"],
            tags=json.loads(row["tags_json"]),
            extra=json.loads(row["extra_json"]),
            createdAt=row["created_at"],
            updatedAt=row["updated_at"],
        )

    async def get_tree(self, bl_id: str) -> CatalogTree:
        self._ensure_bl(bl_id)
        cur = self._conn.conn.execute("SELECT * FROM catalog_nodes WHERE bl_id = ?;", (bl_id,))
        nodes = [self._row_to_node(r) for r in cur.fetchall()]
        root_ids = [n.id for n in nodes if n.parentId is None]
        return CatalogTree(blId=bl_id, nodes=nodes, rootIds=root_ids)

    async def add_node(self, node: CatalogNode) -> CatalogNode:
        self._ensure_bl(node.blId)
        cur = self._conn.conn.execute("SELECT id FROM catalog_nodes WHERE id = ?;", (node.id,))
        if cur.fetchone() is not None:
            raise ValidationError(f"节点已存在: {node.id}")
        if node.parentId is not None:
            cur = self._conn.conn.execute("SELECT * FROM catalog_nodes WHERE id = ?;", (node.parentId,))
            parent_row = cur.fetchone()
            if parent_row is None:
                raise CatalogNodeNotFoundError(node.parentId)
            parent = self._row_to_node(parent_row)
            if parent.blId != node.blId:
                raise PermissionDeniedError(node.blId, "add_node")
            if node.id not in parent.children:
                parent.children.append(node.id)
                self._conn.conn.execute(
                    "UPDATE catalog_nodes SET children_json = ?, updated_at = ? WHERE id = ?;",
                    (json.dumps(parent.children), utc_now().isoformat(), parent.id),
                )
        self._insert_node(node)
        return node

    async def remove_node(self, bl_id: str, node_id: str) -> None:
        self._ensure_bl(bl_id)
        cur = self._conn.conn.execute(
            "SELECT * FROM catalog_nodes WHERE bl_id = ? AND id = ?;",
            (bl_id, node_id),
        )
        row = cur.fetchone()
        if row is None:
            raise CatalogNodeNotFoundError(node_id)
        node = self._row_to_node(row)
        # 从父节点 children 中移除
        if node.parentId:
            cur = self._conn.conn.execute("SELECT * FROM catalog_nodes WHERE id = ?;", (node.parentId,))
            parent_row = cur.fetchone()
            if parent_row is not None:
                parent = self._row_to_node(parent_row)
                if node_id in parent.children:
                    parent.children.remove(node_id)
                    self._conn.conn.execute(
                        "UPDATE catalog_nodes SET children_json = ?, updated_at = ? WHERE id = ?;",
                        (json.dumps(parent.children), utc_now().isoformat(), parent.id),
                    )
        # 递归删除子节点
        for child_id in list(node.children):
            await self.remove_node(bl_id, child_id)
        self._conn.conn.execute("DELETE FROM catalog_nodes WHERE id = ?;", (node_id,))


# ---------------------------------------------------------------------------
# ReportStore
# ---------------------------------------------------------------------------


class SQLiteReportStore(ReportStore):
    """SQLite BI 报表存储（业务线隔离）."""

    def __init__(self, conn: SQLiteConnection) -> None:
        self._conn = conn

    async def create(self, report: Report) -> Report:
        try:
            self._conn.conn.execute(
                """
                INSERT INTO reports (
                    id, bl_id, name, description, status, config_json,
                    datasource_json, creator_id, tags_json, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                """,
                (
                    report.id,
                    report.blId,
                    report.name,
                    report.description,
                    report.status.value,
                    report.config.model_dump_json(),
                    report.dataSource.model_dump_json() if report.dataSource else None,
                    report.creatorId,
                    json.dumps(report.tags),
                    report.createdAt.isoformat(),
                    report.updatedAt.isoformat(),
                ),
            )
        except sqlite3.IntegrityError as e:
            raise ValidationError(f"报表已存在: {report.id}") from e
        return report

    async def get(self, bl_id: str, report_id: str) -> Report:
        cur = self._conn.conn.execute("SELECT * FROM reports WHERE bl_id = ? AND id = ?;", (bl_id, report_id))
        row = cur.fetchone()
        if row is None:
            raise ReportNotFoundError(report_id)
        return self._row_to_report(row)

    async def list(self, filter_: ReportFilter) -> list[Report]:
        clauses: list[str] = ["bl_id = ?"]
        params: list[Any] = [filter_.blId]
        if filter_.status:
            clauses.append("status = ?")
            params.append(filter_.status.value)
        if filter_.name:
            clauses.append("LOWER(name) LIKE ?")
            params.append(f"%{filter_.name.lower()}%")
        if filter_.creatorId:
            clauses.append("creator_id = ?")
            params.append(filter_.creatorId)
        where = " WHERE " + " AND ".join(clauses)
        sql = f"SELECT * FROM reports{where} ORDER BY created_at ASC LIMIT ? OFFSET ?;"
        params.extend([filter_.limit, filter_.offset])
        cur = self._conn.conn.execute(sql, params)
        rows = cur.fetchall()
        result = [self._row_to_report(r) for r in rows]
        if filter_.type:
            result = [r for r in result if r.config.type == filter_.type]
        return result

    async def update(self, bl_id: str, report_id: str, patch: dict) -> Report:
        r = await self.get(bl_id, report_id)
        data = r.model_dump()
        if "config" in patch:
            cfg = data["config"]
            cfg.update(patch["config"])
            patch = {**patch, "config": cfg}
        data.update(patch)
        data["updatedAt"] = utc_now()
        updated = Report(**data)
        self._conn.conn.execute(
            """
            UPDATE reports SET
                name = ?, description = ?, status = ?, config_json = ?,
                datasource_json = ?, creator_id = ?, tags_json = ?, updated_at = ?
            WHERE bl_id = ? AND id = ?;
            """,
            (
                updated.name,
                updated.description,
                updated.status.value,
                updated.config.model_dump_json(),
                updated.dataSource.model_dump_json() if updated.dataSource else None,
                updated.creatorId,
                json.dumps(updated.tags),
                updated.updatedAt.isoformat(),
                bl_id,
                report_id,
            ),
        )
        return updated

    async def delete(self, bl_id: str, report_id: str) -> None:
        cur = self._conn.conn.execute("DELETE FROM reports WHERE bl_id = ? AND id = ?;", (bl_id, report_id))
        if cur.rowcount == 0:
            raise ReportNotFoundError(report_id)

    @staticmethod
    def _row_to_report(row) -> Report:
        return Report(
            id=row["id"],
            blId=row["bl_id"],
            name=row["name"],
            description=row["description"],
            status=row["status"],
            config=ReportConfig.model_validate_json(row["config_json"]),
            dataSource=DataSourceRef.model_validate_json(row["datasource_json"]) if row["datasource_json"] else None,
            creatorId=row["creator_id"],
            tags=json.loads(row["tags_json"]),
            createdAt=row["created_at"],
            updatedAt=row["updated_at"],
        )


__all__ = [
    "SQLiteConnection",
    "SQLiteBusinessLineStore",
    "SQLiteDashboardStore",
    "SQLiteWorkbenchStore",
    "SQLiteCatalogStore",
    "SQLiteReportStore",
]
