"""意图识别单测."""

from __future__ import annotations

from intent_recognition import IntentRecognizer
from models import AggFunc, ColumnSchema, IntentType, SchemaContext, TableSchema


def _mockCtx() -> SchemaContext:
    """构造 mock schema 上下文."""
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


class TestIntentRecognition:
    def test_count_aggregation(self, intentRecognizer: IntentRecognizer) -> None:
        intent = intentRecognizer.recognize("统计订单数量", _mockCtx())
        assert intent.primaryType == IntentType.AGGREGATION
        assert intent.aggFunc == AggFunc.COUNT
        assert intent.confidence > 0.5

    def test_sum_aggregation(self, intentRecognizer: IntentRecognizer) -> None:
        intent = intentRecognizer.recognize("订单金额总和", _mockCtx())
        assert intent.aggFunc == AggFunc.SUM
        assert intent.primaryType == IntentType.AGGREGATION

    def test_avg_aggregation(self, intentRecognizer: IntentRecognizer) -> None:
        intent = intentRecognizer.recognize("平均金额", _mockCtx())
        assert intent.aggFunc == AggFunc.AVG

    def test_max_aggregation(self, intentRecognizer: IntentRecognizer) -> None:
        intent = intentRecognizer.recognize("最高金额", _mockCtx())
        assert intent.aggFunc == AggFunc.MAX

    def test_filter_intent(self, intentRecognizer: IntentRecognizer) -> None:
        intent = intentRecognizer.recognize("查询 dt 等于昨天的订单", _mockCtx())
        assert intent.primaryType in (IntentType.FILTER, IntentType.AGGREGATION)
        assert "dt" in intent.filterColumns

    def test_sort_intent(self, intentRecognizer: IntentRecognizer) -> None:
        intent = intentRecognizer.recognize("按 amount 降序排序", _mockCtx())
        assert intent.primaryType == IntentType.SORT
        assert intent.sortDirection == "desc"
        assert intent.sortColumn == "amount"

    def test_sort_asc(self, intentRecognizer: IntentRecognizer) -> None:
        intent = intentRecognizer.recognize("按 amount 升序", _mockCtx())
        assert intent.sortDirection == "asc"

    def test_limit_intent(self, intentRecognizer: IntentRecognizer) -> None:
        intent = intentRecognizer.recognize("前 10 条订单", _mockCtx())
        assert intent.limit == 10

    def test_join_intent(self, intentRecognizer: IntentRecognizer) -> None:
        intent = intentRecognizer.recognize("关联 orders 和 users 表", _mockCtx())
        assert intent.primaryType == IntentType.JOIN
        assert len(intent.joinTables) >= 1

    def test_group_intent(self, intentRecognizer: IntentRecognizer) -> None:
        intent = intentRecognizer.recognize("按 city 分组统计订单数量", _mockCtx())
        # 含"数量"触发聚合，含"分组"可能升级为 GROUP
        assert intent.primaryType in (IntentType.GROUP, IntentType.AGGREGATION)
        assert intent.aggFunc == AggFunc.COUNT

    def test_simple_select(self, intentRecognizer: IntentRecognizer) -> None:
        intent = intentRecognizer.recognize("查看 orders", _mockCtx())
        assert intent.primaryType in (
            IntentType.SIMPLE_SELECT,
            IntentType.FILTER,
        )

    def test_no_schema_context(self, intentRecognizer: IntentRecognizer) -> None:
        """无 schema 上下文也能识别意图类型."""
        intent = intentRecognizer.recognize("统计订单数量", None)
        assert intent.primaryType == IntentType.AGGREGATION
        assert intent.aggFunc == AggFunc.COUNT

    def test_confidence_range(self, intentRecognizer: IntentRecognizer) -> None:
        intent = intentRecognizer.recognize("随便看看", _mockCtx())
        assert 0.0 <= intent.confidence <= 1.0
