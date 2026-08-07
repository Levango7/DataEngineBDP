"""SQL 语法校验.

职责：
    1. 使用 sqlparse 解析 SQL，校验语法正确性。
    2. 安全护栏：默认仅允许 SELECT 语句（防注入 / 防误删）。
    3. 表名 / 列名存在性校验（基于 SchemaContext）。
    4. 返回结构化 ValidationResult，含问题列表与规范化 SQL。

设计要点：
    - 不连接真实引擎做 EXPLAIN，仅做静态校验（轻量、无外部依赖）。
    - sqlparse 解析失败即视为语法错误。
    - SELECT-only 模式下，检测到 DML/DDL 直接拒绝。
"""
from __future__ import annotations

import re
from typing import Optional

import sqlparse
from sqlparse.sql import Statement
from sqlparse.tokens import Keyword

from models import SchemaContext, ValidationIssue, ValidationLevel, ValidationResult
from config.settings import Settings


# 危险语句关键词（SELECT-only 模式下禁止）
_DANGEROUS_KEYWORDS = {
    "INSERT", "UPDATE", "DELETE", "DROP", "TRUNCATE", "ALTER",
    "CREATE", "GRANT", "REVOKE", "MERGE", "REPLACE",
}


class SqlValidator:
    """SQL 语法校验器."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings

    def validate(
        self,
        sql: str,
        ctx: Optional[SchemaContext] = None,
    ) -> ValidationResult:
        """校验 SQL.

        Args:
            sql: 待校验 SQL 文本。
            ctx: Schema 上下文（用于表名/列名存在性校验，可空）。

        Returns:
            ValidationResult。
        """
        issues: list[ValidationIssue] = []
        if not sql or not sql.strip():
            return ValidationResult(
                valid=False,
                issues=[ValidationIssue(
                    level=ValidationLevel.ERROR, message="SQL 为空", code="EMPTY_SQL"
                )],
            )

        # 1. sqlparse 解析
        parsed = sqlparse.parse(sql)
        if not parsed:
            issues.append(ValidationIssue(
                level=ValidationLevel.ERROR,
                message="SQL 解析失败：sqlparse 返回空",
                code="PARSE_EMPTY",
            ))
            return ValidationResult(valid=False, issues=issues)

        stmt: Statement = parsed[0]
        normalized = self._normalize(stmt)

        # 2. SELECT-only 安全护栏
        if self.settings.selectOnly:
            issue = self._checkSelectOnly(stmt)
            if issue is not None:
                issues.append(issue)
                return ValidationResult(valid=False, issues=issues, parsedSql=normalized)

        # 3. 语法基础检查（括号配对、末尾分号）
        issues.extend(self._basicSyntaxCheck(sql))

        # 4. 表名 / 列名存在性检查
        if ctx is not None and not ctx.isEmpty:
            issues.extend(self._checkTablesExist(stmt, ctx))

        # 5. 汇总
        hasError = any(i.level == ValidationLevel.ERROR for i in issues)
        return ValidationResult(
            valid=not hasError,
            issues=issues,
            parsedSql=normalized,
        )

    # ---- SELECT-only 检查 ----
    def _checkSelectOnly(self, stmt: Statement) -> Optional[ValidationIssue]:
        """检查是否为 SELECT 语句；若包含危险关键词则拒绝."""
        tokens = list(stmt.flatten())
        # 取第一个有意义的 DML/DDL 关键词
        firstKw: Optional[str] = None
        for tok in tokens:
            if tok.ttype is Keyword.DML or tok.ttype is Keyword.DDL:
                firstKw = tok.value.upper()
                break
            # 兜底：匹配 Keyword 中的 DML/DDL
            if tok.ttype in Keyword and tok.value.upper() in _DANGEROUS_KEYWORDS | {"SELECT", "WITH"}:
                firstKw = tok.value.upper()
                break
        if firstKw is None:
            # 无法识别语句类型，按警告处理
            return ValidationIssue(
                level=ValidationLevel.WARNING,
                message="无法识别 SQL 语句类型，建议以 SELECT 开头",
                code="UNKNOWN_STMT_TYPE",
            )
        if firstKw in _DANGEROUS_KEYWORDS:
            return ValidationIssue(
                level=ValidationLevel.ERROR,
                message=f"SELECT-only 模式禁止 {firstKw} 语句",
                code="NON_SELECT_STMT",
            )
        return None

    # ---- 基础语法检查 ----
    def _basicSyntaxCheck(self, sql: str) -> list[ValidationIssue]:
        """基础语法检查：括号配对、末尾分号."""
        issues: list[ValidationIssue] = []
        # 括号配对
        depth = 0
        for ch in sql:
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth < 0:
                    issues.append(ValidationIssue(
                        level=ValidationLevel.ERROR,
                        message="括号不配对：出现多余的 ')'",
                        code="UNBALANCED_PAREN",
                    ))
                    return issues
        if depth != 0:
            issues.append(ValidationIssue(
                level=ValidationLevel.ERROR,
                message=f"括号不配对：剩余 {depth} 个未闭合 '('",
                code="UNBALANCED_PAREN",
            ))
        # 末尾分号（警告）
        if not sql.strip().rstrip().endswith(";"):
            issues.append(ValidationIssue(
                level=ValidationLevel.WARNING,
                message="SQL 末尾建议以 ';' 结尾",
                code="MISSING_SEMICOLON",
            ))
        return issues

    # ---- 表名存在性检查 ----
    def _checkTablesExist(
        self, stmt: Statement, ctx: SchemaContext
    ) -> list[ValidationIssue]:
        """检查 SQL 中引用的表名是否存在于 schema 上下文."""
        issues: list[ValidationIssue] = []
        knownTables = set()
        for t in ctx.tables:
            knownTables.add(t.tableName.lower())
            knownTables.add(t.qualifiedName.lower())

        referenced = self._extractTableNames(stmt)
        for ref in referenced:
            if ref.lower() not in knownTables:
                issues.append(ValidationIssue(
                    level=ValidationLevel.WARNING,
                    message=f"表 '{ref}' 未在 schema 上下文中找到",
                    code="TABLE_NOT_FOUND",
                ))
        return issues

    @staticmethod
    def _extractTableNames(stmt: Statement) -> list[str]:
        """从 SQL 中抽取 FROM / JOIN 后的表名."""
        tables: list[str] = []
        tokens = list(stmt.flatten())
        # 简化实现：寻找 FROM / JOIN 后的 Identifier token
        for i, tok in enumerate(tokens):
            val = tok.value.upper().strip()
            if val in ("FROM", "JOIN"):
                # 向后找第一个非空白 Name token
                for j in range(i + 1, len(tokens)):
                    nxt = tokens[j]
                    if nxt.is_whitespace:
                        continue
                    if nxt.ttype in (sqlparse.tokens.Name, sqlparse.tokens.Name.Placeholder):
                        tables.append(nxt.value)
                    elif nxt.ttype is None and hasattr(nxt, "tokens"):
                        # Identifier 或 IdentifierList
                        for sub in nxt.tokens:
                            if sub.ttype in (sqlparse.tokens.Name,):
                                tables.append(sub.value)
                    break
        return tables

    # ---- 规范化 ----
    @staticmethod
    def _normalize(stmt: Statement) -> str:
        """规范化 SQL：去除多余空白、统一大小写关键字."""
        sql = str(stmt)
        # 多空白合一
        sql = re.sub(r"\s+", " ", sql).strip()
        return sql