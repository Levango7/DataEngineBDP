"""SQL 生成（LangChain + LLM）.

职责：
    1. 基于 Schema 上下文 + 意图 + 槽位，构造 LLM prompt 生成 SQL。
    2. 支持 Mock 模式（无 LLM 依赖，基于意图 + 槽位的规则化 SQL 生成）。
    3. 支持 LangChain 模式（经 llm-gateway :8084，OpenAI 兼容协议）。
    4. 生成后调用 SqlValidator 校验，返回 SqlGenerationResult。

设计要点：
    - Mock 模式保证 `python -c "import app"` 与单测无外部依赖即可运行。
    - LangChain 模式延迟导入 langchain，避免未安装时 import 失败。
    - Prompt 模板内嵌（Jinja2 风格字符串），不依赖外部模板文件。
"""
from __future__ import annotations

import time
from typing import Optional

from loguru import logger

from config.settings import Settings
from models import (
    AggFunc,
    Intent,
    IntentType,
    SchemaContext,
    SlotFrame,
    SqlGenerationResult,
)
from schema_context import SchemaContextBuilder
from sql_validator import SqlValidator


# ============================================================
# Prompt 模板
# ============================================================
_SYSTEM_PROMPT = """你是数擎大数据平台的 NL2SQL 引擎。
根据用户自然语言查询、数据库 schema 与已识别意图，生成一条标准 ANSI SQL。
约束：
1. 仅生成 SELECT 语句，禁止 DML/DDL。
2. 表名使用全限定名（database.table）。
3. 聚合函数大写：COUNT/SUM/AVG/MAX/MIN。
4. 必须包含 LIMIT，默认 100。
5. 仅输出 SQL 本身，不要解释、不要 Markdown 代码块。
"""

_USER_PROMPT_TEMPLATE = """### 数据库 schema
{schema_ddl}

### 用户问题
{query}

### 已识别意图
- 主意图: {primary_type}
- 聚合函数: {agg_func}
- 聚合列: {agg_column}
- 过滤列: {filter_columns}
- 分组列: {group_columns}
- 排序: {sort}
- Join 表: {join_tables}
- 时间范围: {time_range}
- 行数限制: {limit}

请生成 SQL：
"""


# ============================================================
# SQL 生成器基类
# ============================================================
class BaseSqlGenerator:
    """SQL 生成器抽象基类."""

    def __init__(self, settings: Settings, validator: SqlValidator) -> None:
        self.settings = settings
        self.validator = validator

    async def generate(
        self,
        query: str,
        ctx: SchemaContext,
        intent: Intent,
        slots: Optional[SlotFrame] = None,
    ) -> SqlGenerationResult:
        """生成 SQL（子类实现）."""
        raise NotImplementedError

    def _buildUserPrompt(
        self, query: str, ctx: SchemaContext, intent: Intent, slots: Optional[SlotFrame]
    ) -> str:
        """构造 user prompt."""
        schemaDdl = SchemaContextBuilder.renderDdl(ctx)
        sortStr = "无"
        if intent.sortColumn:
            sortStr = f"{intent.sortColumn} {intent.sortDirection or 'asc'}"
        timeRange = "未指定"
        limit = intent.limit or self.settings.defaultLimit
        if slots is not None:
            trSlot = slots.get("timeRange")
            if trSlot and trSlot.isFilled:
                timeRange = trSlot.value
            limSlot = slots.get("limit")
            if limSlot and limSlot.isFilled:
                limit = limSlot.value
        return _USER_PROMPT_TEMPLATE.format(
            schema_ddl=schemaDdl,
            query=query,
            primary_type=intent.primaryType.value,
            agg_func=intent.aggFunc.value,
            agg_column=intent.aggColumn or "无",
            filter_columns=", ".join(intent.filterColumns) or "无",
            group_columns=", ".join(intent.groupColumns) or "无",
            sort=sortStr,
            join_tables=", ".join(intent.joinTables) or "无",
            time_range=timeRange,
            limit=limit,
        )


