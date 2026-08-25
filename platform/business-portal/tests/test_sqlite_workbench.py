"""SQLite 仓储实现测试（工作台待办初始化语义）."""

from __future__ import annotations

import pytest

from business_portal.models.business_line import BusinessLine
from business_portal.repositories.sqlite import (
    SQLiteBusinessLineStore,
    SQLiteConnection,
    SQLiteWorkbenchStore,
)


def _make_stores(tmp_path):
    conn = SQLiteConnection(db_path=str(tmp_path / "bp-test.db"))
    conn.init_schema()
    return SQLiteBusinessLineStore(conn), SQLiteWorkbenchStore(conn), conn


def _todo_count(conn: SQLiteConnection, bl_id: str) -> int:
    cur = conn.conn.execute(
        "SELECT COUNT(*) AS c FROM workbench_todos WHERE bl_id = ?;",
        (bl_id,),
    )
    return cur.fetchone()["c"]


class TestWorkbenchGetPureRead:
    """GET 工作台必须为纯读：无数据返空态，不产生写入副作用."""

    @pytest.mark.asyncio
    async def test_get_without_data_returns_empty_and_writes_nothing(self, tmp_path):
        bl_store, wb_store, conn = _make_stores(tmp_path)
        wb = await wb_store.get_workbench("bl-x")
        assert wb.blId == "bl-x"
        assert wb.todos == []
        assert wb.tools
        assert wb.recentTasks
        assert _todo_count(conn, "bl-x") == 0

    @pytest.mark.asyncio
    async def test_repeated_get_does_not_duplicate_rows(self, tmp_path):
        bl_store, wb_store, conn = _make_stores(tmp_path)
        bl = BusinessLine(id="bl-1", name="风控线", tenantId="t-1")
        await bl_store.create(bl)
        first = await wb_store.get_workbench("bl-1")
        second = await wb_store.get_workbench("bl-1")
        third = await wb_store.get_workbench("bl-1")
        assert len(first.todos) == 3
        assert {t.id for t in second.todos} == {t.id for t in first.todos}
        assert {t.id for t in third.todos} == {t.id for t in first.todos}
        assert _todo_count(conn, "bl-1") == 3

    @pytest.mark.asyncio
    async def test_get_reflects_current_db_state(self, tmp_path):
        bl_store, wb_store, conn = _make_stores(tmp_path)
        bl = BusinessLine(id="bl-2", name="增长线", tenantId="t-1")
        await bl_store.create(bl)
        conn.conn.execute("DELETE FROM workbench_todos WHERE bl_id = 'bl-2' AND type = 'approval';")
        wb = await wb_store.get_workbench("bl-2")
        assert len(wb.todos) == 2
        assert all(t.type != "approval" for t in wb.todos)
