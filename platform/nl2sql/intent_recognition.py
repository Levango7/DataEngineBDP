"""意图识别.

职责：
    识别用户自然语言查询的意图类型（聚合 / 过滤 / Join / 排序 / 分组 / 限制 / 简单查询），
    并抽取相关实体（聚合列、过滤列、排序列、Join 表等）。

策略：
    采用"关键词匹配 + 启发式规则"的轻量方案，保证无 LLM 依赖即可工作；
    后续可扩展为 LLM 意图分类（在 LlmIntentRecognizer 中实现，本文件提供接口）。

设计要点：
    - 关键词表覆盖中英文常见表述。
    - 多意图可同时识别，但 primaryType 取优先级最高者。
    - 置信度根据命中关键词数量与意图明确度估算。
"""

from __future__ import annotations

import re
from typing import Optional

from models import AggFunc, Intent, IntentType, SchemaContext

# ============================================================
# 关键词表
# ============================================================
_AGG_KEYWORDS: dict[AggFunc, list[str]] = {
    AggFunc.COUNT: [
        "多少",
        "几个",
        "数量",
        "计数",
        "count",
        "总数",
        "条数",
    ],
    AggFunc.SUM: ["总和", "合计", "求和", "sum", "总金额", "累加"],
    AggFunc.AVG: ["平均", "均值", "avg", "average", "mean"],
    AggFunc.MAX: ["最大", "最高", "max", "峰值", "top"],
    AggFunc.MIN: ["最小", "最低", "min", "谷值", "bottom"],
}

_FILTER_KEYWORDS: list[str] = [
    "其中",
    "筛选",
    "过滤",
    "条件",
    "where",
    "等于",
    "大于",
    "小于",
    "是",
    "为",
    "包含",
    "属于",
    "在",
    "between",
    "like",
    "in",
    "昨天",
    "今天",
    "最近",
    "本月",
    "上月",
    "本年",
    "去年",
]

_JOIN_KEYWORDS: list[str] = [
    "join",
    "连接",
    "关联",
    "联合",
    "以及",
    "和",
    "与",
    "对应",
    "匹配",
    "each",
    "together",
    "combine",
]

_SORT_KEYWORDS: list[str] = [
    "排序",
    "sort",
    "order by",
    "order",
    "排列",
    "降序",
    "升序",
    "desc",
    "asc",
    "从大到小",
    "从小到大",
    "按",
]

_GROUP_KEYWORDS: list[str] = [
    "分组",
    "group by",
    "group",
    "按...分类",
    "分类",
    "每个",
    "各类",
    "各",
    "按天",
    "按月",
    "按年",
    "按城市",
    "按类目",
]

_LIMIT_KEYWORDS: list[str] = [
    "前",
    "top",
    "limit",
    "取",
    "只看",
    "最多",
    "条",
]


