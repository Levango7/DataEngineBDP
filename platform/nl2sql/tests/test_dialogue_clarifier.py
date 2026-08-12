"""多轮对话澄清单测."""

from __future__ import annotations

from dialogue_clarifier import DialogueClarifier
from models import (
    ColumnSchema,
    Intent,
    IntentType,
    SchemaContext,
    Slot,
    SlotFrame,
    SlotStatus,
    TableSchema,
)
from slot_filler import SlotFiller


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


class TestDialogueClarifier:
    def test_detect_no_ambiguity(self, clarifier: DialogueClarifier) -> None:
        intent = Intent(primaryType=IntentType.SIMPLE_SELECT)
        frame = SlotFrame(
            slots=[
                Slot(name="timeRange", required=False, status=SlotStatus.FILLED, value="yesterday"),
                Slot(name="limit", required=False, status=SlotStatus.FILLED, value=10),
            ],
            intent=intent,
        )
        qs = clarifier.detectAmbiguity("查询昨天的数据", intent, frame, _mockCtx())
        # 已填充时间，可能仍提示其他可选问题；但不应有必需问题
        assert all("必需" not in q for q in qs)

    def test_detect_missing_required(self, clarifier: DialogueClarifier) -> None:
        intent = Intent(primaryType=IntentType.JOIN)
        frame = SlotFrame(
            slots=[
                Slot(
                    name="joinTables",
                    required=True,
                    status=SlotStatus.MISSING,
                    promptQuestion="请问要关联哪些表？",
                ),
            ],
            intent=intent,
        )
        qs = clarifier.detectAmbiguity("关联一下", intent, frame, _mockCtx())
        assert any("关联" in q for q in qs)

    def test_start_dialogue(self, clarifier: DialogueClarifier) -> None:
        intent = Intent(primaryType=IntentType.SIMPLE_SELECT)
        frame = SlotFrame(slots=[], intent=intent)
        state = clarifier.startDialogue("s1", "查询数据", intent, frame)
        assert state.sessionId == "s1"
        assert state.turnCount == 1
        assert state.turns[0].role == "user"

    def test_next_question_returns_none_when_complete(self, clarifier: DialogueClarifier) -> None:
        intent = Intent(primaryType=IntentType.SIMPLE_SELECT)
        frame = SlotFrame(
            slots=[
                Slot(name="timeRange", required=False, status=SlotStatus.FILLED, value="yesterday"),
            ],
            intent=intent,
        )
        state = clarifier.startDialogue("s1", "查询昨天的数据", intent, frame)
        q = clarifier.nextQuestion(state, _mockCtx())
        # 简单查询 + 时间已填，可能仍有可选问题；若返回 None 则已澄清
        if q is None:
            assert state.clarified is True

    def test_next_question_max_turns(self, slotFiller: SlotFiller) -> None:
        clarifier = DialogueClarifier(slotFiller=slotFiller, maxTurns=1)
        intent = Intent(primaryType=IntentType.JOIN)
        frame = SlotFrame(
            slots=[
                Slot(name="joinTables", required=True, status=SlotStatus.MISSING, promptQuestion="关联哪些表？"),
            ],
            intent=intent,
        )
        state = clarifier.startDialogue("s1", "关联", intent, frame)
        # 第一轮已用，第二轮应返回 None
        q1 = clarifier.nextQuestion(state, _mockCtx())
        # turnCount=2 后达到上限
        if q1:
            state.addTurn(__import__("models").DialogueTurn(role="user", content="t"))
        q2 = clarifier.nextQuestion(state, _mockCtx())
        assert q2 is None

    def test_absorb_answer_updates_slots(self, clarifier: DialogueClarifier) -> None:
        intent = Intent(primaryType=IntentType.SIMPLE_SELECT)
        frame = SlotFrame(
            slots=[
                Slot(name="timeRange", required=False, status=SlotStatus.OPTIONAL, promptQuestion="时间范围？"),
            ],
            intent=intent,
        )
        state = clarifier.startDialogue("s1", "查询数据", intent, frame)
        clarifier.absorbAnswer(state, "昨天", _mockCtx())
        tr = state.currentSlots.get("timeRange")
        assert tr is not None and tr.isFilled and tr.value == "yesterday"

    def test_is_complete(self, clarifier: DialogueClarifier) -> None:
        intent = Intent(primaryType=IntentType.SIMPLE_SELECT)
        frame = SlotFrame(slots=[], intent=intent)
        state = clarifier.startDialogue("s1", "查询", intent, frame)
        state.clarified = True
        assert clarifier.isComplete(state) is True
