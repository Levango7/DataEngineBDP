"""槽位填充.

职责：
    1. 根据意图定义槽位 schema（timeRange / aggColumn / filterValue / groupBy / sortBy / limit 等）。
    2. 从用户自然语言抽取槽位值。
    3. 检测缺失 / 模糊槽位，生成澄清问题。

设计要点：
    - 槽位定义与意图类型关联：聚合意图必有 aggColumn，过滤意图必有 filterValue。
    - 时间槽位支持"昨天/今天/最近N天/本月/上月"等自然语言表述。
    - 槽位填充是澄清的前置：缺失必需槽位时触发澄清流程。
"""

from __future__ import annotations

import re
from typing import Optional

from models import Intent, IntentType, SchemaContext, Slot, SlotFrame, SlotStatus

# ============================================================
# 时间表达式解析
# ============================================================
_TIME_PATTERNS: list[tuple[re.Pattern, str]] = [
    (re.compile(r"今天|today", re.IGNORECASE), "today"),
    (re.compile(r"昨天|yesterday", re.IGNORECASE), "yesterday"),
    (re.compile(r"前天", re.IGNORECASE), "day_before_yesterday"),
    (re.compile(r"最近\s*(\d+)\s*天", re.IGNORECASE), "last_n_days"),
    (re.compile(r"最近\s*(\d+)\s*个月", re.IGNORECASE), "last_n_months"),
    (re.compile(r"本月|这个月", re.IGNORECASE), "this_month"),
    (re.compile(r"上月|上个月", re.IGNORECASE), "last_month"),
    (re.compile(r"本年|今年", re.IGNORECASE), "this_year"),
    (re.compile(r"去年|上一年", re.IGNORECASE), "last_year"),
    (re.compile(r"(\d{4})[-/](\d{1,2})[-/](\d{1,2})"), "exact_date"),
    (re.compile(r"(\d{4})[-/](\d{1,2})"), "year_month"),
]


def parseTimeRange(query: str) -> Optional[str]:
    """从查询中解析时间范围表达式，返回规范化字符串.

    Returns:
        规范化时间表达式（如 'yesterday' / 'last_7_days' / '2024-01-01'），
        无匹配返回 None。
    """
    for pat, kind in _TIME_PATTERNS:
        m = pat.search(query)
        if not m:
            continue
        if kind == "last_n_days":
            return f"last_{m.group(1)}_days"
        if kind == "last_n_months":
            return f"last_{m.group(1)}_months"
        if kind == "exact_date":
            y, mo, d = m.group(1), m.group(2).zfill(2), m.group(3).zfill(2)
            return f"{y}-{mo}-{d}"
        if kind == "year_month":
            y, mo = m.group(1), m.group(2).zfill(2)
            return f"{y}-{mo}"
        return kind
    return None


