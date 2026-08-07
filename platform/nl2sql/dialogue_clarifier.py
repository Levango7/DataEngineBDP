"""多轮对话澄清.

职责：
    1. 检测模糊查询（缺失必需槽位、表名歧义、时间范围缺失等）。
    2. 生成澄清问题（基于槽位填充结果）。
    3. 维护多轮对话状态，合并用户回答。
    4. 判断澄清是否完成。

设计要点：
    - 澄清轮次上限由 settings.maxDialogueTurns 控制，超过则降级生成最佳猜测 SQL。
    - 澄清问题按优先级排序：必需槽位 > 时间范围 > 聚合列。
    - 与 SlotFiller 协作：SlotFiller 检测缺失，DialogueClarifier 编排交互流程。
"""
from __future__ import annotations

from typing import Optional

from models import (
    DialogueState,
    DialogueTurn,
    Intent,
    SchemaContext,
    SlotFrame,
)
from slot_filler import SlotFiller


class DialogueClarifier:
    """多轮对话澄清器."""

    def __init__(self, slotFiller: Optional[SlotFiller] = None, maxTurns: int = 5) -> None:
        self.slotFiller = slotFiller or SlotFiller()
        self.maxTurns = maxTurns

    # ---- 模糊检测 ----
    def detectAmbiguity(
        self,
        query: str,
        intent: Intent,
        frame: SlotFrame,
        ctx: Optional[SchemaContext] = None,
    ) -> list[str]:
        """检测模糊点，返回澄清问题列表.

        Args:
            query: 用户原始查询。
            intent: 识别意图。
            frame: 槽位框架。
            ctx: Schema 上下文。

        Returns:
            澄清问题列表（空表示无需澄清）。
        """
        questions: list[str] = []

        # 1. 缺失必需槽位
        questions.extend(self.slotFiller.missingQuestions(frame))

        # 2. 聚合意图但聚合列未明确（且非 COUNT(*)）
        from models import AggFunc
        if intent.isAggregate and intent.aggFunc != AggFunc.COUNT:
            aggSlot = frame.get("aggColumn")
            if aggSlot is not None and not aggSlot.isFilled:
                questions.append("请问要对哪个字段做聚合计算？")

        # 3. 时间范围缺失（多数业务查询都需要时间约束）
        timeSlot = frame.get("timeRange")
        if timeSlot is not None and not timeSlot.isFilled:
            # 仅在查询未显式提及时间时提示
            questions.append("请问要查询哪个时间段的数据？例如：昨天、最近7天、本月")

        # 去重保序
        seen: set[str] = set()
        unique: list[str] = []
        for q in questions:
            if q not in seen:
                seen.add(q)
                unique.append(q)
        return unique

    # ---- 对话编排 ----
    def startDialogue(
        self,
        sessionId: str,
        query: str,
        intent: Intent,
        frame: SlotFrame,
        database: Optional[str] = None,
    ) -> DialogueState:
        """开启一轮新对话，返回初始状态（含首轮澄清问题）."""
        state = DialogueState(
            sessionId=sessionId,
            database=database,
            currentSlots=frame,
        )
        state.addTurn(DialogueTurn(
            role="user",
            content=query,
            intent=intent,
            slots=frame,
        ))
        return state

    def nextQuestion(
        self,
        state: DialogueState,
        ctx: Optional[SchemaContext] = None,
    ) -> Optional[str]:
        """根据当前状态生成下一个澄清问题.

        Returns:
            澄清问题文本；None 表示无需再澄清（可生成 SQL）。
        """
        if state.turnCount >= self.maxTurns:
            return None
        if state.currentSlots is None or state.currentSlots.intent is None:
            return None
        # 取最后一轮 user 查询
        lastUser = next(
            (t for t in reversed(state.turns) if t.role == "user"), None
        )
        if lastUser is None:
            return None
        questions = self.detectAmbiguity(
            lastUser.content,
            state.currentSlots.intent,
            state.currentSlots,
            ctx,
        )
        if not questions:
            state.clarified = True
            return None
        # 追加 assistant 澄清问题
        state.addTurn(DialogueTurn(
            role="assistant",
            content=questions[0],
        ))
        return questions[0]

    def absorbAnswer(
        self,
        state: DialogueState,
        answer: str,
        ctx: Optional[SchemaContext] = None,
    ) -> DialogueState:
        """吸收用户对澄清问题的回答，更新槽位框架."""
        if state.currentSlots is None:
            return state
        self.slotFiller.mergeAnswer(state.currentSlots, answer, ctx)
        state.addTurn(DialogueTurn(
            role="user",
            content=answer,
            slots=state.currentSlots,
        ))
        return state

    # ---- 完成判定 ----
    def isComplete(self, state: DialogueState) -> bool:
        """判断对话是否完成（已澄清或达到轮次上限）."""
        if state.clarified:
            return True
        if state.turnCount >= self.maxTurns:
            return True
        if state.currentSlots is not None and state.currentSlots.isComplete:
            return True
        return False