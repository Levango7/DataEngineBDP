"""Schema 上下文构建器.

职责：
    1. 从 Catalog 服务（Go :8082）拉取数据库 / 表 / 列元数据。
    2. 根据用户自然语言查询，裁剪相关表与列，构建 LLM prompt 上下文。
    3. 生成 Markdown / DDL 两种风格的上下文文本，供 SQL 生成 prompt 使用。
    4. Catalog 不可达时降级到内置 Mock schema，保证服务可用（接口抽象 + Mock 策略）。

对接端点（参见 platform/catalog/README.md）：
    GET /api/v1/catalog/databases
    GET /api/v1/catalog/tables?database=xxx
    GET /api/v1/catalog/tables/{id}
"""

from __future__ import annotations

from typing import Optional

from config.settings import Settings
import httpx
from loguru import logger
from models import ColumnSchema, SchemaContext, TableSchema

# ============================================================
# Mock schema（Catalog 不可达时降级使用）
# ============================================================
_MOCK_TABLES: list[dict] = [
    {
        "databaseName": "default",
        "tableName": "orders",
        "comment": "订单表",
        "columns": [
            {"name": "order_id", "type": "bigint", "nullable": False, "comment": "订单ID"},
            {"name": "user_id", "type": "bigint", "nullable": False, "comment": "用户ID"},
            {"name": "amount", "type": "decimal(18,2)", "nullable": False, "comment": "订单金额"},
            {"name": "status", "type": "string", "nullable": False, "comment": "订单状态"},
            {"name": "dt", "type": "date", "nullable": False, "comment": "业务日期"},
        ],
        "partitionKeys": ["dt"],
    },
    {
        "databaseName": "default",
        "tableName": "users",
        "comment": "用户表",
        "columns": [
            {"name": "user_id", "type": "bigint", "nullable": False, "comment": "用户ID"},
            {"name": "name", "type": "string", "nullable": False, "comment": "用户名"},
            {"name": "age", "type": "int", "nullable": True, "comment": "年龄"},
            {"name": "city", "type": "string", "nullable": True, "comment": "城市"},
            {"name": "dt", "type": "date", "nullable": False, "comment": "业务日期"},
        ],
        "partitionKeys": ["dt"],
    },
    {
        "databaseName": "default",
        "tableName": "products",
        "comment": "商品表",
        "columns": [
            {"name": "product_id", "type": "bigint", "nullable": False, "comment": "商品ID"},
            {"name": "name", "type": "string", "nullable": False, "comment": "商品名"},
            {"name": "category", "type": "string", "nullable": True, "comment": "类目"},
            {"name": "price", "type": "decimal(18,2)", "nullable": False, "comment": "价格"},
        ],
        "partitionKeys": [],
    },
]


def _mockTableSchema(d: dict) -> TableSchema:
    """从 Mock dict 构建 TableSchema."""
    cols = [
        ColumnSchema(
            name=c["name"],
            type=c["type"],
            nullable=c.get("nullable", True),
            comment=c.get("comment"),
            isPartitionKey=c["name"] in d.get("partitionKeys", []),
        )
        for c in d["columns"]
    ]
    return TableSchema(
        databaseName=d["databaseName"],
        tableName=d["tableName"],
        columns=cols,
        partitionKeys=d.get("partitionKeys", []),
        comment=d.get("comment"),
    )


