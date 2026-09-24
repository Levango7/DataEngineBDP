"""SQL 生成（LangChain / httpx 直连 + LLM）.

职责：
    1. 基于 Schema 上下文 + 意图 + 槽位，构造 LLM prompt 生成 SQL。
    2. 支持 Mock 模式（无 LLM 依赖，基于意图 + 槽位的规则化 SQL 生成）。
    3. 支持 LangChain 模式（经 llm-gateway :8084，OpenAI 兼容协议）。
    4. 支持 http 直连模式（httpx 调 OpenAI 兼容 /v1/chat/completions，
       无 langchain 依赖；配置 LLM_API_BASE_URL/LLM_API_KEY/LLM_MODEL 即用）。
    5. 生成后调用 SqlValidator 校验，返回 SqlGenerationResult。

设计要点：
    - Mock 模式保证 `python -c "import app"` 与单测无外部依赖即可运行。
    - LangChain 模式延迟导入 langchain，避免未安装时 import 失败。
    - http 直连模式仅依赖 httpx（已在 requirements.txt）。
    - Prompt 模板内嵌（Jinja2 风格字符串），不依赖外部模板文件。
    - LLM 未配置 / 调用失败时抛 LlmNotConfiguredError / LlmCallError，
      由路由层转换为友好 HTTP 错误（服务不崩溃）。
"""

from __future__ import annotations

import re
import time
from typing import Any, Optional

from config.settings import Settings
import httpx
from loguru import logger
from models import (
    AggFunc,
    Intent,
    SchemaContext,
    SlotFrame,
    SqlGenerationResult,
)
from schema_context import SchemaContextBuilder
from sql_validator import SqlValidator


# ============================================================
# LLM 配置 / 调用异常（路由层转为友好 HTTP 错误）
# ============================================================
class LlmNotConfiguredError(RuntimeError):
    """LLM 服务未配置（如缺少 LLM_API_KEY）.

    携带面向用户的友好提示文案，路由层原样透传（HTTP 503）。
    """


class LlmCallError(RuntimeError):
    """LLM API 调用失败（网络 / 超时 / 非 2xx / 响应格式异常）.

    携带面向用户的友好提示文案，路由层原样透传（HTTP 502）。
    """


