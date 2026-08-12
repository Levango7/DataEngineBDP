"""Schema 上下文构建器单测."""

from __future__ import annotations

from models import SchemaContext, TableSchema
import pytest
from schema_context import SchemaContextBuilder


@pytest.mark.asyncio
class TestSchemaContextBuilder:
    async def test_mock_tables(self, schemaBuilder: SchemaContextBuilder) -> None:
        tables = await schemaBuilder.fetchTables(useMock=True)
        assert len(tables) == 3
        names = {t.tableName for t in tables}
        assert names == {"orders", "users", "products"}

    async def test_mock_tables_filter_database(self, schemaBuilder: SchemaContextBuilder) -> None:
        tables = await schemaBuilder.fetchTables(database="default", useMock=True)
        assert len(tables) == 3
        tables_other = await schemaBuilder.fetchTables(database="other", useMock=True)
        assert len(tables_other) == 0

    async def test_build_context_with_query_match(self, schemaBuilder: SchemaContextBuilder) -> None:
        # 查询含 orders 表名
        ctx = await schemaBuilder.buildContext(query="查询 orders 表的订单数量", useMock=True)
        assert not ctx.isEmpty
        assert any(t.tableName == "orders" for t in ctx.tables)

    async def test_build_context_with_column_match(self, schemaBuilder: SchemaContextBuilder) -> None:
        # 查询含 user_id 列名
        ctx = await schemaBuilder.buildContext(query="按 user_id 统计", useMock=True)
        # 应匹配到含 user_id 列的表
        assert not ctx.isEmpty

    async def test_build_context_with_table_hints(self, schemaBuilder: SchemaContextBuilder) -> None:
        ctx = await schemaBuilder.buildContext(query="随便看看", tableHints=["users"], useMock=True)
        assert len(ctx.tables) == 1
        assert ctx.tables[0].tableName == "users"

    async def test_build_context_no_match_returns_all(self, schemaBuilder: SchemaContextBuilder) -> None:
        ctx = await schemaBuilder.buildContext(query="xyz12345", useMock=True)
        # 无匹配返回全部
        assert len(ctx.tables) == 3

    async def test_build_context_max_tables(self, schemaBuilder: SchemaContextBuilder) -> None:
        # 修改上限
        schemaBuilder.settings.maxTables = 1
        ctx = await schemaBuilder.buildContext(query="xyz", useMock=True)
        assert len(ctx.tables) <= 1

    async def test_catalog_unreachable_falls_back_mock(self, schemaBuilder: SchemaContextBuilder) -> None:
        # Catalog 不可达（默认 localhost:8082 无服务），应降级 Mock
        tables = await schemaBuilder.fetchTables()
        assert len(tables) == 3


class TestSchemaContextRender:
    def test_render_markdown(self) -> None:
        ctx = SchemaContext(
            database="default",
            tables=[
                TableSchema(
                    databaseName="default",
                    tableName="orders",
                    columns=[],
                    comment="订单表",
                )
            ],
        )
        md = SchemaContextBuilder.renderMarkdown(ctx)
        assert "default" in md
        assert "orders" in md
        assert "订单表" in md

    def test_render_markdown_empty(self) -> None:
        ctx = SchemaContext(tables=[])
        md = SchemaContextBuilder.renderMarkdown(ctx)
        assert "无可用" in md

    def test_render_ddl(self) -> None:
        ctx = SchemaContext(
            tables=[
                TableSchema(
                    databaseName="default",
                    tableName="orders",
                    columns=[],
                )
            ],
        )
        ddl = SchemaContextBuilder.renderDdl(ctx)
        assert "CREATE TABLE" in ddl
        assert "default.orders" in ddl

    def test_render_ddl_empty(self) -> None:
        ctx = SchemaContext(tables=[])
        ddl = SchemaContextBuilder.renderDdl(ctx)
        assert "无可用" in ddl