# ============================================================
# Mock 生成器（规则化，无 LLM 依赖）
# ============================================================
class MockSqlGenerator(BaseSqlGenerator):
    """基于规则的 Mock SQL 生成器.

    根据意图与槽位拼装 SQL，保证可读性与基本正确性，用于：
    - 无 LLM 环境的演示 / 单测。
    - LangChain 不可达时的降级。
    """

    async def generate(
        self,
        query: str,
        ctx: SchemaContext,
        intent: Intent,
        slots: Optional[SlotFrame] = None,
    ) -> SqlGenerationResult:
        start = time.perf_counter()
        sql = self._buildSql(ctx, intent, slots)
        validation = self.validator.validate(sql, ctx)
        elapsed = (time.perf_counter() - start) * 1000.0
        return SqlGenerationResult(
            sql=sql,
            intent=intent,
            validation=validation,
            slots=slots,
            needsClarification=False,
            clarificationQuestions=[],
            llmUsed=False,
            elapsedMs=elapsed,
        )

    def _buildSql(
        self, ctx: SchemaContext, intent: Intent, slots: Optional[SlotFrame]
    ) -> str:
        """规则化拼装 SQL."""
        if ctx.isEmpty:
            return "SELECT 1;"

        # 选择主表：优先 joinTables[0]，否则第一张表
        primaryTable = None
        if intent.joinTables:
            for t in ctx.tables:
                if t.tableName in intent.joinTables:
                    primaryTable = t
                    break
        if primaryTable is None:
            primaryTable = ctx.tables[0]
        tableRef = primaryTable.qualifiedName

        # SELECT 子句
        selectParts: list[str] = []
        if intent.isAggregate:
            func = intent.aggFunc.value if intent.aggFunc != AggFunc.NONE else "COUNT"
            aggCol = intent.aggColumn or "*"
            if func == "COUNT" and not intent.aggColumn:
                selectParts.append("COUNT(*) AS cnt")
            else:
                selectParts.append(f"{func}({aggCol}) AS agg_result")
        else:
            selectParts.append("*")

        # 分组列前置加入 SELECT
        if intent.groupColumns:
            for g in intent.groupColumns:
                if g not in selectParts:
                    selectParts.insert(0, g)

        sql = f"SELECT {', '.join(selectParts)} FROM {tableRef}"

        # JOIN
        if intent.isJoin and len(intent.joinTables) > 1:
            for jtName in intent.joinTables[1:]:
                # 找到表对象以构造 ON 条件（简化：同名列）
                jtObj = next((t for t in ctx.tables if t.tableName == jtName), None)
                if jtObj is None:
                    continue
                onCol = self._findJoinColumn(primaryTable, jtObj)
                if onCol:
                    sql += f" JOIN {jtObj.qualifiedName} ON {tableRef}.{onCol} = {jtObj.qualifiedName}.{onCol}"
                else:
                    sql += f" JOIN {jtObj.qualifiedName}"

        # WHERE
        whereParts: list[str] = []
        if slots is not None:
            trSlot = slots.get("timeRange")
            if trSlot and trSlot.isFilled and trSlot.value:
                whereParts.append(self._timeToWhere(trSlot.value, primaryTable))
        if intent.filterColumns:
            for fc in intent.filterColumns:
                # 不构造具体值，仅占位（避免幻觉）
                pass
        if whereParts:
            sql += " WHERE " + " AND ".join(whereParts)

        # GROUP BY
        if intent.groupColumns:
            sql += " GROUP BY " + ", ".join(intent.groupColumns)

        # ORDER BY
        if intent.sortColumn:
            direction = intent.sortDirection or "asc"
            sql += f" ORDER BY {intent.sortColumn} {direction}"

        # LIMIT
        limit = intent.limit or self.settings.defaultLimit
        if slots is not None:
            limSlot = slots.get("limit")
            if limSlot and limSlot.isFilled and isinstance(limSlot.value, int):
                limit = limSlot.value
        sql += f" LIMIT {limit}"

        sql += ";"
        return sql

    @staticmethod
    def _findJoinColumn(t1, t2) -> Optional[str]:
        """寻找两表的同名列作为 join 键."""
        names1 = {c.name for c in t1.columns}
        for c in t2.columns:
            if c.name in names1:
                return c.name
        return None

    @staticmethod
    def _timeToWhere(timeRange: str, table) -> str:
        """将时间范围转为 WHERE 条件（简化实现）.

        注：真实场景应解析为具体日期，这里用占位符 dt 列。
        """
        dtCol = "dt"
        # 优先用表的分区键
        if table.partitionKeys:
            dtCol = table.partitionKeys[0]
        mapping = {
            "today": f"{dtCol} = current_date",
            "yesterday": f"{dtCol} = date_sub(current_date, 1)",
            "day_before_yesterday": f"{dtCol} = date_sub(current_date, 2)",
            "this_month": f"{dtCol} >= date_trunc('month', current_date)",
            "last_month": f"{dtCol} >= date_trunc('month', date_sub(current_date, 30))",
            "this_year": f"{dtCol} >= date_trunc('year', current_date)",
            "last_year": f"{dtCol} >= date_trunc('year', date_sub(current_date, 365))",
        }
        if timeRange in mapping:
            return mapping[timeRange]
        if timeRange.startswith("last_") and timeRange.endswith("_days"):
            n = timeRange[5:-5]
            return f"{dtCol} >= date_sub(current_date, {n})"
        if timeRange.startswith("last_") and timeRange.endswith("_months"):
            n = timeRange[5:-7]
            return f"{dtCol} >= date_sub(current_date, {int(n) * 30})"
        # 精确日期
        if len(timeRange) == 10 and timeRange[4] == "-":
            return f"{dtCol} = date '{timeRange}'"
        if len(timeRange) == 7 and timeRange[4] == "-":
            return f"{dtCol} >= date '{timeRange}-01'"
        return f"{dtCol} = date '{timeRange}'"


