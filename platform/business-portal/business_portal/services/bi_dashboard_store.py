"""BI 看板仓储（/dashboards 契约的前端 BI 看板，区别于业务线概览 DashboardStore）.

存储：默认内存（线程安全），BP_BI_DASHBOARD_STORE=sqlite 时落盘
`data/business_portal_bi.db`（轻量负载可用，生产级数据库切换待做，
与 asset-exchange 同档定位）。

此前 Analyze.vue 因无此后端只能渲染 CSS 假图；本仓储为其提供
真实看板 CRUD（面板数据由创建者通过 panels[].data 提供，服务端
不篡改），实时指标仍走 realtime 端点聚合。
"""

from __future__ import annotations

import os
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

from pydantic import BaseModel, Field


class Panel(BaseModel):
    """看板组件."""

    id: str
    title: str
    type: str = Field(description="line | pie | bar | metric | funnel | table")
    config: dict = Field(default_factory=dict)
    data: Optional[dict] = None


class BiDashboard(BaseModel):
    """BI 看板."""

    id: str
    name: str
    description: Optional[str] = None
    panels: list[Panel] = Field(default_factory=list)
    createdAt: str
    updatedAt: str


class DashboardNotFoundError(Exception):
    """看板不存在."""


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


class InMemoryBiDashboardStore:
    """内存实现（默认）."""

    def __init__(self) -> None:
        self._items: dict[str, BiDashboard] = {}
        self._lock = threading.RLock()

    async def list(
        self, page: int, page_size: int, keyword: Optional[str] = None
    ) -> tuple[list[BiDashboard], int]:
        with self._lock:
            items = sorted(self._items.values(), key=lambda d: d.createdAt, reverse=True)
        if keyword:
            items = [d for d in items if keyword in d.name]
        total = len(items)
        start = (page - 1) * page_size
        return items[start : start + page_size], total

    async def get(self, dashboard_id: str) -> BiDashboard:
        with self._lock:
            item = self._items.get(dashboard_id)
        if item is None:
            raise DashboardNotFoundError(dashboard_id)
        return item

    async def create(self, data: dict[str, Any]) -> BiDashboard:
        import uuid as _uuid

        now = _now()
        item = BiDashboard(
            id=_uuid.uuid4().hex,
            name=data["name"],
            description=data.get("description"),
            panels=[Panel(**p) for p in data.get("panels", [])],
            createdAt=now,
            updatedAt=now,
        )
        with self._lock:
            self._items[item.id] = item
        return item

    async def update(self, dashboard_id: str, data: dict[str, Any]) -> BiDashboard:
        with self._lock:
            item = self._items.get(dashboard_id)
            if item is None:
                raise DashboardNotFoundError(dashboard_id)
            if "name" in data:
                item.name = data["name"]
            if "description" in data:
                item.description = data["description"]
            if "panels" in data:
                item.panels = [Panel(**p) for p in data["panels"]]
            item.updatedAt = _now()
            self._items[dashboard_id] = item
            return item

    async def delete(self, dashboard_id: str) -> None:
        with self._lock:
            if dashboard_id not in self._items:
                raise DashboardNotFoundError(dashboard_id)
            del self._items[dashboard_id]


class SqliteBiDashboardStore(InMemoryBiDashboardStore):
    """SQLite 实现：读路径走库，写路径双写内存缓存保并发语义."""

    def __init__(self, db_path: str) -> None:
        super().__init__()
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(db_path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute(
            """
            CREATE TABLE IF NOT EXISTS bi_dashboard (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT,
                panels TEXT NOT NULL DEFAULT '[]',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """
        )
        self._conn.commit()
        # 启动时载入已有数据
        for row in self._conn.execute("SELECT * FROM bi_dashboard"):
            import json as _json

            self._items[row["id"]] = BiDashboard(
                id=row["id"],
                name=row["name"],
                description=row["description"],
                panels=[Panel(**p) for p in _json.loads(row["panels"])],
                createdAt=row["created_at"],
                updatedAt=row["updated_at"],
            )

    def _persist(self, item: BiDashboard) -> None:
        import json as _json

        self._conn.execute(
            "INSERT OR REPLACE INTO bi_dashboard VALUES (?,?,?,?,?,?)",
            (
                item.id,
                item.name,
                item.description,
                _json.dumps([p.model_dump() for p in item.panels]),
                item.createdAt,
                item.updatedAt,
            ),
        )
        self._conn.commit()

    async def create(self, data: dict[str, Any]) -> BiDashboard:
        item = await super().create(data)
        self._persist(item)
        return item

    async def update(self, dashboard_id: str, data: dict[str, Any]) -> BiDashboard:
        item = await super().update(dashboard_id, data)
        self._persist(item)
        return item

    async def delete(self, dashboard_id: str) -> None:
        await super().delete(dashboard_id)
        self._conn.execute("DELETE FROM bi_dashboard WHERE id=?", (dashboard_id,))
        self._conn.commit()


def build_bi_dashboard_store() -> InMemoryBiDashboardStore:
    """按 BP_BI_DASHBOARD_STORE 构建仓储（memory | sqlite）."""
    mode = os.environ.get("BP_BI_DASHBOARD_STORE", "memory").strip().lower()
    if mode == "sqlite":
        db_path = os.environ.get(
            "BP_BI_DASHBOARD_DB", os.path.join("data", "business_portal_bi.db")
        )
        return SqliteBiDashboardStore(db_path)
    return InMemoryBiDashboardStore()


# 路由层类型别名（两种实现共用同一接口形态）
BiDashboardStore = InMemoryBiDashboardStore