# ============================================================
# Schema 上下文构建器
# ============================================================
class SchemaContextBuilder:
    """Schema 上下文构建器.

    通过 httpx 调用 Catalog 服务获取元数据，并裁剪为 LLM 可用的上下文。
    Catalog 不可达时降级到 Mock schema，保证服务可用。
    """

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._baseUrl = settings.catalogUrl.rstrip("/")
        self._timeout = settings.catalogTimeout

    # ---- Catalog HTTP 调用 ----
    async def _listDatabases(self) -> list[str]:
        """列出所有数据库名."""
        url = f"{self._baseUrl}/api/v1/catalog/databases"
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            resp = await client.get(url)
            resp.raise_for_status()
            data = resp.json()
        # Catalog 返回结构兼容：[{id,name,...}] 或 {databases:[...]}
        if isinstance(data, list):
            return [d.get("name") or d.get("id") for d in data if d]
        if isinstance(data, dict) and "databases" in data:
            return [d.get("name") or d.get("id") for d in data["databases"] if d]
        return []

    async def _listTables(self, database: Optional[str] = None) -> list[TableSchema]:
        """列出表 schema."""
        url = f"{self._baseUrl}/api/v1/catalog/tables"
        params = {"database": database} if database else None
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            resp = await client.get(url, params=params)
            resp.raise_for_status()
            data = resp.json()
        items = data if isinstance(data, list) else data.get("tables", [])
        return [self._parseTableItem(t) for t in items if t]

    def _parseTableItem(self, t: dict) -> TableSchema:
        """解析 Catalog 返回的表项为 TableSchema."""
        cols = []
        for c in t.get("columns", []):
            cols.append(
                ColumnSchema(
                    name=c.get("name", ""),
                    type=c.get("type", "string"),
                    nullable=c.get("nullable", True),
                    comment=c.get("comment"),
                    isPartitionKey=c.get("name") in t.get("partitionKeys", []),
                )
            )
        return TableSchema(
            databaseName=t.get("databaseName") or t.get("database") or "default",
            tableName=t.get("tableName") or t.get("name", ""),
            columns=cols,
            partitionKeys=t.get("partitionKeys", []),
            comment=t.get("comment"),
        )

    # ---- Mock 降级 ----
    def _mockTables(self, database: Optional[str] = None) -> list[TableSchema]:
        """获取 Mock 表 schema."""
        tables = [_mockTableSchema(d) for d in _MOCK_TABLES]
        if database:
            tables = [t for t in tables if t.databaseName == database]
        return tables

    # ---- 公共 API ----
    async def fetchTables(self, database: Optional[str] = None, useMock: bool = False) -> list[TableSchema]:
        """拉取表 schema.

        Args:
            database: 指定数据库，None 表示全部。
            useMock: 强制使用 Mock schema（测试 / 离线场景）。

        Returns:
            表 schema 列表。
        """
        if useMock:
            return self._mockTables(database)
        try:
            return await self._listTables(database)
        except Exception as e:  # noqa: BLE001
            logger.warning("Catalog 不可达，降级 Mock schema: {} | error={}", self._baseUrl, e)
            return self._mockTables(database)

    async def buildContext(
        self,
        query: str,
        database: Optional[str] = None,
        tableHints: Optional[list[str]] = None,
        useMock: bool = False,
    ) -> SchemaContext:
        """根据自然语言查询构建 Schema 上下文.

        策略：
            1. 拉取数据库下所有表 schema。
            2. 若提供 tableHints，按 hint 过滤；否则按查询文本中出现的表名/列名做相关性裁剪。
            3. 限制表数不超过 settings.maxTables。

        Args:
            query: 用户自然语言查询。
            database: 目标数据库。
            tableHints: 显式指定的表名提示。
            useMock: 强制 Mock。

        Returns:
            SchemaContext。
        """
        allTables = await self.fetchTables(database=database, useMock=useMock)
        if not allTables:
            return SchemaContext(tables=[], database=database)

        selected = self._selectTables(query, allTables, tableHints)
        # 限制表数量
        if len(selected) > self.settings.maxTables:
            logger.info(
                "Schema 上下文裁剪: {} -> {} 表（上限 {}）",
                len(selected),
                self.settings.maxTables,
                self.settings.maxTables,
            )
            selected = selected[: self.settings.maxTables]

        return SchemaContext(tables=selected, database=database)

    def _selectTables(
        self,
        query: str,
        allTables: list[TableSchema],
        tableHints: Optional[list[str]] = None,
    ) -> list[TableSchema]:
        """根据查询文本与提示选择相关表.

        选择规则（优先级从高到低）：
            1. tableHints 显式匹配（表名或全限定名）。
            2. 查询文本包含表名 / 列名 / 表注释关键词。
            3. 无任何匹配时返回全部表（让 LLM 自行判断）。
        """
        queryLower = query.lower()
        # 1. hints
        if tableHints:
            hintsLower = {h.lower() for h in tableHints}
            matched = [
                t for t in allTables if t.tableName.lower() in hintsLower or t.qualifiedName.lower() in hintsLower
            ]
            if matched:
                return matched

        # 2. 关键词匹配
        matched: list[TableSchema] = []
        for t in allTables:
            candidates = [t.tableName.lower()]
            if t.comment:
                candidates.append(t.comment.lower())
            for c in t.columns:
                candidates.append(c.name.lower())
                if c.comment:
                    candidates.append(c.comment.lower())
            if any(k and k in queryLower for k in candidates):
                matched.append(t)
        if matched:
            return matched

        # 3. 全部返回
        return allTables

    # ---- 上下文渲染 ----
    @staticmethod
    def renderMarkdown(ctx: SchemaContext) -> str:
        """渲染为 Markdown 风格的 schema 描述（供 LLM prompt）."""
        if ctx.isEmpty:
            return "(无可用 schema 上下文)"
        lines: list[str] = []
        if ctx.database:
            lines.append(f"### 数据库: `{ctx.database}`")
        for t in ctx.tables:
            lines.append(f"#### 表 `{t.qualifiedName}`")
            if t.comment:
                lines.append(f"  说明: {t.comment}")
            lines.append("  | 列名 | 类型 | 可空 | 分区键 | 说明 |")
            lines.append("  | --- | --- | --- | --- | --- |")
            for c in t.columns:
                lines.append(
                    f"  | {c.name} | {c.type} | {'是' if c.nullable else '否'}"
                    f" | {'是' if c.isPartitionKey else '否'} | {c.comment or ''} |"
                )
            if t.partitionKeys:
                lines.append(f"  分区键: {', '.join(t.partitionKeys)}")
            lines.append("")
        if ctx.foreignKeys:
            lines.append("### 外键关系")
            for fk in ctx.foreignKeys:
                lines.append(f"  - {fk.get('from')} → {fk.get('to')}")
        return "\n".join(lines)

    @staticmethod
    def renderDdl(ctx: SchemaContext) -> str:
        """渲染为 DDL 风格的 schema 描述（更紧凑，供 LLM prompt）."""
        if ctx.isEmpty:
            return "(无可用 schema 上下文)"
        lines: list[str] = []
        for t in ctx.tables:
            colDefs = []
            for c in t.columns:
                parts = [f"{c.name} {c.type}"]
                if not c.nullable:
                    parts.append("NOT NULL")
                if c.comment:
                    parts.append(f"COMMENT '{c.comment}'")
                colDefs.append("  " + ", ".join(parts))
            parts = [
                f"CREATE TABLE {t.qualifiedName} (",
                ",\n".join(colDefs),
                ")",
            ]
            if t.partitionKeys:
                parts.append(f"PARTITIONED BY ({', '.join(t.partitionKeys)})")
            if t.comment:
                parts.append(f"COMMENT '{t.comment}'")
            lines.append("\n".join(parts) + ";")
        return "\n\n".join(lines)
