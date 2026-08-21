#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PoC SQL 占位符渲染器。

对应 ROADMAP v1.2「PoC SQL 占位符渲染逻辑实现，表命名衔接修复」。

职责：
1. 渲染 PoC 脚本中的 SQL 占位符（${var}、{{var}}、:var 三种语法）
2. 修复表命名衔接问题（Iceberg 表名 ↔ Doris 表名 ↔ Catalog 全名）
3. 提供表命名映射规则（database.table → catalog.database.table）
4. 校验渲染后 SQL 的完整性（无残留占位符）

设计文档对应：
- 多平台多租户大数据平台_端到端PoC详细设计_v0.1.md §5 步骤2 Flink CDC SQL
- 多平台多租户大数据平台_端到端PoC详细设计_v0.1.md §6 步骤3 Spark SQL
- 多平台多租户大数据平台_端到端PoC详细设计_v0.1.md §7 步骤4 Doris 物化视图
"""
from __future__ import annotations

import re
import logging
from dataclasses import dataclass, field
from typing import Dict, Mapping, Optional, Tuple

logger = logging.getLogger(__name__)


# ===================== 异常 =====================

class SqlRenderError(Exception):
    """SQL 渲染异常（占位符未解析 / 表名映射缺失等）。"""


class TableNameMappingError(Exception):
    """表命名映射异常。"""


# ===================== 表命名衔接 =====================

@dataclass
class TableNameMapping:
    """
    表命名衔接映射（Iceberg ↔ Doris ↔ Catalog 全名）。

    设计文档中的表命名约定：
    - Iceberg 湖层: lakehouse/<workspace>/<project>/ods_user_order
      → SQL 引用: shuqing_catalog.<db>.ods_user_order
    - Iceberg 仓层 dwd: shuqing_catalog.<db>.dwd_user_order
    - Iceberg 仓层 dws: shuqing_catalog.<db>.dws_user_order_1d
    - Doris 集层物化视图: mv_dws_user_order_1d

    层次命名约定（对应设计文档 §3 数据流）：
    - ods: 原始层（Flink CDC 写入）
    - dwd: 明细层（Spark 产出）
    - dws: 汇总层（Spark 产出）
    - mv:  物化视图层（Doris 产出）
    """

    #: Iceberg Catalog 名称（对应 SnapshotIsolationConfig.catalogName）
    catalog_name: str = "shuqing_catalog"

    #: 工作空间名（对应封装层 workspace）
    workspace: str = "demo-fin"

    #: 数据项目名（对应封装层 project）
    project: str = "trade"

    #: Doris 数据库名（对应 External Catalog 下的 database）
    doris_database: str = "dwd"

    @property
    def iceberg_database(self) -> str:
        """Iceberg 数据库名（与 project 同名，对应 warehouse 路径中的项目段）。"""
        return self.project

    @property
    def warehouse_path(self) -> str:
        """Iceberg warehouse 路径（对应封装层 project.storagePrefix）。"""
        return f"lakehouse/{self.workspace}/{self.project}"

    def iceberg_full_name(self, table: str) -> str:
        """
        将简短表名转为 Iceberg 全名（catalog.database.table）。

        :param table: 简短表名（如 ods_user_order、dwd_user_order）
        :return: Iceberg 全名（如 shuqing_catalog.trade.ods_user_order）
        """
        if "." in table:
            # 已含 database 前缀，仅补 catalog
            return f"{self.catalog_name}.{table}"
        return f"{self.catalog_name}.{self.iceberg_database}.{table}"

    def doris_full_name(self, table: str) -> str:
        """
        将简短表名转为 Doris 全名（database.table）。

        :param table: 简短表名（如 dws_user_order_1d、mv_dws_user_order_1d）
        :return: Doris 全名（如 dwd.dws_user_order_1d）
        """
        if "." in table:
            return table
        return f"{self.doris_database}.{table}"

    def doris_external_catalog_name(self) -> str:
        """Doris External Catalog 名称（对应设计文档 §7 步骤4）。"""
        return f"iceberg_{self.project}"

    def materialized_view_name(self, dws_table: str) -> str:
        """
        根据汇总层表名生成物化视图名（对应设计文档 §7）。

        :param dws_table: 汇总层表名（如 dws_user_order_1d）
        :return: 物化视图名（如 mv_dws_user_order_1d）
        """
        # 去除可能的 database 前缀
        if "." in dws_table:
            dws_table = dws_table.split(".")[-1]
        if dws_table.startswith("dws_"):
            return "mv_" + dws_table
        return "mv_" + dws_table

    def resolve_layer(self, table: str) -> str:
        """
        解析表所属层次（ods/dwd/dws/mv）。

        :param table: 表名
        :return: 层次名（ods/dwd/dws/mv/unknown）
        """
        if "." in table:
            table = table.split(".")[-1]
        for layer in ("ods_", "dwd_", "dws_", "mv_"):
            if table.startswith(layer):
                return layer.rstrip("_")
        return "unknown"

    def to_dict(self) -> Dict[str, str]:
        """转为字典（用于占位符渲染上下文）。"""
        return {
            "catalog_name": self.catalog_name,
            "workspace": self.workspace,
            "project": self.project,
            "iceberg_database": self.iceberg_database,
            "doris_database": self.doris_database,
            "warehouse_path": self.warehouse_path,
            "doris_external_catalog": self.doris_external_catalog_name(),
        }


# ===================== 占位符渲染 =====================

#: 三种占位符语法正则：
#: 1. ${var}    — Flink SQL WITH 子句常用（如 ${secret.mysql.host}）
#: 2. {{var}}   — Jinja 风格（如 {{warehouse_path}}）
#: 3. :var      — 命名参数风格（如 :tenant_id）
_PLACEHOLDER_PATTERNS: Tuple[re.Pattern, ...] = (
    re.compile(r"\$\{([a-zA-Z_][a-zA-Z0-9_.]*)\}"),
    re.compile(r"\{\{([a-zA-Z_][a-zA-Z0-9_.]*)\}\}"),
    re.compile(r"(?<![\w$]):([a-zA-Z_][a-zA-Z0-9_]*)"),
)


@dataclass
class SqlRenderResult:
    """SQL 渲染结果。"""

    rendered_sql: str
    replaced_vars: Dict[str, str] = field(default_factory=dict)
    unresolved_vars: list = field(default_factory=list)

    @property
    def success(self) -> bool:
        """渲染是否成功（无残留占位符）。"""
        return len(self.unresolved_vars) == 0


class SqlPlaceholderRenderer:
    """
    PoC SQL 占位符渲染器。

    支持三种占位符语法：
    1. ${var}    — Flink SQL WITH 子句常用
    2. {{var}}   — Jinja 风格
    3. :var      — 命名参数风格

    渲染上下文来源（优先级从高到低）：
    1. 显式传入的 variables 参数
    2. TableNameMapping.to_dict()（表命名衔接）
    3. 默认值（如 current_date、tenant_id 等）

    表命名衔接修复：
    - ${iceberg_table.<layer>} → shuqing_catalog.<db>.<table>
    - ${doris_table.<name>}    → <db>.<table>
    - ${mv_name.<dws_table>}   → mv_<dws_table>
    """

    def __init__(
        self,
        table_mapping: Optional[TableNameMapping] = None,
        default_variables: Optional[Mapping[str, str]] = None,
    ):
        self.table_mapping = table_mapping or TableNameMapping()
        self.default_variables: Dict[str, str] = {
            "current_date": "CURRENT_DATE",
            "tenant_id": "demo-fin",
            "tenant_name": "demo-fin",
            # 默认 Secret 占位符（PoC 环境用，生产由封装层 Secret 注入）
            "secret.mysql.host": "mysql.demo-fin.svc.cluster.local",
            "secret.mysql.port": "3306",
            "secret.mysql.user": "demo_user",
            "secret.mysql.pass": "demo_pass",
            "secret.mysql.database": "fin",
            "secret.mysql.table": "user_order",
        }
        if default_variables:
            self.default_variables.update(default_variables)

    def render(
        self,
        sql: str,
        variables: Optional[Mapping[str, str]] = None,
        strict: bool = True,
    ) -> SqlRenderResult:
        """
        渲染 SQL 占位符。

        :param sql:       含占位符的 SQL
        :param variables: 额外变量（覆盖默认值）
        :param strict:    严格模式（True 时残留占位符抛异常；False 时保留原占位符）
        :return: 渲染结果
        :raises SqlRenderError: strict=True 且存在未解析占位符
        """
        # 合并变量上下文：默认 < 表命名映射 < 显式传入（显式传入优先级最高）
        context: Dict[str, str] = {}
        context.update(self.default_variables)
        context.update(self.table_mapping.to_dict())
        # 表命名衔接变量（优先级低于显式传入）
        context.update(self._build_table_variables())
        if variables:
            context.update(variables)

        rendered = sql
        replaced: Dict[str, str] = {}
        unresolved: list = []

        for pattern in _PLACEHOLDER_PATTERNS:
            rendered, pat_replaced, pat_unresolved = self._render_pattern(
                rendered, pattern, context)
            replaced.update(pat_replaced)
            unresolved.extend(pat_unresolved)

        result = SqlRenderResult(
            rendered_sql=rendered,
            replaced_vars=replaced,
            unresolved_vars=unresolved,
        )

        if strict and unresolved:
            raise SqlRenderError(
                f"SQL 渲染后仍存在 {len(unresolved)} 个未解析占位符: {unresolved}"
            )

        logger.debug("SQL 渲染完成: replaced=%d, unresolved=%d",
                     len(replaced), len(unresolved))
        return result

    def _render_pattern(
        self,
        sql: str,
        pattern: re.Pattern,
        context: Mapping[str, str],
    ) -> Tuple[str, Dict[str, str], list]:
        """渲染单个模式的占位符。"""
        replaced: Dict[str, str] = {}
        unresolved: list = []

        def replacer(match: re.Match) -> str:
            var_name = match.group(1)
            if var_name in context:
                value = context[var_name]
                replaced[var_name] = value
                return value
            unresolved.append(var_name)
            return match.group(0)

        rendered = pattern.sub(replacer, sql)
        return rendered, replaced, unresolved

    def _build_table_variables(self) -> Dict[str, str]:
        """构建表命名衔接变量（用于 ${iceberg_table.xxx} 等）。"""
        m = self.table_mapping
        return {
            # Iceberg 表全名（含 catalog 前缀）
            "iceberg_table.ods_user_order": m.iceberg_full_name("ods_user_order"),
            "iceberg_table.dwd_user_order": m.iceberg_full_name("dwd_user_order"),
            "iceberg_table.dws_user_order_1d": m.iceberg_full_name("dws_user_order_1d"),
            # Doris 表全名（含 database 前缀）
            "doris_table.dws_user_order_1d": m.doris_full_name("dws_user_order_1d"),
            "doris_table.mv_dws_user_order_1d": m.doris_full_name(
                m.materialized_view_name("dws_user_order_1d")),
            # 物化视图名
            "mv_name.dws_user_order_1d": m.materialized_view_name("dws_user_order_1d"),
            # Doris External Catalog
            "doris_external_catalog": m.doris_external_catalog_name(),
            # warehouse 路径
            "warehouse_path": m.warehouse_path,
        }

    def render_flink_cdc_sql(
        self,
        workspace: str = "demo-fin",
        project: str = "trade",
        mysql_table: str = "user_order",
        iceberg_table: str = "ods_user_order",
    ) -> str:
        """
        渲染 Flink CDC 入湖 SQL（对应设计文档 §5 步骤2）。

        :return: 渲染后的 Flink SQL
        """
        sql_template = """
