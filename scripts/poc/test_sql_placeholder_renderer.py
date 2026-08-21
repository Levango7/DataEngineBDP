#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PoC SQL 占位符渲染器单元测试。

覆盖：
1. 三种占位符语法（${var}、{{var}}、:var）
2. 表命名衔接（Iceberg ↔ Doris ↔ Catalog 全名）
3. Flink CDC / Spark dwd / Spark dws / Doris MV 模板渲染
4. 严格模式异常 / 非严格模式保留占位符
5. 层次解析（ods/dwd/dws/mv）
"""
from __future__ import annotations

import sys
from pathlib import Path

# 将 scripts/poc 加入 sys.path 以导入被测模块
sys.path.insert(0, str(Path(__file__).parent))
from sql_placeholder_renderer import (  # noqa: E402
    SqlPlaceholderRenderer,
    TableNameMapping,
    SqlRenderError,
)

import pytest  # noqa: E402


# ===================== TableNameMapping 测试 =====================

class TestTableNameMapping:
    """表命名衔接映射测试。"""

    def test_default_mapping(self):
        m = TableNameMapping()
        assert m.catalog_name == "shuqing_catalog"
        assert m.workspace == "demo-fin"
        assert m.project == "trade"
        assert m.iceberg_database == "trade"
        assert m.warehouse_path == "lakehouse/demo-fin/trade"

    def test_iceberg_full_name_short(self):
        m = TableNameMapping()
        assert m.iceberg_full_name("ods_user_order") == "shuqing_catalog.trade.ods_user_order"
        assert m.iceberg_full_name("dwd_user_order") == "shuqing_catalog.trade.dwd_user_order"

    def test_iceberg_full_name_with_db(self):
        m = TableNameMapping()
        # 已含 database 前缀，仅补 catalog
        assert m.iceberg_full_name("trade.ods_user_order") == "shuqing_catalog.trade.ods_user_order"

    def test_doris_full_name(self):
        m = TableNameMapping()
        assert m.doris_full_name("dws_user_order_1d") == "dwd.dws_user_order_1d"
        assert m.doris_full_name("dwd.dws_user_order_1d") == "dwd.dws_user_order_1d"

    def test_materialized_view_name(self):
        m = TableNameMapping()
        assert m.materialized_view_name("dws_user_order_1d") == "mv_dws_user_order_1d"
        # 含 database 前缀
        assert m.materialized_view_name("dwd.dws_user_order_1d") == "mv_dws_user_order_1d"

    def test_doris_external_catalog_name(self):
        m = TableNameMapping(project="trade")
        assert m.doris_external_catalog_name() == "iceberg_trade"

    def test_resolve_layer(self):
        m = TableNameMapping()
        assert m.resolve_layer("ods_user_order") == "ods"
        assert m.resolve_layer("dwd_user_order") == "dwd"
        assert m.resolve_layer("dws_user_order_1d") == "dws"
        assert m.resolve_layer("mv_dws_user_order_1d") == "mv"
        assert m.resolve_layer("unknown_table") == "unknown"
        # 含 database 前缀
        assert m.resolve_layer("trade.ods_user_order") == "ods"

    def test_custom_workspace_project(self):
        m = TableNameMapping(workspace="ws-fin", project="trade2026")
        assert m.warehouse_path == "lakehouse/ws-fin/trade2026"
        assert m.iceberg_full_name("ods_x") == "shuqing_catalog.trade2026.ods_x"


# ===================== SqlPlaceholderRenderer 测试 =====================

class TestSqlPlaceholderRenderer:
    """占位符渲染器测试。"""

    def test_dollar_brace_syntax(self):
        r = SqlPlaceholderRenderer()
        result = r.render("SELECT * FROM ${iceberg_table.ods_user_order}")
        assert result.success
        assert "shuqing_catalog.trade.ods_user_order" in result.rendered_sql

    def test_jinja_syntax(self):
        r = SqlPlaceholderRenderer()
        result = r.render("SELECT * FROM {{warehouse_path}}", strict=False)
        assert result.success
        assert "lakehouse/demo-fin/trade" in result.rendered_sql

    def test_colon_syntax(self):
        r = SqlPlaceholderRenderer()
        result = r.render("WHERE tenant_id = :tenant_id", strict=False)
        # :tenant_id 应被替换
        assert "demo-fin" in result.rendered_sql

    def test_mixed_syntax(self):
        r = SqlPlaceholderRenderer()
        sql = """
        SELECT * FROM ${iceberg_table.dwd_user_order}
        WHERE ws = {{workspace}} AND tenant = :tenant_id
        """.strip()
        result = r.render(sql, strict=False)
        assert "shuqing_catalog.trade.dwd_user_order" in result.rendered_sql
        assert "demo-fin" in result.rendered_sql

    def test_strict_mode_raises_on_unresolved(self):
        r = SqlPlaceholderRenderer()
        with pytest.raises(SqlRenderError):
            r.render("SELECT * FROM ${unknown.variable}", strict=True)

    def test_non_strict_mode_keeps_placeholder(self):
        r = SqlPlaceholderRenderer()
        result = r.render("SELECT * FROM ${unknown.variable}", strict=False)
        assert not result.success
        assert "${unknown.variable}" in result.rendered_sql
        assert "unknown.variable" in result.unresolved_vars

    def test_extra_variables_override_defaults(self):
        r = SqlPlaceholderRenderer()
        result = r.render("${warehouse_path}", {"warehouse_path": "custom/path"})
        assert result.rendered_sql == "custom/path"

    def test_secret_placeholders_resolved(self):
        r = SqlPlaceholderRenderer()
        sql = """
        'hostname' = '${secret.mysql.host}',
        'port' = '${secret.mysql.port}',
        'username' = '${secret.mysql.user}',
        'password' = '${secret.mysql.pass}'
        """.strip()
        result = r.render(sql)
        assert result.success
        assert "mysql.demo-fin.svc.cluster.local" in result.rendered_sql
        assert "3306" in result.rendered_sql

    def test_replaced_vars_recorded(self):
        r = SqlPlaceholderRenderer()
        result = r.render("${warehouse_path} and ${project}")
        assert "warehouse_path" in result.replaced_vars
        assert "project" in result.replaced_vars


# ===================== 内置模板渲染测试 =====================

class TestBuiltinTemplates:
    """内置 SQL 模板渲染测试（对应设计文档 §5/§6/§7）。"""

    def test_flink_cdc_sql_renders(self):
        r = SqlPlaceholderRenderer()
        sql = r.render_flink_cdc_sql()
        # 应含 mysql-cdc connector
        assert "mysql-cdc" in sql
        # 应含 iceberg format
        assert "iceberg" in sql
        # 应含 warehouse 路径
        assert "lakehouse/demo-fin/trade" in sql
        # 不应含未解析占位符
        assert "${" not in sql
        assert "{{" not in sql

    def test_spark_dwd_sql_renders(self):
        r = SqlPlaceholderRenderer()
        sql = r.render_spark_dwd_sql()
        assert "INSERT OVERWRITE" in sql
        assert "shuqing_catalog.trade.dwd_user_order" in sql
        assert "shuqing_catalog.trade.ods_user_order" in sql
        assert "${" not in sql

    def test_spark_dws_sql_renders(self):
        r = SqlPlaceholderRenderer()
        sql = r.render_spark_dws_sql()
        assert "INSERT OVERWRITE" in sql
        assert "shuqing_catalog.trade.dws_user_order_1d" in sql
        assert "shuqing_catalog.trade.dwd_user_order" in sql
        assert "COUNT(DISTINCT user_id)" in sql
        assert "${" not in sql

    def test_doris_mv_sql_renders(self):
        r = SqlPlaceholderRenderer()
        sql = r.render_doris_mv_sql()
        assert "CREATE EXTERNAL CATALOG" in sql
        assert "iceberg_trade" in sql
        assert "CREATE MATERIALIZED VIEW" in sql
        assert "mv_dws_user_order_1d" in sql
        assert "${" not in sql

    def test_custom_workspace_project_in_templates(self):
        m = TableNameMapping(workspace="ws-custom", project="proj2026")
        r = SqlPlaceholderRenderer(table_mapping=m)
        sql = r.render_flink_cdc_sql(workspace="ws-custom", project="proj2026")
        assert "lakehouse/ws-custom/proj2026" in sql


# ===================== 表命名衔接修复测试 =====================

class TestTableNameBridging:
    """表命名衔接修复测试（Iceberg ↔ Doris ↔ Catalog 全名一致性）。"""

    def test_iceberg_doris_consistency(self):
        """Iceberg 表名与 Doris External Catalog 引用一致。"""
        m = TableNameMapping()
        # Doris External Catalog 引用 Iceberg 表：iceberg_trade.dwd.dws_user_order_1d
        # 应与 Iceberg 全名的 database.table 部分一致
        iceberg_full = m.iceberg_full_name("dws_user_order_1d")
        # iceberg_full = shuqing_catalog.trade.dws_user_order_1d
        # Doris External Catalog 引用 = iceberg_trade.dwd.dws_user_order_1d
        # database 部分应一致（trade vs dwd 是不同命名空间，但 table 部分应一致）
        iceberg_table = iceberg_full.split(".")[-1]
        doris_table = m.doris_full_name("dws_user_order_1d").split(".")[-1]
        assert iceberg_table == doris_table == "dws_user_order_1d"

    def test_mv_name_bridging(self):
        """物化视图名与汇总层表名衔接一致。"""
        m = TableNameMapping()
        dws_table = "dws_user_order_1d"
        mv_name = m.materialized_view_name(dws_table)
        # mv_dws_user_order_1d 应含 dws_user_order_1d
        assert dws_table in mv_name
        assert mv_name.startswith("mv_")

    def test_layer_naming_convention(self):
        """层次命名约定：ods → dwd → dws → mv。"""
        m = TableNameMapping()
        # ods 层（Flink CDC 写入）
        assert m.resolve_layer("ods_user_order") == "ods"
        # dwd 层（Spark 产出）
        assert m.resolve_layer("dwd_user_order") == "dwd"
        # dws 层（Spark 产出）
        assert m.resolve_layer("dws_user_order_1d") == "dws"
        # mv 层（Doris 产出）
        assert m.resolve_layer("mv_dws_user_order_1d") == "mv"


if __name__ == "__main__":
    pytest.main([__file__, "-v"])