"""NL2SQL 引擎核心数据模型.

定义贯穿 schema_context / intent_recognition / sql_generator / sql_validator /
dialogue_clarifier / slot_filler / gateway_client 的共享数据结构。

设计原则：
    - 所有模型为 Pydantic v2 BaseModel，便于序列化与校验。
    - 字段命名采用 camelCase（与平台约定一致），通过 alias 暴露 snake_case 别名。
    - 不可变模型默认 frozen=False（对话状态需更新），但提供 with_* 更新方法。
"""

from __future__ import annotations

from enum import Enum
from typing import Any, Optional

from pydantic import BaseModel, Field


# ============================================================
# 枚举
# ============================================================
class IntentType(str, Enum):
    """查询意图类型."""

    AGGREGATION = "aggregation"  # 聚合：count/sum/avg/max/min
    FILTER = "filter"  # 过滤：WHERE 条件
    JOIN = "join"  # 多表连接
    SORT = "sort"  # 排序：ORDER BY
    GROUP = "group"  # 分组：GROUP BY
    LIMIT = "limit"  # 行数限制
    SIMPLE_SELECT = "simple_select"  # 简单查询
    UNKNOWN = "unknown"


class AggFunc(str, Enum):
    """聚合函数."""

    COUNT = "COUNT"
    SUM = "SUM"
    AVG = "AVG"
    MAX = "MAX"
    MIN = "MIN"
    NONE = "NONE"


class SlotStatus(str, Enum):
    """槽位状态."""

    FILLED = "filled"  # 已填充
    MISSING = "missing"  # 缺失（必需）
    OPTIONAL = "optional"  # 可选未填
    AMBIGUOUS = "ambiguous"  # 模糊需澄清


class ValidationLevel(str, Enum):
    """校验结果级别."""

    OK = "ok"
    WARNING = "warning"
    ERROR = "error"


# ============================================================
# Schema 上下文模型
# ============================================================
class ColumnSchema(BaseModel):
    """列定义."""

    name: str = Field(description="列名")
    type: str = Field(description="数据类型，如 bigint/string/date")
    nullable: bool = Field(default=True, description="是否允许 NULL")
    comment: Optional[str] = Field(default=None, description="列注释")
    isPartitionKey: bool = Field(default=False, description="是否分区键")


class TableSchema(BaseModel):
    """表定义."""

    databaseName: str = Field(description="数据库名")
    tableName: str = Field(description="表名")
    columns: list[ColumnSchema] = Field(default_factory=list, description="列列表")
    partitionKeys: list[str] = Field(default_factory=list, description="分区键名")
    comment: Optional[str] = Field(default=None, description="表注释")

    @property
    def qualifiedName(self) -> str:
        """全限定表名 database.table."""
        return f"{self.databaseName}.{self.tableName}"

    @property
    def columnNames(self) -> list[str]:
        """列名列表."""
        return [c.name for c in self.columns]


class SchemaContext(BaseModel):
    """Schema 上下文（供 LLM prompt 使用）.

    由 SchemaContextBuilder 从 Catalog 拉取并裁剪后构建。
    """

    tables: list[TableSchema] = Field(default_factory=list, description="相关表 schema")
    database: Optional[str] = Field(default=None, description="当前数据库")
    foreignKeys: list[dict[str, str]] = Field(
        default_factory=list,
        description="外键关系，每项 {from: 'db.t1.c1', to: 'db.t2.c2'}",
    )

    @property
    def tableNames(self) -> list[str]:
        """表名列表（含库名前缀）."""
        return [t.qualifiedName for t in self.tables]

    @property
    def isEmpty(self) -> bool:
        """是否为空上下文."""
        return not self.tables


# ============================================================
# 意图识别模型
# ============================================================
class Intent(BaseModel):
    """识别出的查询意图."""

    primaryType: IntentType = Field(default=IntentType.UNKNOWN, description="主意图类型")
    aggFunc: AggFunc = Field(default=AggFunc.NONE, description="聚合函数")
    aggColumn: Optional[str] = Field(default=None, description="聚合列名")
    filterColumns: list[str] = Field(default_factory=list, description="过滤列")
    joinTables: list[str] = Field(default_factory=list, description="Join 涉及表")
    sortColumn: Optional[str] = Field(default=None, description="排序列")
    sortDirection: Optional[str] = Field(default=None, description="排序方向 asc/desc")
    groupColumns: list[str] = Field(default_factory=list, description="分组列")
    limit: Optional[int] = Field(default=None, description="行数限制")
    confidence: float = Field(default=0.0, ge=0.0, le=1.0, description="置信度")
    rawEntities: dict[str, Any] = Field(default_factory=dict, description="原始抽取实体（关键词匹配结果）")

    @property
    def isAggregate(self) -> bool:
        return self.primaryType == IntentType.AGGREGATION

    @property
    def isJoin(self) -> bool:
        return self.primaryType == IntentType.JOIN or len(self.joinTables) > 1