-- 作业名: cdc-${mysql_table}  (客户视角: 一个"同步任务")
CREATE TABLE mysql_${mysql_table} (
  order_id     BIGINT,
  user_id      BIGINT,
  amount       DECIMAL(18,2),
  status       STRING,
  update_time  TIMESTAMP(3),
  PRIMARY KEY (order_id) NOT ENFORCED
) WITH (
  'connector' = 'mysql-cdc',
  'hostname'  = '${secret.mysql.host}',
  'port'      = '${secret.mysql.port}',
  'username'  = '${secret.mysql.user}',
  'password'  = '${secret.mysql.pass}',
  'database-name' = '${secret.mysql.database}',
  'table-name'    = '${mysql_table}'
);

CREATE TABLE iceberg_${iceberg_table} (
  order_id     BIGINT,
  user_id      BIGINT,
  amount       DECIMAL(18,2),
  status       STRING,
  update_time  TIMESTAMP(3),
  PRIMARY KEY (order_id) NOT ENFORCED
) WITH (
  'format'      = 'iceberg',
  'catalog-type'= 'hadoop',
  'warehouse'   = '${warehouse_path}',
  'table'       = '${iceberg_table}'
);

INSERT INTO iceberg_${iceberg_table}
SELECT order_id, user_id, amount, status, update_time
FROM mysql_${mysql_table};
""".strip()
        result = self.render(sql_template, {
            "mysql_table": mysql_table,
            "iceberg_table": iceberg_table,
            "workspace": workspace,
            "project": project,
        })
        return result.rendered_sql

    def render_spark_dwd_sql(self) -> str:
        """
        渲染 Spark dwd 明细层 SQL（对应设计文档 §6 步骤3）。

        :return: 渲染后的 Spark SQL
        """
        sql_template = """
