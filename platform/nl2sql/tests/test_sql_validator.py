"""SQL 校验单测."""

from __future__ import annotations

from models import ColumnSchema, SchemaContext, TableSchema, ValidationLevel
from sql_validator import SqlValidator


def _mockCtx() -> SchemaContext:
    return SchemaContext(
        database="default",
        tables=[
            TableSchema(
                databaseName="default",
                tableName="orders",
                columns=[
                    ColumnSchema(name="order_id", type="bigint"),
                    ColumnSchema(name="amount", type="decimal"),
                ],
            ),
        ],
    )


class TestSqlValidator:
    def test_valid_select(self, validator: SqlValidator) -> None:
        r = validator.validate("SELECT * FROM default.orders LIMIT 10;")
        assert r.valid is True
        assert not r.hasError

    def test_empty_sql(self, validator: SqlValidator) -> None:
        r = validator.validate("")
        assert r.valid is False
        assert any(i.code == "EMPTY_SQL" for i in r.issues)

    def test_select_only_rejects_insert(self, validator: SqlValidator) -> None:
        r = validator.validate("INSERT INTO orders VALUES (1);")
        assert r.valid is False
        assert any(i.code == "NON_SELECT_STMT" for i in r.issues)

    def test_select_only_rejects_delete(self, validator: SqlValidator) -> None:
        r = validator.validate("DELETE FROM orders;")
        assert r.valid is False
        assert any(i.code == "NON_SELECT_STMT" for i in r.issues)

    def test_select_only_rejects_drop(self, validator: SqlValidator) -> None:
        r = validator.validate("DROP TABLE orders;")
        assert r.valid is False

    def test_unbalanced_paren(self, validator: SqlValidator) -> None:
        r = validator.validate("SELECT * FROM orders WHERE (a = 1;")
        assert r.valid is False
        assert any(i.code == "UNBALANCED_PAREN" for i in r.issues)

    def test_missing_semicolon_warning(self, validator: SqlValidator) -> None:
        r = validator.validate("SELECT * FROM default.orders LIMIT 10")
        # 末尾分号缺失仅为 warning
        assert any(i.code == "MISSING_SEMICOLON" and i.level == ValidationLevel.WARNING for i in r.issues)

    def test_table_not_found_warning(self, validator: SqlValidator) -> None:
        ctx = _mockCtx()
        r = validator.validate("SELECT * FROM unknown_table LIMIT 10;", ctx)
        # 表不存在为 warning，不致错
        assert any(i.code == "TABLE_NOT_FOUND" for i in r.issues)

    def test_table_found(self, validator: SqlValidator) -> None:
        ctx = _mockCtx()
        r = validator.validate("SELECT * FROM orders LIMIT 10;", ctx)
        # orders 在 ctx 中，不应有 TABLE_NOT_FOUND
        assert not any(i.code == "TABLE_NOT_FOUND" for i in r.issues)

    def test_parsed_sql_normalized(self, validator: SqlValidator) -> None:
        r = validator.validate("SELECT   *   FROM   default.orders   LIMIT   10;")
        assert r.parsedSql is not None
        # 多空白应被合并
        assert "  " not in r.parsedSql

    def test_select_only_disabled(self, settings) -> None:
        from sql_validator import SqlValidator

        s = settings
        s.selectOnly = False
        v = SqlValidator(s)
        r = v.validate("INSERT INTO orders VALUES (1);")
        # 关闭 select-only 后，INSERT 不再被拒绝（但仍可能因其他检查告警）
        assert not any(i.code == "NON_SELECT_STMT" for i in r.issues)

    def test_multi_statement_rejected(self, validator: SqlValidator) -> None:
        r = validator.validate("SELECT 1; SELECT 2;")
        assert r.valid is False
        assert any(i.code == "MULTI_STMT" for i in r.issues)

    def test_select_then_drop_smuggled(self, validator: SqlValidator) -> None:
        # 首条为合法 SELECT、第二条夹带 DROP：不得借首条绕过检查
        r = validator.validate("SELECT * FROM orders; DROP TABLE orders;")
        assert r.valid is False
        assert any(i.code in ("MULTI_STMT", "NON_SELECT_STMT") for i in r.issues)


class TestParenScanIgnoresLiteralsAndComments:
    """括号计数必须剥离字符串字面量与注释，避免误判 UNBALANCED_PAREN."""

    def test_paren_in_string_literal_not_counted(self, validator: SqlValidator) -> None:
        r = validator.validate("SELECT '(' AS p FROM default.orders WHERE b = ')';")
        assert not any(i.code == "UNBALANCED_PAREN" for i in r.issues)
        assert r.valid is True

    def test_escaped_quote_literal_with_paren(self, validator: SqlValidator) -> None:
        r = validator.validate("SELECT 'it''s (ok)' FROM default.orders;")
        assert r.valid is True

    def test_double_quoted_literal_with_paren(self, validator: SqlValidator) -> None:
        r = validator.validate('SELECT "((" FROM default.orders;')
        assert r.valid is True

    def test_parens_in_line_and_block_comments_not_counted(self, validator: SqlValidator) -> None:
        sql = (
            "SELECT * FROM default.orders -- stray ) ) (\n"
            "# more (((\n"
            "WHERE a = '(' /* )))( */ LIMIT 10;"
        )
        r = validator.validate(sql)
        assert not any(i.code == "UNBALANCED_PAREN" for i in r.issues)
        assert r.valid is True

    def test_real_unbalanced_still_detected_with_literals(self, validator: SqlValidator) -> None:
        r = validator.validate("SELECT * FROM default.orders WHERE (a = '(' LIMIT 10;")
        assert any(i.code == "UNBALANCED_PAREN" for i in r.issues)

    def test_extra_close_paren_after_commented_one_detected(self, validator: SqlValidator) -> None:
        r = validator.validate("SELECT * FROM default.orders -- (\nWHERE b = ')') ;")
        # 剥离注释后：b = ')' 中括号在字面量内，末尾 ')' 多余 → 报错
        assert any(i.code == "UNBALANCED_PAREN" for i in r.issues)

    def test_unterminated_quote_tolerated_raw_tail_counted(self, validator: SqlValidator) -> None:
        # 引号未闭合到 EOF：按原文返回，尾部括号参与计数，不崩溃不吞错
        r = validator.validate("SELECT * FROM default.orders WHERE a = 'x ( y")
        assert any(i.code == "UNBALANCED_PAREN" for i in r.issues)

    def test_unterminated_quote_without_parens_no_paren_error(self, validator: SqlValidator) -> None:
        r = validator.validate("SELECT * FROM default.orders WHERE a = 'unclosed")
        assert not any(i.code == "UNBALANCED_PAREN" for i in r.issues)

    def test_scanner_strips_literals_and_comments(self, validator: SqlValidator) -> None:
        stripped = SqlValidator._stripLiteralsAndComments(
            "SELECT '(' -- )(\nFROM t /* (#) */ WHERE x = \")\";"
        )
        assert "(" not in stripped
        assert ")" not in stripped
        assert "--" not in stripped
        assert "'" not in stripped