# ============================================================
# 槽位填充模型
# ============================================================
class Slot(BaseModel):
    """单个槽位."""

    name: str = Field(description="槽位名，如 timeRange/aggColumn/filterValue")
    value: Any = Field(default=None, description="槽位值")
    status: SlotStatus = Field(default=SlotStatus.MISSING, description="状态")
    required: bool = Field(default=True, description="是否必需")
    description: str = Field(default="", description="槽位描述（用于生成澄清问题）")
    promptQuestion: Optional[str] = Field(default=None, description="向用户提问的澄清问题文本")

    @property
    def isFilled(self) -> bool:
        return self.status == SlotStatus.FILLED and self.value is not None

    @property
    def needsClarify(self) -> bool:
        return self.status in (SlotStatus.MISSING, SlotStatus.AMBIGUOUS) and self.required


class SlotFrame(BaseModel):
    """槽位框架（一次查询的槽位集合）."""

    slots: list[Slot] = Field(default_factory=list, description="槽位列表")
    intent: Optional[Intent] = Field(default=None, description="关联意图")

    def filledSlots(self) -> list[Slot]:
        """已填充槽位."""
        return [s for s in self.slots if s.isFilled]

    def missingSlots(self) -> list[Slot]:
        """缺失且必需的槽位."""
        return [s for s in self.slots if s.needsClarify]

    @property
    def isComplete(self) -> bool:
        """所有必需槽位是否已填充."""
        return not self.missingSlots()

    def get(self, name: str) -> Optional[Slot]:
        """按名取槽位."""
        for s in self.slots:
            if s.name == name:
                return s
        return None

    def set(self, name: str, value: Any) -> None:
        """设置槽位值并标记为已填充."""
        slot = self.get(name)
        if slot is not None:
            slot.value = value
            slot.status = SlotStatus.FILLED


# ============================================================
# 对话状态模型
# ============================================================
class DialogueTurn(BaseModel):
    """单轮对话记录."""

    role: str = Field(description="user / assistant")
    content: str = Field(description="文本内容")
    intent: Optional[Intent] = Field(default=None, description="该轮意图（user 轮）")
    sql: Optional[str] = Field(default=None, description="生成的 SQL（assistant 轮）")
    slots: Optional[SlotFrame] = Field(default=None, description="槽位框架（user 轮）")


class DialogueState(BaseModel):
    """多轮对话状态."""

    sessionId: str = Field(description="会话 ID")
    turns: list[DialogueTurn] = Field(default_factory=list, description="对话历史")
    currentSlots: Optional[SlotFrame] = Field(default=None, description="当前槽位框架")
    database: Optional[str] = Field(default=None, description="目标数据库")
    clarified: bool = Field(default=False, description="是否已完成澄清")
    turnCount: int = Field(default=0, description="已交互轮次")

    def addTurn(self, turn: DialogueTurn) -> None:
        """追加一轮对话."""
        self.turns.append(turn)
        self.turnCount = len(self.turns)


# ============================================================
# SQL 校验模型
# ============================================================
class ValidationIssue(BaseModel):
    """单条校验问题."""

    level: ValidationLevel = Field(description="级别")
    message: str = Field(description="问题描述")
    code: Optional[str] = Field(default=None, description="错误码")


class ValidationResult(BaseModel):
    """SQL 校验结果."""

    valid: bool = Field(description="是否通过")
    issues: list[ValidationIssue] = Field(default_factory=list, description="问题列表")
    parsedSql: Optional[str] = Field(default=None, description="规范化后的 SQL")

    @property
    def hasError(self) -> bool:
        return any(i.level == ValidationLevel.ERROR for i in self.issues)

    @property
    def hasWarning(self) -> bool:
        return any(i.level == ValidationLevel.WARNING for i in self.issues)

    def errorMessages(self) -> list[str]:
        return [i.message for i in self.issues if i.level == ValidationLevel.ERROR]


# ============================================================
# SQL 生成结果
# ============================================================
class SqlGenerationResult(BaseModel):
    """SQL 生成结果."""

    sql: str = Field(description="生成的 SQL")
    intent: Optional[Intent] = Field(default=None, description="识别意图")
    validation: Optional[ValidationResult] = Field(default=None, description="校验结果")
    slots: Optional[SlotFrame] = Field(default=None, description="槽位框架")
    needsClarification: bool = Field(default=False, description="是否需要澄清")
    clarificationQuestions: list[str] = Field(default_factory=list, description="澄清问题列表")
    llmUsed: bool = Field(default=False, description="是否实际调用 LLM")
    elapsedMs: float = Field(default=0.0, ge=0.0, description="耗时毫秒")


# ============================================================
# SQL 网关执行结果
# ============================================================
class GatewayExecuteResult(BaseModel):
    """SQL 网关执行结果."""

    queryId: Optional[str] = Field(default=None, description="网关查询 ID")
    status: str = Field(default="UNKNOWN", description="执行状态")
    columns: list[str] = Field(default_factory=list, description="列名")
    rows: list[dict[str, Any]] = Field(default_factory=list, description="结果行")
    durationMs: float = Field(default=0.0, ge=0.0, description="执行耗时毫秒")
    engine: Optional[str] = Field(default=None, description="实际引擎")
    error: Optional[str] = Field(default=None, description="错误信息")

    @property
    def isSuccess(self) -> bool:
        return self.status in ("OK", "SUCCESS", "SIMULATED") and self.error is None