-- 作业名: spark-dwd-user-order
-- 明细层 dwd
INSERT OVERWRITE ${iceberg_table.dwd_user_order}
SELECT
  order_id, user_id, amount, status,
  DATE(update_time) AS order_date,
  update_time
FROM ${iceberg_table.ods_user_order}
WHERE update_time >= current_date;
""".strip()
        return self.render(sql_template).rendered_sql

    def render_spark_dws_sql(self) -> str:
        """
        渲染 Spark dws 汇总层 SQL（对应设计文档 §6 步骤3）。

        :return: 渲染后的 Spark SQL
        """
        sql_template = """
-- 汇总层 dws (近1日)
INSERT OVERWRITE ${iceberg_table.dws_user_order_1d}
SELECT
  order_date,
  COUNT(*)                 AS order_cnt,
  SUM(amount)              AS total_amount,
  COUNT(DISTINCT user_id)  AS uv
FROM ${iceberg_table.dwd_user_order}
GROUP BY order_date;
""".strip()
        return self.render(sql_template).rendered_sql

    def render_doris_mv_sql(self) -> str:
        """
        渲染 Doris 物化视图 SQL（对应设计文档 §7 步骤4）。

        :return: 渲染后的 Doris SQL
        """
        sql_template = """
-- Doris 侧: 建 Iceberg External Catalog (由封装层在数据项目初始化时自动建)
CREATE EXTERNAL CATALOG IF NOT EXISTS ${doris_external_catalog} PROPERTIES (
  "type" = "iceberg",
  "iceberg.catalog.type" = "hadoop",
  "warehouse" = "${warehouse_path}"
);