# ============================================================
# LangChain 生成器
# ============================================================
class LangChainSqlGenerator(BaseSqlGenerator):
    """基于 LangChain + OpenAI 兼容 LLM 的 SQL 生成器.

    通过 llm-gateway（OpenAI 兼容协议）调用大模型。
    LangChain 依赖延迟导入，未安装时回退到 Mock 生成器。
    """

    def __init__(
        self,
        settings: Settings,
        validator: SqlValidator,
        mockFallback: Optional[MockSqlGenerator] = None,
    ) -> None:
        super().__init__(settings, validator)
        self._mock = mockFallback or MockSqlGenerator(settings, validator)
        self._llm = None
        self._initError: Optional[str] = None
        self._initLlm()

    def _initLlm(self) -> None:
        """延迟初始化 LangChain LLM."""
        try:
            # 延迟导入：未安装 langchain 时优雅降级
            from langchain_openai import ChatOpenAI  # type: ignore
        except ImportError as e:
            self._initError = f"langchain_openai 未安装: {e}"
            logger.warning("LangChain LLM 初始化失败，将降级 Mock: {}", self._initError)
            return
        try:
            self._llm = ChatOpenAI(
                model=self.settings.llmModel,
                openai_api_key=self.settings.llmApiKey or "not-required",
                openai_api_base=self.settings.llmEndpoint,
                temperature=self.settings.llmTemperature,
                max_tokens=self.settings.llmMaxTokens,
                timeout=self.settings.llmTimeout,
            )
        except Exception as e:  # noqa: BLE001
            self._initError = f"LLM 构造失败: {e}"
            logger.warning("LangChain LLM 构造失败，将降级 Mock: {}", self._initError)

    async def generate(
        self,
        query: str,
        ctx: SchemaContext,
        intent: Intent,
        slots: Optional[SlotFrame] = None,
    ) -> SqlGenerationResult:
        """生成 SQL；LLM 不可用时降级 Mock."""
        if self._llm is None:
            logger.info("LLM 不可用，降级 Mock 生成")
            return await self._mock.generate(query, ctx, intent, slots)

        start = time.perf_counter()
        try:
            userPrompt = self._buildUserPrompt(query, ctx, intent, slots)
            # LangChain 同步调用，包到线程池
            import asyncio
            messages = [
                ("system", _SYSTEM_PROMPT),
                ("human", userPrompt),
            ]
            resp = await asyncio.to_thread(self._llm.invoke, messages)
            sql = self._extractSql(str(resp))
            validation = self.validator.validate(sql, ctx)
            elapsed = (time.perf_counter() - start) * 1000.0
            return SqlGenerationResult(
                sql=sql,
                intent=intent,
                validation=validation,
                slots=slots,
                needsClarification=False,
                clarificationQuestions=[],
                llmUsed=True,
                elapsedMs=elapsed,
            )
        except Exception as e:  # noqa: BLE001
            logger.warning("LangChain 生成异常，降级 Mock: {}", e)
            return await self._mock.generate(query, ctx, intent, slots)

    @staticmethod
    def _extractSql(text: str) -> str:
        """从 LLM 响应中抽取 SQL（去除 Markdown 代码块等）."""
        s = text.strip()
        # 去除 ```sql ... ``` 包裹
        if s.startswith("```"):
            lines = s.splitlines()
            # 去首行 ```sql 与末行 ```
            if lines[0].startswith("```"):
                lines = lines[1:]
            if lines and lines[-1].strip() == "```":
                lines = lines[:-1]
            s = "\n".join(lines).strip()
        # 保证末尾分号
        if not s.endswith(";"):
            s += ";"
        return s


# ============================================================
# 工厂
# ============================================================
def createGenerator(
    settings: Settings, validator: Optional[SqlValidator] = None
) -> BaseSqlGenerator:
    """根据配置创建 SQL 生成器."""
    validator = validator or SqlValidator(settings)
    if settings.isLangchainLlm:
        return LangChainSqlGenerator(settings, validator)
    return MockSqlGenerator(settings, validator)