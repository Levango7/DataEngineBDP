"""SQL 生成器单测（Mock 模式）."""

from __future__ import annotations

from models import (
    AggFunc,
    ColumnSchema,
    Intent,
    IntentType,
    SchemaContext,
    Slot,
    SlotFrame,
    SlotStatus,
    TableSchema,
)
import pytest
from sql_generator import MockSqlGenerator, createGenerator


def _mockCtx() -> SchemaContext:
    return SchemaContext(
        database="default",
        tables=[
            TableSchema(
                databaseName="default",
                tableName="orders",
                columns=[
                    ColumnSchema(name="order_id", type="bigint"),
                    ColumnSchema(name="user_id", type="bigint"),
                    ColumnSchema(name="amount", type="decimal"),
                    ColumnSchema(name="dt", type="date"),
                ],
                partitionKeys=["dt"],
            ),
            TableSchema(
                databaseName="default",
                tableName="users",
                columns=[
                    ColumnSchema(name="user_id", type="bigint"),
                    ColumnSchema(name="city", type="string"),
                ],
            ),
        ],
    )


@pytest.mark.asyncio
class TestMockSqlGenerator:
    async def test_simple_select(self, mockGenerator: MockSqlGenerator) -> None:
        ctx = _mockCtx()
        intent = Intent(primaryType=IntentType.SIMPLE_SELECT)
        result = await mockGenerator.generate("查询 orders", ctx, intent)
        assert result.sql
        assert "SELECT" in result.sql.upper()
        assert "FROM" in result.sql.upper()
        assert result.llmUsed is False
        assert result.elapsedMs >= 0.0

    async def test_count_aggregation(self, mockGenerator: MockSqlGenerator) -> None:
        ctx = _mockCtx()
        intent = Intent(
            primaryType=IntentType.AGGREGATION,
            aggFunc=AggFunc.COUNT,
        )
        result = await mockGenerator.generate("统计订单数量", ctx, intent)
        assert "COUNT(*) AS cnt" in result.sql
        assert "LIMIT" in result.sql.upper()

    async def test_sum_aggregation_with_column(self, mockGenerator: MockSqlGenerator) -> None:
        ctx = _mockCtx()
        intent = Intent(
            primaryType=IntentType.AGGREGATION,
            aggFunc=AggFunc.SUM,
            aggColumn="amount",
        )
        result = await mockGenerator.generate("amount 总和", ctx, intent)
        assert "SUM(amount)" in result.sql

    async def test_group_by(self, mockGenerator: MockSqlGenerator) -> None:
        ctx = _mockCtx()
        intent = Intent(
            primaryType=IntentType.GROUP,
            aggFunc=AggFunc.COUNT,
            groupColumns=["city"],
        )
        result = await mockGenerator.generate("按 city 分组统计", ctx, intent)
        assert "GROUP BY" in result.sql.upper()
        assert "city" in result.sql

    async def test_order_by(self, mockGenerator: MockSqlGenerator) -> None:
        ctx = _mockCtx()
        intent = Intent(
            primaryType=IntentType.SORT,
            sortColumn="amount",
            sortDirection="desc",
        )
        result = await mockGenerator.generate("按 amount 降序", ctx, intent)
        assert "ORDER BY" in result.sql.upper()
        assert "amount" in result.sql
        assert "desc" in result.sql.lower()

    async def test_limit(self, mockGenerator: MockSqlGenerator) -> None:
        ctx = _mockCtx()
        intent = Intent(primaryType=IntentType.SIMPLE_SELECT, limit=50)
        result = await mockGenerator.generate("前 50 条", ctx, intent)
        assert "LIMIT 50" in result.sql

    async def test_join(self, mockGenerator: MockSqlGenerator) -> None:
        ctx = _mockCtx()
        intent = Intent(
            primaryType=IntentType.JOIN,
            joinTables=["orders", "users"],
        )
        result = await mockGenerator.generate("关联 orders 和 users", ctx, intent)
        assert "JOIN" in result.sql.upper()
        assert "users" in result.sql
        # 应有 ON 条件（同名列 user_id）
        assert "ON" in result.sql.upper()

    async def test_time_range_slot(self, mockGenerator: MockSqlGenerator) -> None:
        ctx = _mockCtx()
        intent = Intent(primaryType=IntentType.SIMPLE_SELECT)
        frame = SlotFrame(
            slots=[
                Slot(name="timeRange", required=False, status=SlotStatus.FILLED, value="yesterday"),
            ],
            intent=intent,
        )
        result = await mockGenerator.generate("查询昨天的数据", ctx, intent, frame)
        assert "WHERE" in result.sql.upper()
        assert "dt" in result.sql

    async def test_empty_context(self, mockGenerator: MockSqlGenerator) -> None:
        ctx = SchemaContext(tables=[])
        intent = Intent(primaryType=IntentType.SIMPLE_SELECT)
        result = await mockGenerator.generate("查询", ctx, intent)
        assert "SELECT 1" in result.sql

    async def test_validation_attached(self, mockGenerator: MockSqlGenerator) -> None:
        ctx = _mockCtx()
        intent = Intent(primaryType=IntentType.SIMPLE_SELECT)
        result = await mockGenerator.generate("查询 orders", ctx, intent)
        assert result.validation is not None

    async def test_create_generator_mock(self, settings) -> None:
        from sql_validator import SqlValidator

        settings.llmMode = "mock"
        gen = createGenerator(settings, SqlValidator(settings))
        assert isinstance(gen, MockSqlGenerator)
