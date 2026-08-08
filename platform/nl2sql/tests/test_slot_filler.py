"""槽位填充单测."""

from __future__ import annotations

from models import (
    AggFunc,
    ColumnSchema,
    Intent,
    IntentType,
    SchemaContext,
    TableSchema,
)
from slot_filler import SlotFiller, parseTimeRange


def _mockCtx() -> SchemaContext:
    return SchemaContext(
        database="default",
        tables=[
            TableSchema(
                databaseName="default",
                tableName="orders",
                columns=[
                    ColumnSchema(name="amount", type="decimal"),
                    ColumnSchema(name="dt", type="date"),
                ],
                partitionKeys=["dt"],
            ),
        ],
    )


class TestParseTimeRange:
    def test_today(self) -> None:
        assert parseTimeRange("今天的数据") == "today"

    def test_yesterday(self) -> None:
        assert parseTimeRange("昨天") == "yesterday"

    def test_last_n_days(self) -> None:
        assert parseTimeRange("最近7天") == "last_7_days"

    def test_last_n_months(self) -> None:
        assert parseTimeRange("最近3个月") == "last_3_months"

    def test_this_month(self) -> None:
        assert parseTimeRange("本月") == "this_month"

    def test_last_month(self) -> None:
        assert parseTimeRange("上月") == "last_month"

    def test_exact_date(self) -> None:
        assert parseTimeRange("2024-01-01") == "2024-01-01"

    def test_year_month(self) -> None:
        assert parseTimeRange("2024-01") == "2024-01"

    def test_no_match(self) -> None:
        assert parseTimeRange("随便看看") is None


class TestSlotFiller:
    def test_build_frame_aggregation(self, slotFiller: SlotFiller) -> None:
        intent = Intent(
            primaryType=IntentType.AGGREGATION,
            aggFunc=AggFunc.COUNT,
        )
        frame = slotFiller.buildFrame(intent, "统计订单数量", _mockCtx())
        # 应有 timeRange / aggColumn / filterCondition / limit 等槽位
        names = {s.name for s in frame.slots}
        assert "timeRange" in names
        assert "aggColumn" in names
        assert "limit" in names

    def test_fill_time_range(self, slotFiller: SlotFiller) -> None:
        intent = Intent(primaryType=IntentType.SIMPLE_SELECT)
        frame = slotFiller.buildFrame(intent, "查询昨天的数据", _mockCtx())
        tr = frame.get("timeRange")
        assert tr is not None
        assert tr.isFilled
        assert tr.value == "yesterday"

    def test_fill_limit(self, slotFiller: SlotFiller) -> None:
        intent = Intent(primaryType=IntentType.SIMPLE_SELECT, limit=10)
        frame = slotFiller.buildFrame(intent, "前 10 条", _mockCtx())
        lim = frame.get("limit")
        assert lim is not None
        assert lim.isFilled
        assert lim.value == 10

    def test_fill_agg_column(self, slotFiller: SlotFiller) -> None:
        intent = Intent(
            primaryType=IntentType.AGGREGATION,
            aggFunc=AggFunc.SUM,
            aggColumn="amount",
        )
        frame = slotFiller.buildFrame(intent, "amount 总和", _mockCtx())
        agg = frame.get("aggColumn")
        assert agg is not None
        assert agg.isFilled
        assert agg.value == "amount"

    def test_join_required_slot(self, slotFiller: SlotFiller) -> None:
        intent = Intent(primaryType=IntentType.JOIN, joinTables=["orders"])
        frame = slotFiller.buildFrame(intent, "关联 orders 表", _mockCtx())
        jt = frame.get("joinTables")
        assert jt is not None
        assert jt.required is True
        assert jt.isFilled  # 已从 intent 填充

    def test_missing_questions(self, slotFiller: SlotFiller) -> None:
        intent = Intent(primaryType=IntentType.JOIN)
        frame = slotFiller.buildFrame(intent, "关联一下", _mockCtx())
        qs = slotFiller.missingQuestions(frame)
        # joinTables 缺失应产生问题
        assert len(qs) >= 1

    def test_merge_answer_time(self, slotFiller: SlotFiller) -> None:
        intent = Intent(primaryType=IntentType.SIMPLE_SELECT)
        frame = slotFiller.buildFrame(intent, "查询数据", _mockCtx())
        slotFiller.mergeAnswer(frame, "昨天", _mockCtx())
        tr = frame.get("timeRange")
        assert tr is not None and tr.isFilled and tr.value == "yesterday"

    def test_merge_answer_limit(self, slotFiller: SlotFiller) -> None:
        intent = Intent(primaryType=IntentType.SIMPLE_SELECT)
        frame = slotFiller.buildFrame(intent, "查询数据", _mockCtx())
        slotFiller.mergeAnswer(frame, "前 50 条", _mockCtx())
        lim = frame.get("limit")
        assert lim is not None and lim.isFilled and lim.value == 50

    def test_is_complete(self, slotFiller: SlotFiller) -> None:
        intent = Intent(primaryType=IntentType.SIMPLE_SELECT)
        frame = slotFiller.buildFrame(intent, "查询昨天的数据", _mockCtx())
        # 简单查询无必需槽位，应完成
        assert frame.isComplete is True
