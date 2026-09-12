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

class TestTimeToWhereSqlInjection:
    """_timeToWhere 白名单校验测试（防 SQL 注入）."""

    def _table(self):
        return TableSchema(
            databaseName="default",
            tableName="orders",
            columns=[ColumnSchema(name="dt", type="date")],
            partitionKeys=["dt"],
        )

    def test_predefined_keywords_pass(self):
        """预定义关键字应通过白名单."""
        for kw in [
            "today",
            "yesterday",
            "day_before_yesterday",
            "this_month",
            "last_month",
            "this_year",
            "last_year",
        ]:
            sql = MockSqlGenerator._timeToWhere(kw, self._table())
            assert "dt" in sql

    def test_last_n_days_pass(self):
        """last_N_days 格式应通过."""
        sql = MockSqlGenerator._timeToWhere("last_7_days", self._table())
        assert "date_sub(current_date, 7)" in sql

    def test_last_n_months_pass(self):
        """last_N_months 格式应通过."""
        sql = MockSqlGenerator._timeToWhere("last_3_months", self._table())
        assert "date_sub(current_date, 90)" in sql

    def test_exact_date_pass(self):
        """YYYY-MM-DD 精确日期应通过."""
        sql = MockSqlGenerator._timeToWhere("2025-01-15", self._table())
        assert "date '2025-01-15'" in sql

    def test_year_month_pass(self):
        """YYYY-MM 年月应通过."""
        sql = MockSqlGenerator._timeToWhere("2025-01", self._table())
        assert "date '2025-01-01'" in sql

    @pytest.mark.parametrize(
        "malicious",
        [
            "'; DROP TABLE orders; --",  # 经典 SQL 注入
            "today' OR '1'='1",  # 字符串截断
            "today; DROP TABLE users",  # 分号注入
            "today--",  # 注释注入
            "today/*comment*/",  # 块注释
            "today UNION SELECT * FROM users",  # UNION 注入（含空格）
            "today\tOR\t1=1",  # 制表符
            "today\nOR\n1=1",  # 换行符
            "today' AND 1=1 --",  # 引号+注释
            "last_7_days'; --",  # last_N_days 后注入
            "last_'7'_days",  # last_N_days 内引号
            "2025-01-15'; --",  # 日期后注入
            "2025-01-15' OR '1'='1",  # 日期后 OR 注入
            "last_-1_days",  # 负数
            "last_abc_days",  # 非数字
            "last_7_days ",  # 末尾空格
            " today",  # 前导空格
            "today\"",  # 双引号
            "today\\",  # 反斜杠
            "today\x00",  # null 字节
            "x" * 33,  # 超长（>32）
        ],
    )
    def test_malicious_input_rejected(self, malicious: str):
        """恶意输入应被拒绝（抛 ValueError）."""
        with pytest.raises(ValueError):
            MockSqlGenerator._timeToWhere(malicious, self._table())

    def test_empty_input_rejected(self):
        """空字符串应被拒绝."""
        with pytest.raises(ValueError):
            MockSqlGenerator._timeToWhere("", self._table())

    def test_non_string_rejected(self):
        """非字符串输入应被拒绝."""
        with pytest.raises(ValueError):
            MockSqlGenerator._timeToWhere(None, self._table())  # type: ignore[arg-type]
        with pytest.raises(ValueError):
            MockSqlGenerator._timeToWhere(123, self._table())  # type: ignore[arg-type]

    def test_invalid_date_rejected(self):
        """非法日期格式应被拒绝."""
        with pytest.raises(ValueError):
            MockSqlGenerator._timeToWhere("2025-13-45", self._table())  # 月13 日45
        with pytest.raises(ValueError):
            MockSqlGenerator._timeToWhere("2025-00-01", self._table())  # 月0
        with pytest.raises(ValueError):
            MockSqlGenerator._timeToWhere("2025-13", self._table())  # 月13

    def test_unrecognized_format_rejected(self):
        """未识别格式应被拒绝（不再默认拼接到 SQL）."""
        with pytest.raises(ValueError):
            MockSqlGenerator._timeToWhere("foobar", self._table())
        with pytest.raises(ValueError):
            MockSqlGenerator._timeToWhere("last_7", self._table())  # 缺 _days/_months 后缀