-- 集层物化视图: 直接基于 Iceberg dws 层
CREATE MATERIALIZED VIEW ${mv_name.dws_user_order_1d}
DISTRIBUTED BY HASH(order_date)
AS
SELECT order_date, order_cnt, total_amount, uv
FROM ${doris_external_catalog}.${doris_table.dws_user_order_1d};
""".strip()
        return self.render(sql_template).rendered_sql


# ===================== CLI 入口（供 PoC 脚本调用） =====================

def main() -> int:
    """CLI 入口：渲染 SQL 文件中的占位符。"""
    import argparse
    import sys
    from pathlib import Path

    parser = argparse.ArgumentParser(
        description="PoC SQL 占位符渲染器（表命名衔接修复）")
    parser.add_argument("sql_file", type=str, nargs="?",
                        help="含占位符的 SQL 文件路径（不指定则演示内置模板）")
    parser.add_argument("--workspace", default="demo-fin", help="工作空间名")
    parser.add_argument("--project", default="trade", help="数据项目名")
    parser.add_argument("--var", action="append", default=[],
                        help="额外变量（key=value 格式，可多次指定）")
    parser.add_argument("--output", "-o", type=str, help="输出文件路径")
    parser.add_argument("--check", action="store_true",
                        help="仅校验占位符是否全部解析（不输出 SQL）")
    args = parser.parse_args()

    # 构建表命名映射
    mapping = TableNameMapping(workspace=args.workspace, project=args.project)
    renderer = SqlPlaceholderRenderer(table_mapping=mapping)

    # 解析额外变量
    extra_vars: Dict[str, str] = {}
    for kv in args.var:
        if "=" in kv:
            k, v = kv.split("=", 1)
            extra_vars[k] = v

    # 获取 SQL 内容
    if args.sql_file:
        sql_content = Path(args.sql_file).read_text(encoding="utf-8")
    else:
        # 演示内置模板
        print("=" * 70)
        print("PoC SQL 占位符渲染器 · 内置模板演示")
        print("=" * 70)
        for label, rendered in [
            ("Flink CDC 入湖 SQL", renderer.render_flink_cdc_sql()),
            ("Spark dwd 明细层 SQL", renderer.render_spark_dwd_sql()),
            ("Spark dws 汇总层 SQL", renderer.render_spark_dws_sql()),
            ("Doris 物化视图 SQL", renderer.render_doris_mv_sql()),
        ]:
            print(f"\n--- {label} ---")
            print(rendered)
        return 0

    # 渲染
    try:
        result = renderer.render(sql_content, extra_vars, strict=True)
    except SqlRenderError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1

    if args.check:
        if result.success:
            print(f"OK: 全部 {len(result.replaced_vars)} 个占位符已解析")
            return 0
        print(f"FAIL: {len(result.unresolved_vars)} 个占位符未解析: "
              f"{result.unresolved_vars}", file=sys.stderr)
        return 1

    # 输出
    if args.output:
        Path(args.output).write_text(result.rendered_sql, encoding="utf-8")
        print(f"已写入: {args.output}")
    else:
        print(result.rendered_sql)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())