# ============================================================
# 槽位填充器
# ============================================================
class SlotFiller:
    """槽位填充器."""

    def __init__(self) -> None:
        self._limitPat = re.compile(
            r"(?:前|top|取|最多|limit\s*)\s*(\d+)\s*(?:条|行|个)?",
            re.IGNORECASE,
        )

    def buildFrame(
        self,
        intent: Intent,
        query: str,
        ctx: Optional[SchemaContext] = None,
    ) -> SlotFrame:
        """根据意图构建槽位框架并尝试从查询中填充.

        Args:
            intent: 已识别意图。
            query: 用户查询文本。
            ctx: Schema 上下文。

        Returns:
            SlotFrame。
        """
        slots = self._defineSlots(intent, ctx)
        frame = SlotFrame(slots=slots, intent=intent)
        self._fillFromQuery(frame, query, ctx)
        return frame

    # ---- 槽位定义 ----
    def _defineSlots(self, intent: Intent, ctx: Optional[SchemaContext]) -> list[Slot]:
        """根据意图定义所需槽位."""
        slots: list[Slot] = []

        # 通用：时间范围（可选，多数查询都涉及时间）
        slots.append(
            Slot(
                name="timeRange",
                required=False,
                status=SlotStatus.OPTIONAL,
                description="查询时间范围",
                promptQuestion="请问要查询哪个时间段的数据？例如：昨天、最近7天、本月",
            )
        )

        # 聚合意图
        if intent.isAggregate:
            slots.append(
                Slot(
                    name="aggColumn",
                    required=False,
                    status=SlotStatus.OPTIONAL,
                    description="聚合列",
                    promptQuestion="请问要对哪个字段做聚合？",
                )
            )

        # 过滤意图
        if intent.primaryType in (IntentType.FILTER, IntentType.AGGREGATION, IntentType.SIMPLE_SELECT):
            slots.append(
                Slot(
                    name="filterCondition",
                    required=False,
                    status=SlotStatus.OPTIONAL,
                    description="过滤条件",
                    promptQuestion="请问需要按什么条件过滤？",
                )
            )

        # 分组意图
        if intent.primaryType == IntentType.GROUP or intent.groupColumns:
            slots.append(
                Slot(
                    name="groupBy",
                    required=False,
                    status=SlotStatus.OPTIONAL,
                    description="分组维度",
                    promptQuestion="请问按哪个字段分组？",
                )
            )

        # 排序意图
        if intent.primaryType == IntentType.SORT or intent.sortColumn:
            slots.append(
                Slot(
                    name="sortBy",
                    required=False,
                    status=SlotStatus.OPTIONAL,
                    description="排序字段与方向",
                    promptQuestion="请问按哪个字段排序？升序还是降序？",
                )
            )

        # 限制
        slots.append(
            Slot(
                name="limit",
                required=False,
                status=SlotStatus.OPTIONAL,
                description="结果行数限制",
                promptQuestion="请问需要返回多少条结果？",
            )
        )

        # Join 意图：连接表
        if intent.isJoin:
            slots.append(
                Slot(
                    name="joinTables",
                    required=True,
                    status=SlotStatus.MISSING,
                    description="参与连接的表",
                    promptQuestion="请问要关联哪些表？",
                )
            )

        return slots

    # ---- 从查询填充 ----
    def _fillFromQuery(
        self,
        frame: SlotFrame,
        query: str,
        ctx: Optional[SchemaContext],
    ) -> None:
        """从查询文本填充槽位值."""
        # timeRange
        tr = parseTimeRange(query)
        if tr:
            frame.set("timeRange", tr)

        # limit
        m = self._limitPat.search(query)
        if m:
            try:
                frame.set("limit", int(m.group(1)))
            except ValueError:
                pass
        elif frame.intent and frame.intent.limit:
            frame.set("limit", frame.intent.limit)

        # aggColumn / groupBy / sortBy / filterCondition：从意图已抽取的实体填充
        intent = frame.intent
        if intent is None:
            return
        if intent.aggColumn:
            frame.set("aggColumn", intent.aggColumn)
        if intent.groupColumns:
            frame.set("groupBy", intent.groupColumns)
        if intent.sortColumn:
            direction = intent.sortDirection or "asc"
            frame.set("sortBy", {"column": intent.sortColumn, "direction": direction})
        if intent.filterColumns:
            frame.set("filterCondition", {"columns": intent.filterColumns})
        if intent.joinTables:
            frame.set("joinTables", intent.joinTables)

    # ---- 澄清问题生成 ----
    def missingQuestions(self, frame: SlotFrame) -> list[str]:
        """返回缺失必需槽位的澄清问题列表."""
        questions: list[str] = []
        for slot in frame.missingSlots():
            q = slot.promptQuestion or f"请提供 {slot.description or slot.name}"
            questions.append(q)
        return questions

    def mergeAnswer(
        self,
        frame: SlotFrame,
        answer: str,
        ctx: Optional[SchemaContext] = None,
    ) -> SlotFrame:
        """将用户对澄清问题的回答合并到槽位框架.

        简化策略：对每个缺失槽位尝试用通用解析填充。
        """
        # 时间
        tr = parseTimeRange(answer)
        if tr:
            frame.set("timeRange", tr)
        # limit
        m = self._limitPat.search(answer)
        if m:
            try:
                frame.set("limit", int(m.group(1)))
            except ValueError:
                pass
        # 列名（基于 schema）
        if ctx and not ctx.isEmpty:
            qLower = answer.lower()
            for t in ctx.tables:
                for c in t.columns:
                    if c.name and c.name.lower() in qLower:
                        # 优先填到第一个缺失槽位
                        for slot in frame.slots:
                            if slot.name in ("aggColumn", "groupBy", "sortBy") and not slot.isFilled:
                                frame.set(slot.name, c.name)
                                break
                        break
        return frame