# ============================================================
# 意图识别器
# ============================================================
class IntentRecognizer:
    """基于关键词 + 启发式规则的意图识别器."""

    def __init__(self) -> None:
        # 编译排序方向正则
        self._descPat = re.compile(r"(降序|从大到小|从高到低|desc\b)", re.IGNORECASE)
        self._ascPat = re.compile(r"(升序|从小到大|从低到高|asc\b)", re.IGNORECASE)
        # 数字 + 单位（前 N 条 / top N）
        self._limitPat = re.compile(
            r"(?:前|top|取|最多|limit\s*)\s*(\d+)\s*(?:条|行|个)?",
            re.IGNORECASE,
        )

    def recognize(
        self,
        query: str,
        ctx: Optional[SchemaContext] = None,
    ) -> Intent:
        """识别查询意图.

        Args:
            query: 用户自然语言查询。
            ctx: Schema 上下文（用于列名实体匹配，可空）。

        Returns:
            Intent 对象。
        """
        q = query.lower()
        entities: dict[str, object] = {}

        # ---- 聚合 ----
        aggFunc = AggFunc.NONE
        aggColumn: Optional[str] = None
        for func, kws in _AGG_KEYWORDS.items():
            for kw in kws:
                if kw in q:
                    aggFunc = func
                    entities["aggKeyword"] = kw
                    break
            if aggFunc != AggFunc.NONE:
                break
        if aggFunc != AggFunc.NONE:
            aggColumn = self._guessColumn(query, ctx)

        # ---- 过滤 ----
        filterColumns: list[str] = []
        if any(kw in q for kw in _FILTER_KEYWORDS):
            filterColumns = self._extractColumns(query, ctx)

        # ---- Join ----
        joinTables: list[str] = []
        if any(kw in q for kw in _JOIN_KEYWORDS):
            joinTables = self._extractTables(query, ctx)

        # ---- 排序 ----
        sortColumn: Optional[str] = None
        sortDirection: Optional[str] = None
        if any(kw in q for kw in _SORT_KEYWORDS):
            sortColumn = self._guessSortColumn(query, ctx)
            if self._descPat.search(query):
                sortDirection = "desc"
            elif self._ascPat.search(query):
                sortDirection = "asc"

        # ---- 分组 ----
        groupColumns: list[str] = []
        if any(kw in q for kw in _GROUP_KEYWORDS):
            groupColumns = self._extractColumns(query, ctx)

        # ---- 限制 ----
        limit: Optional[int] = None
        m = self._limitPat.search(query)
        if m:
            try:
                limit = int(m.group(1))
            except ValueError:
                limit = None
        if any(kw in q for kw in _LIMIT_KEYWORDS) and limit is None:
            limit = 10  # 默认前 10 条

        # ---- 主意图判定 ----
        primaryType, confidence = self._decidePrimary(
            aggFunc=aggFunc,
            hasFilter=bool(filterColumns),
            joinTables=joinTables,
            hasSort=sortColumn is not None,
            hasGroup=bool(groupColumns),
            hasLimit=limit is not None,
        )

        return Intent(
            primaryType=primaryType,
            aggFunc=aggFunc,
            aggColumn=aggColumn,
            filterColumns=filterColumns,
            joinTables=joinTables,
            sortColumn=sortColumn,
            sortDirection=sortDirection,
            groupColumns=groupColumns,
            limit=limit,
            confidence=confidence,
            rawEntities=entities,
        )

    # ---- 实体抽取辅助 ----
    def _extractColumns(self, query: str, ctx: Optional[SchemaContext]) -> list[str]:
        """从查询中抽取列名（基于 schema 列名匹配）."""
        if not ctx or ctx.isEmpty:
            return []
        qLower = query.lower()
        found: list[str] = []
        for t in ctx.tables:
            for c in t.columns:
                if c.name and c.name.lower() in qLower:
                    if c.name not in found:
                        found.append(c.name)
        return found

    def _extractTables(self, query: str, ctx: Optional[SchemaContext]) -> list[str]:
        """从查询中抽取表名."""
        if not ctx or ctx.isEmpty:
            return []
        qLower = query.lower()
        found: list[str] = []
        for t in ctx.tables:
            if t.tableName.lower() in qLower:
                found.append(t.tableName)
        return found

    def _guessColumn(self, query: str, ctx: Optional[SchemaContext]) -> Optional[str]:
        """猜测聚合列（默认 *，若查询中明确出现某列名则用之）."""
        cols = self._extractColumns(query, ctx)
        return cols[0] if cols else None

    def _guessSortColumn(self, query: str, ctx: Optional[SchemaContext]) -> Optional[str]:
        """猜测排序列."""
        cols = self._extractColumns(query, ctx)
        return cols[0] if cols else None

    # ---- 主意图决策 ----
    def _decidePrimary(
        self,
        aggFunc: AggFunc,
        hasFilter: bool,
        joinTables: list[str],
        hasSort: bool,
        hasGroup: bool,
        hasLimit: bool,
    ) -> tuple[IntentType, float]:
        """根据各子意图命中情况决定主意图与置信度.

        优先级：Join > 聚合 > 分组 > 排序 > 过滤 > 限制 > 简单查询。
        """
        score = 0.0
        if len(joinTables) >= 2:
            score += 0.4
            return IntentType.JOIN, min(0.9, 0.5 + score)
        if aggFunc != AggFunc.NONE:
            score += 0.4
            if hasGroup:
                score += 0.2
                return IntentType.GROUP, min(0.95, 0.5 + score)
            return IntentType.AGGREGATION, min(0.95, 0.5 + score)
        if hasGroup:
            score += 0.3
            return IntentType.GROUP, min(0.9, 0.5 + score)
        if hasSort:
            score += 0.2
            return IntentType.SORT, min(0.85, 0.45 + score)
        if hasFilter:
            score += 0.2
            return IntentType.FILTER, min(0.8, 0.4 + score)
        if hasLimit:
            score += 0.1
            return IntentType.LIMIT, min(0.7, 0.4 + score)
        return IntentType.SIMPLE_SELECT, 0.5 + score