# ============================================================
# Prompt 模板
# ============================================================
_SYSTEM_PROMPT = """你是数据引擎大数据平台的 NL2SQL 引擎。
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
        tenantId: Optional[str] = None,
    ) -> SqlGenerationResult:
        """生成 SQL（子类实现）.

        Args:
            tenantId: 租户 ID，非空时生成的 SQL 必须包含 tenant_id 过滤条件，
                防止跨租户数据泄露。None 表示不做租户隔离（仅限无鉴权的本地调试）。
        """
        raise NotImplementedError

    def _buildUserPrompt(
        self,
        query: str,
        ctx: SchemaContext,
        intent: Intent,
        slots: Optional[SlotFrame],
        tenantId: Optional[str] = None,
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
        tenantClause = (
            f"必须包含 WHERE tenant_id = '{self._escapeTenantId(tenantId)}' 过滤条件（租户隔离）"
            if tenantId
            else "无租户隔离要求"
        )
        return (
            _USER_PROMPT_TEMPLATE.format(
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
            + f"\n\n### 租户隔离\n{tenantClause}"
        )

    @staticmethod
    def _escapeTenantId(tenantId: str) -> str:
        """转义租户 ID 中的单引号，防止 SQL 注入.

        将单引号替换为两个单引号（SQL 标准转义）。
        用于 LLM prompt 中无法参数化的场景。
        """
        return tenantId.replace("'", "''")


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
        tenantId: Optional[str] = None,
    ) -> SqlGenerationResult:
        start = time.perf_counter()
        sql, params = self._buildSql(ctx, intent, slots, tenantId)
        validation = self.validator.validate(sql, ctx)
        elapsed = (time.perf_counter() - start) * 1000.0
        return SqlGenerationResult(
            sql=sql,
            params=params,
            intent=intent,
            validation=validation,
            slots=slots,
            needsClarification=False,
            clarificationQuestions=[],
            llmUsed=False,
            elapsedMs=elapsed,
        )

    def _buildSql(
        self,
        ctx: SchemaContext,
        intent: Intent,
        slots: Optional[SlotFrame],
        tenantId: Optional[str] = None,
    ) -> tuple[str, list]:
        """规则化拼装 SQL.

        Args:
            tenantId: 租户 ID，非空时在 WHERE 子句中追加 tenant_id = ? 过滤条件
                （参数化查询，防止 SQL 注入），实现租户级数据隔离。
                None 时跳过（仅限无鉴权的本地调试场景）。

        Returns:
            (sql, params) 元组：sql 含 ? 占位符，params 为按序对应的参数值列表。
        """
        params: list = []
        if ctx.isEmpty:
            return "SELECT 1;", params

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
        # 租户隔离：tenantId 非空时强制追加 tenant_id 过滤，防止跨租户数据泄露
        # 使用参数化查询（? 占位符）防止 SQL 注入
        if tenantId:
            whereParts.append("tenant_id = ?")
            params.append(tenantId)
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
        return sql, params

    @staticmethod
    def _findJoinColumn(t1, t2) -> Optional[str]:
        """寻找两表的同名列作为 join 键."""
        names1 = {c.name for c in t1.columns}
        for c in t2.columns:
            if c.name in names1:
                return c.name
        return None

    # 时间范围白名单：仅允许字母数字下划线连字符和日期格式
    # 拒绝任何包含特殊字符（引号、分号、注释、空格等）的输入，防止 SQL 注入
    _TIME_RANGE_PATTERN = re.compile(r"^(?:[A-Za-z0-9_-]+|\d{4}-\d{2}-\d{2}|\d{4}-\d{2})$")

    @staticmethod
    def _timeToWhere(timeRange: str, table) -> str:
        """将时间范围转为 WHERE 条件（简化实现）.

        注：真实场景应解析为具体日期，这里用占位符 dt 列。

        安全：对 timeRange 做白名单校验，仅允许：
          - 预定义关键字（today/yesterday/this_month/last_N_days 等）
          - 精确日期格式 YYYY-MM-DD 或 YYYY-MM
          - 字母数字下划线连字符组合
        拒绝任何包含引号、分号、注释、空格等特殊字符的输入，防止 SQL 注入。
        """
        if not isinstance(timeRange, str) or not timeRange:
            raise ValueError("timeRange 不能为空")
        # 白名单校验：仅允许字母数字下划线连字符和日期格式
        if not MockSqlGenerator._TIME_RANGE_PATTERN.match(timeRange):
            raise ValueError(
                f"timeRange 包含非法字符: {timeRange!r}（仅允许字母数字下划线连字符和 YYYY-MM-DD 日期格式）"
            )
        # 长度上限：防止超长输入造成 DoS
        if len(timeRange) > 32:
            raise ValueError(f"timeRange 过长（>32 字符）: {timeRange!r}")

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
            if not n.isdigit():
                raise ValueError(f"last_N_days 中 N 必须为正整数: {timeRange!r}")
            return f"{dtCol} >= date_sub(current_date, {int(n)})"
        if timeRange.startswith("last_") and timeRange.endswith("_months"):
            n = timeRange[5:-7]
            if not n.isdigit():
                raise ValueError(f"last_N_months 中 N 必须为正整数: {timeRange!r}")
            return f"{dtCol} >= date_sub(current_date, {int(n) * 30})"
        # 精确日期 YYYY-MM-DD
        if len(timeRange) == 10 and timeRange[4] == "-" and timeRange[7] == "-":
            # 校验各段为数字
            try:
                year, month, day = int(timeRange[0:4]), int(timeRange[5:7]), int(timeRange[8:10])
                if not (1 <= month <= 12 and 1 <= day <= 31):
                    raise ValueError
            except ValueError:
                raise ValueError(f"非法日期格式: {timeRange!r}（应为 YYYY-MM-DD）")
            return f"{dtCol} = date '{timeRange}'"
        # 年月 YYYY-MM
        if len(timeRange) == 7 and timeRange[4] == "-":
            try:
                year, month = int(timeRange[0:4]), int(timeRange[5:7])
                if not (1 <= month <= 12):
                    raise ValueError
            except ValueError:
                raise ValueError(f"非法年月格式: {timeRange!r}（应为 YYYY-MM）")
            return f"{dtCol} >= date '{timeRange}-01'"
        # 其他未识别格式：拒绝（不再默认拼接到 SQL，防止注入）
        raise ValueError(
            f"无法识别的 timeRange: {timeRange!r}（支持预定义关键字、last_N_days/months、YYYY-MM-DD、YYYY-MM）"
        )


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
        tenantId: Optional[str] = None,
    ) -> SqlGenerationResult:
        """生成 SQL；LLM 不可用时降级 Mock."""
        if self._llm is None:
            logger.info("LLM 不可用，降级 Mock 生成")
            return await self._mock.generate(query, ctx, intent, slots, tenantId)

        start = time.perf_counter()
        try:
            userPrompt = self._buildUserPrompt(query, ctx, intent, slots, tenantId)
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
            return await self._mock.generate(query, ctx, intent, slots, tenantId)

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
# httpx 直连生成器（OpenAI 兼容 /v1/chat/completions）
# ============================================================
class OpenAiHttpSqlGenerator(BaseSqlGenerator):
    """基于 httpx 直连 OpenAI 兼容 API 的 SQL 生成器.

    通过 POST {LLM_API_BASE_URL}/chat/completions 调用大模型（B-1a）：
    - 兼容 OpenAI（https://api.openai.com/v1）与华为云等 OpenAI 兼容 endpoint。
    - 仅依赖 httpx，无 langchain 安装要求。
    - 复用系统 prompt（_SYSTEM_PROMPT）与 user prompt 模板（_buildUserPrompt）。
    - 未配置 LLM_API_KEY → LlmNotConfiguredError（路由层转 503 友好提示）。
    - 调用失败 / 响应异常 → LlmCallError（路由层转 502 友好提示）；
      不静默降级 Mock，避免把规则化 SQL 当成 LLM 结果误导用户。
    """

    def __init__(self, settings: Settings, validator: SqlValidator) -> None:
        super().__init__(settings, validator)
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(settings.llmTimeout),
        )

    @property
    def _chatUrl(self) -> str:
        """chat/completions 完整 URL（自动处理 /v1 后缀）."""
        return f"{self.settings.llmEndpoint}/chat/completions"

    async def generate(
        self,
        query: str,
        ctx: SchemaContext,
        intent: Intent,
        slots: Optional[SlotFrame] = None,
        tenantId: Optional[str] = None,
    ) -> SqlGenerationResult:
        """生成 SQL；未配置或调用失败时抛出友好错误."""
        if not self.settings.llmApiKey:
            raise LlmNotConfiguredError(
                "LLM 服务未配置：请设置环境变量 LLM_API_KEY（及可选 LLM_API_BASE_URL、LLM_MODEL）"
                "后重启服务，或切换 NL2SQL_LLM_MODE=mock 使用离线模式。"
            )

        start = time.perf_counter()
        userPrompt = self._buildUserPrompt(query, ctx, intent, slots, tenantId)
        content = await self._chatCompletion(
            systemPrompt=_SYSTEM_PROMPT,
            userPrompt=userPrompt,
        )
        sql = self._extractSql(content)
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

    async def _chatCompletion(self, systemPrompt: str, userPrompt: str) -> str:
        """调用 OpenAI 兼容 /chat/completions，返回首条回复文本.

        Raises:
            LlmCallError: 网络/超时/非 2xx/响应格式异常（携带友好文案）。
        """
        payload: dict[str, Any] = {
            "model": self.settings.llmModel,
            "messages": [
                {"role": "system", "content": systemPrompt},
                {"role": "user", "content": userPrompt},
            ],
            "temperature": self.settings.llmTemperature,
            "max_tokens": self.settings.llmMaxTokens,
        }
        headers = {
            "Authorization": f"Bearer {self.settings.llmApiKey}",
            "Content-Type": "application/json",
        }
        try:
            resp = await self._client.post(self._chatUrl, json=payload, headers=headers)
        except httpx.TimeoutException as e:
            raise LlmCallError(f"LLM 请求超时（>{self.settings.llmTimeout:.0f}s），请稍后重试或检查网络") from e
        except httpx.HTTPError as e:
            raise LlmCallError("LLM 服务不可达，请检查 LLM_API_BASE_URL 配置") from e

        if resp.status_code != 200:
            # 截断错误体，避免把上游海量日志透给前端
            body = resp.text[:300] if resp.text else ""
            logger.warning("LLM API 返回 {}: {}", resp.status_code, body)
            if resp.status_code in (401, 403):
                raise LlmCallError("LLM API 鉴权失败，请检查 LLM_API_KEY 是否有效")
            raise LlmCallError(f"LLM API 返回 HTTP {resp.status_code}，请稍后重试")

        try:
            data = resp.json()
            content = data["choices"][0]["message"]["content"]
        except (ValueError, KeyError, IndexError, TypeError) as e:
            raise LlmCallError("LLM API 响应格式异常，请检查模型名（LLM_MODEL）是否正确") from e
        if not isinstance(content, str) or not content.strip():
            raise LlmCallError("LLM API 返回空内容，请检查模型可用性")
        return content

    async def aclose(self) -> None:
        """关闭底层 httpx 连接池（应用 shutdown 时调用）."""
        await self._client.aclose()


# ============================================================
# 工厂
# ============================================================
def createGenerator(settings: Settings, validator: Optional[SqlValidator] = None) -> BaseSqlGenerator:
    """根据配置创建 SQL 生成器.

    模式映射：
        http      → OpenAiHttpSqlGenerator（httpx 直连，B-1a 推荐）
        langchain → LangChainSqlGenerator（LangChain SDK）
        mock/其他 → MockSqlGenerator（规则化，无外部依赖）
    """
    validator = validator or SqlValidator(settings)
    if settings.isHttpLlm:
        return OpenAiHttpSqlGenerator(settings, validator)
    if settings.isLangchainLlm:
        return LangChainSqlGenerator(settings, validator)
    return MockSqlGenerator(settings, validator)
