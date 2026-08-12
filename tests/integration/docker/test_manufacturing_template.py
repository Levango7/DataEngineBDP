"""制造行业模板（Manufacturing Industry Template, T037）集成测试.

被测对象：platform/industry-templates/templates/manufacturing/ 全部交付物
         platform/industry-templates/charts/manufacturing-template/ Helm Chart

测试覆盖：
    - DDL 场景：25 张表 DDL 语法正确（CREATE TABLE 语句可解析）
    - DAG 场景：4 个 DAG Python 文件可解析（import 语法正确）
    - Dashboard 场景：3 个 Dashboard JSON 格式正确
    - IoTDB 场景：JDBC 配置 + Flink Connector 配置 YAML 格式正确
    - Helm 部署场景：Chart.yaml + values.yaml + templates 可渲染
    - RBAC 场景：角色权限矩阵一致性
    - 交叉一致性：DDL 表数与 RBAC 资源数一致、DAG 数与 RBAC DAG 资源一致等

运行方式：
    pytest tests/integration/docker/test_manufacturing_template.py -v

Author: T037 制造模板工程师
"""
from __future__ import annotations

import ast
import json
import os
import re
import subprocess
from pathlib import Path

import pytest
import yaml

# ---------------------------------------------------------------------------
# 路径常量
# ---------------------------------------------------------------------------
# 测试文件所在目录：tests/integration/docker/
_THIS_DIR = Path(__file__).resolve().parent

# 项目根目录：DataEngineBDP/
PROJECT_ROOT = _THIS_DIR.parents[2]

# 制造模板根目录：platform/industry-templates/templates/manufacturing/
TEMPLATE_ROOT = (
    PROJECT_ROOT / "platform" / "industry-templates" / "templates" / "manufacturing"
)

# Helm Chart 目录：platform/industry-templates/charts/manufacturing-template/
CHART_ROOT = (
    PROJECT_ROOT
    / "platform"
    / "industry-templates"
    / "charts"
    / "manufacturing-template"
)

# 各子目录
DDL_DIR = TEMPLATE_ROOT / "ddl"
DAG_DIR = TEMPLATE_ROOT / "dag"
DASHBOARD_DIR = TEMPLATE_ROOT / "dashboards"
IOTDB_DIR = TEMPLATE_ROOT / "iotdb"
RBAC_DIR = TEMPLATE_ROOT / "rbac"

# 期望的表清单（25 张）
EXPECTED_TABLES = [
    # OEE 域（7 张）
    "equipment",
    "production_line",
    "shift",
    "equipment_status_log",
    "equipment_oee_daily",
    "equipment_oee_shift",
    "equipment_sensor_metric",
    # 质量追溯域（7 张）
    "product_batch",
    "work_order",
    "process_route",
    "process_record",
    "quality_parameter",
    "defect_record",
    "quality_trace_link",
    # 供应链协同域（7 张）
    "supplier",
    "purchase_order",
    "inventory",
    "inventory_movement",
    "sales_order",
    "logistics_shipment",
    "supply_chain_event",
    # RBAC 域（4 张）
    "mfg_role",
    "mfg_permission",
    "mfg_role_permission",
    "mfg_user_role",
]

# 期望的 DAG 文件（4 个）
EXPECTED_DAGS = [
    "oee_calculation.py",
    "quality_trace.py",
    "supply_chain_sync.py",
    "iotdb_ingestion.py",
]

# 期望的 Dashboard 文件（3 个）
EXPECTED_DASHBOARDS = [
    "oee_dashboard.json",
    "quality_dashboard.json",
    "supply_chain_dashboard.json",
]

# 期望的 RBAC 角色（4 个）
EXPECTED_ROLES = [
    "workshop_director",
    "quality_engineer",
    "supply_chain_manager",
    "equipment_engineer",
]


# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------
def _read_file(path: Path) -> str:
    """读取文件文本内容."""
    return path.read_text(encoding="utf-8")


def _extract_create_tables(sql_content: str) -> list[str]:
    """从 SQL DDL 内容中提取所有 CREATE TABLE 的表名.

    Args:
        sql_content: SQL DDL 文本.

    Returns:
        表名列表（按出现顺序）.
    """
    # 匹配 CREATE TABLE IF NOT EXISTS table_name 或 CREATE TABLE table_name
    pattern = re.compile(
        r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)", re.IGNORECASE
    )
    return pattern.findall(sql_content)


# ---------------------------------------------------------------------------
# 测试类：DDL 场景
# ---------------------------------------------------------------------------
class TestDdlScenarios:
    """DDL 场景测试：验证 25 张表 DDL 语法正确."""

    def test_ddl_directory_exists(self):
        """DDL 目录存在."""
        assert DDL_DIR.exists(), f"DDL 目录不存在: {DDL_DIR}"
        assert DDL_DIR.is_dir(), f"DDL 路径不是目录: {DDL_DIR}"

    def test_ddl_files_exist(self):
        """4 个 DDL 文件全部存在."""
        expected_files = [
            "oee_ddl.sql",
            "quality_trace_ddl.sql",
            "supply_chain_ddl.sql",
            "rbac_ddl.sql",
        ]
        for filename in expected_files:
            filepath = DDL_DIR / filename
            assert filepath.exists(), f"DDL 文件不存在: {filepath}"

    def test_oee_ddl_has_7_tables(self):
        """OEE DDL 包含 7 张表."""
        content = _read_file(DDL_DIR / "oee_ddl.sql")
        tables = _extract_create_tables(content)
        assert len(tables) >= 7, f"OEE DDL 表数不足 7 张，实际: {len(tables)}, 表: {tables}"
        # 验证关键表存在
        for expected_table in [
            "equipment",
            "production_line",
            "shift",
            "equipment_oee_daily",
            "equipment_sensor_metric",
        ]:
            assert expected_table in tables, f"OEE DDL 缺少表: {expected_table}"

    def test_quality_trace_ddl_has_7_tables(self):
        """质量追溯 DDL 包含 7 张表."""
        content = _read_file(DDL_DIR / "quality_trace_ddl.sql")
        tables = _extract_create_tables(content)
        assert len(tables) >= 7, f"质量追溯 DDL 表数不足 7 张，实际: {len(tables)}"
        for expected_table in [
            "product_batch",
            "work_order",
            "process_record",
            "quality_parameter",
            "defect_record",
            "quality_trace_link",
        ]:
            assert expected_table in tables, f"质量追溯 DDL 缺少表: {expected_table}"

    def test_supply_chain_ddl_has_7_tables(self):
        """供应链协同 DDL 包含 7 张表."""
        content = _read_file(DDL_DIR / "supply_chain_ddl.sql")
        tables = _extract_create_tables(content)
        assert len(tables) >= 7, f"供应链协同 DDL 表数不足 7 张，实际: {len(tables)}"
        for expected_table in [
            "supplier",
            "purchase_order",
            "inventory",
            "sales_order",
            "logistics_shipment",
            "supply_chain_event",
        ]:
            assert expected_table in tables, f"供应链协同 DDL 缺少表: {expected_table}"

    def test_rbac_ddl_has_4_tables(self):
        """RBAC DDL 包含 4 张表."""
        content = _read_file(DDL_DIR / "rbac_ddl.sql")
        tables = _extract_create_tables(content)
        assert len(tables) >= 4, f"RBAC DDL 表数不足 4 张，实际: {len(tables)}"
        for expected_table in [
            "mfg_role",
            "mfg_permission",
            "mfg_role_permission",
            "mfg_user_role",
        ]:
            assert expected_table in tables, f"RBAC DDL 缺少表: {expected_table}"

    def test_total_tables_at_least_15(self):
        """所有 DDL 合计表数 >= 15 张（实际 25 张）."""
        all_tables: list[str] = []
        for ddl_file in DDL_DIR.glob("*.sql"):
            content = _read_file(ddl_file)
            all_tables.extend(_extract_create_tables(content))
        # 去重（不同 DDL 文件不应有同名表）
        unique_tables = set(all_tables)
        assert len(unique_tables) >= 15, (
            f"DDL 总表数不足 15 张，实际: {len(unique_tables)}, 表: {sorted(unique_tables)}"
        )

    def test_ddl_has_oee_formula_comment(self):
        """OEE DDL 包含 OEE 公式注释."""
        content = _read_file(DDL_DIR / "oee_ddl.sql")
        assert "OEE" in content, "OEE DDL 未包含 OEE 关键字"
        assert "availability" in content.lower(), "OEE DDL 未包含 availability 字段"
        assert "performance" in content.lower(), "OEE DDL 未包含 performance 字段"
        assert "quality" in content.lower(), "OEE DDL 未包含 quality 字段"

    def test_ddl_has_iotdb_reference(self):
        """DDL 包含 IoTDB 时序数据引用."""
        oee_content = _read_file(DDL_DIR / "oee_ddl.sql")
        assert "iotdb" in oee_content.lower(), "OEE DDL 未包含 IoTDB 引用"


# ---------------------------------------------------------------------------
# 测试类：DAG 场景
# ---------------------------------------------------------------------------
class TestDagScenarios:
    """DAG 场景测试：验证 4 个 DAG Python 文件可解析."""

    def test_dag_directory_exists(self):
        """DAG 目录存在."""
        assert DAG_DIR.exists(), f"DAG 目录不存在: {DAG_DIR}"

    def test_dag_files_exist(self):
        """4 个 DAG 文件全部存在."""
        for filename in EXPECTED_DAGS:
            filepath = DAG_DIR / filename
            assert filepath.exists(), f"DAG 文件不存在: {filepath}"

    def test_dag_python_syntax_valid(self):
        """所有 DAG Python 文件语法正确（可通过 ast.parse 解析）."""
        for filename in EXPECTED_DAGS:
            filepath = DAG_DIR / filename
            content = _read_file(filepath)
            try:
                ast.parse(content, filename=str(filepath))
            except SyntaxError as e:
                pytest.fail(f"DAG 文件 {filename} 语法错误: {e}")

    def test_dag_has_dag_definition(self):
        """每个 DAG 文件包含 DAG 定义（dag_id 或 DAG 构造）."""
        for filename in EXPECTED_DAGS:
            filepath = DAG_DIR / filename
            content = _read_file(filepath)
            assert "DAG(" in content, f"DAG 文件 {filename} 未包含 DAG() 构造"
            assert "dag_id" in content, f"DAG 文件 {filename} 未包含 dag_id 参数"

    def test_dag_count_at_least_4(self):
        """DAG 文件数 >= 4 个."""
        dag_files = list(DAG_DIR.glob("*.py"))
        assert len(dag_files) >= 4, f"DAG 文件数不足 4 个，实际: {len(dag_files)}"

    def test_oee_dag_has_oee_formula(self):
        """OEE 计算 DAG 包含 OEE 公式逻辑."""
        content = _read_file(DAG_DIR / "oee_calculation.py")
        assert "availability" in content.lower(), "OEE DAG 未包含 availability 计算"
        assert "performance" in content.lower(), "OEE DAG 未包含 performance 计算"
        assert "quality" in content.lower(), "OEE DAG 未包含 quality 计算"

    def test_iotdb_ingestion_dag_has_iotdb(self):
        """IoTDB 接入 DAG 包含 IoTDB 连接逻辑."""
        content = _read_file(DAG_DIR / "iotdb_ingestion.py")
        assert "iotdb" in content.lower(), "IoTDB 接入 DAG 未包含 iotdb 关键字"
        assert "flink" in content.lower(), "IoTDB 接入 DAG 未包含 flink 关键字"


# ---------------------------------------------------------------------------
# 测试类：Dashboard 场景
# ---------------------------------------------------------------------------
class TestDashboardScenarios:
    """Dashboard 场景测试：验证 3 个 Dashboard JSON 格式正确."""

    def test_dashboard_directory_exists(self):
        """Dashboard 目录存在."""
        assert DASHBOARD_DIR.exists(), f"Dashboard 目录不存在: {DASHBOARD_DIR}"

    def test_dashboard_files_exist(self):
        """3 个 Dashboard 文件全部存在."""
        for filename in EXPECTED_DASHBOARDS:
            filepath = DASHBOARD_DIR / filename
            assert filepath.exists(), f"Dashboard 文件不存在: {filepath}"

    def test_dashboard_json_valid(self):
        """所有 Dashboard JSON 格式正确."""
        for filename in EXPECTED_DASHBOARDS:
            filepath = DASHBOARD_DIR / filename
            content = _read_file(filepath)
            try:
                data = json.loads(content)
            except json.JSONDecodeError as e:
                pytest.fail(f"Dashboard 文件 {filename} JSON 格式错误: {e}")
            assert isinstance(data, dict), f"Dashboard {filename} 顶层不是 JSON 对象"

    def test_dashboard_has_required_keys(self):
        """每个 Dashboard 包含必需的顶层键：version/databases/datasets/dashboards."""
        for filename in EXPECTED_DASHBOARDS:
            filepath = DASHBOARD_DIR / filename
            data = json.loads(_read_file(filepath))
            assert "version" in data, f"Dashboard {filename} 缺少 version 键"
            assert "databases" in data, f"Dashboard {filename} 缺少 databases 键"
            assert "datasets" in data, f"Dashboard {filename} 缺少 datasets 键"
            assert "dashboards" in data, f"Dashboard {filename} 缺少 dashboards 键"

    def test_dashboard_has_charts(self):
        """每个 Dashboard 包含至少 1 个仪表盘和图表."""
        for filename in EXPECTED_DASHBOARDS:
            filepath = DASHBOARD_DIR / filename
            data = json.loads(_read_file(filepath))
            dashboards = data["dashboards"]
            assert len(dashboards) >= 1, f"Dashboard {filename} 无仪表盘定义"
            for dash in dashboards:
                assert "dashboard_title" in dash, f"Dashboard {filename} 缺少 dashboard_title"
                assert "charts" in dash, f"Dashboard {filename} 缺少 charts"
                assert len(dash["charts"]) >= 1, f"Dashboard {filename} 无图表"

    def test_dashboard_count_at_least_3(self):
        """Dashboard 文件数 >= 3 个."""
        dash_files = list(DASHBOARD_DIR.glob("*.json"))
        assert len(dash_files) >= 3, f"Dashboard 文件数不足 3 个，实际: {len(dash_files)}"


# ---------------------------------------------------------------------------
# 测试类：IoTDB 场景
# ---------------------------------------------------------------------------
class TestIotdbScenarios:
    """IoTDB 场景测试：验证 JDBC 配置 + Flink Connector 配置正确."""

    def test_iotdb_directory_exists(self):
        """IoTDB 配置目录存在."""
        assert IOTDB_DIR.exists(), f"IoTDB 配置目录不存在: {IOTDB_DIR}"

    def test_iotdb_config_files_exist(self):
        """IoTDB 配置文件全部存在."""
        expected_files = [
            "iotdb-jdbc-config.yaml",
            "flink-iotdb-connector.yaml",
        ]
        for filename in expected_files:
            filepath = IOTDB_DIR / filename
            assert filepath.exists(), f"IoTDB 配置文件不存在: {filepath}"

    def test_iotdb_jdbc_config_valid(self):
        """IoTDB JDBC 配置 YAML 格式正确且包含必需键."""
        filepath = IOTDB_DIR / "iotdb-jdbc-config.yaml"
        content = _read_file(filepath)
        data = yaml.safe_load(content)
        assert "iotdb" in data, "IoTDB JDBC 配置缺少 iotdb 键"
        assert "jdbc" in data["iotdb"], "IoTDB JDBC 配置缺少 jdbc 键"
        assert "url" in data["iotdb"]["jdbc"], "IoTDB JDBC 配置缺少 url"
        assert "driverClass" in data["iotdb"]["jdbc"], "IoTDB JDBC 配置缺少 driverClass"
        # 验证驱动类名
        assert "IoTDBDriver" in data["iotdb"]["jdbc"]["driverClass"], (
            "IoTDB JDBC 驱动类名不正确"
        )

    def test_iotdb_jdbc_has_timeseries_config(self):
        """IoTDB JDBC 配置包含时序路径定义."""
        filepath = IOTDB_DIR / "iotdb-jdbc-config.yaml"
        data = yaml.safe_load(_read_file(filepath))
        assert "timeseries" in data, "IoTDB JDBC 配置缺少 timeseries 键"
        assert "rootPath" in data["timeseries"], "IoTDB 配置缺少 rootPath"
        assert "metrics" in data["timeseries"], "IoTDB 配置缺少 metrics 列表"
        assert len(data["timeseries"]["metrics"]) >= 5, (
            "IoTDB 配置传感器指标不足 5 个"
        )

    def test_flink_iotdb_connector_config_valid(self):
        """Flink IoTDB Connector 配置 YAML 格式正确."""
        filepath = IOTDB_DIR / "flink-iotdb-connector.yaml"
        content = _read_file(filepath)
        data = yaml.safe_load(content)
        assert "flink" in data, "Flink 配置缺少 flink 键"
        assert "iotdbSource" in data, "Flink 配置缺少 iotdbSource 键"
        assert "dorisSink" in data, "Flink 配置缺少 dorisSink 键"

    def test_flink_connector_has_checkpoint_config(self):
        """Flink Connector 配置包含 Checkpoint 配置（Exactly-Once 语义）."""
        filepath = IOTDB_DIR / "flink-iotdb-connector.yaml"
        data = yaml.safe_load(_read_file(filepath))
        assert "checkpoint" in data["flink"], "Flink 配置缺少 checkpoint 键"
        assert data["flink"]["checkpoint"]["enabled"] is True, (
            "Flink Checkpoint 未启用"
        )
        assert data["flink"]["checkpoint"]["mode"] == "EXACTLY_ONCE", (
            "Flink Checkpoint 模式应为 EXACTLY_ONCE"
        )


# ---------------------------------------------------------------------------
# 测试类：Helm 部署场景
# ---------------------------------------------------------------------------
class TestHelmScenarios:
    """Helm 部署场景测试：验证 Chart 可部署."""

    def test_chart_directory_exists(self):
        """Helm Chart 目录存在."""
        assert CHART_ROOT.exists(), f"Helm Chart 目录不存在: {CHART_ROOT}"

    def test_chart_yaml_exists_and_valid(self):
        """Chart.yaml 存在且 YAML 格式正确."""
        chart_yaml = CHART_ROOT / "Chart.yaml"
        assert chart_yaml.exists(), f"Chart.yaml 不存在: {chart_yaml}"
        data = yaml.safe_load(_read_file(chart_yaml))
        assert data["apiVersion"] == "v2", "Chart.yaml apiVersion 应为 v2"
        assert data["name"] == "manufacturing-template", (
            "Chart.yaml name 应为 manufacturing-template"
        )
        assert data["type"] == "application", "Chart.yaml type 应为 application"
        assert "version" in data, "Chart.yaml 缺少 version"
        assert "appVersion" in data, "Chart.yaml 缺少 appVersion"

    def test_values_yaml_exists_and_valid(self):
        """values.yaml 存在且 YAML 格式正确."""
        values_yaml = CHART_ROOT / "values.yaml"
        assert values_yaml.exists(), f"values.yaml 不存在: {values_yaml}"
        data = yaml.safe_load(_read_file(values_yaml))
        assert "template" in data, "values.yaml 缺少 template 键"
        assert "namespace" in data, "values.yaml 缺少 namespace 键"
        assert "target" in data, "values.yaml 缺少 target 键"
        assert "configMap" in data, "values.yaml 缺少 configMap 键"
        assert "importJob" in data, "values.yaml 缺少 importJob 键"
        # 验证目标组件配置
        assert "doris" in data["target"], "values.yaml 缺少 doris 目标配置"
        assert "iotdb" in data["target"], "values.yaml 缺少 iotdb 目标配置"
        assert "flink" in data["target"], "values.yaml 缺少 flink 目标配置"

    def test_chart_templates_exist(self):
        """Chart templates 目录存在且包含模板文件."""
        templates_dir = CHART_ROOT / "templates"
        assert templates_dir.exists(), f"templates 目录不存在: {templates_dir}"
        template_files = list(templates_dir.glob("*"))
        assert len(template_files) >= 2, (
            f"templates 目录文件数不足 2 个，实际: {len(template_files)}"
        )
        # 验证关键模板存在
        template_names = [f.name for f in template_files]
        assert any("configmap" in n for n in template_names), (
            "缺少 ConfigMap 模板"
        )
        assert any("import" in n and "job" in n for n in template_names), (
            "缺少导入 Job 模板"
        )

    def test_helm_lint_if_available(self):
        """如果 helm CLI 可用，执行 helm lint 验证 Chart 有效性."""
        # 检查 helm 是否可用
        try:
            result = subprocess.run(
                ["helm", "version", "--short"],
                capture_output=True,
                text=True,
                timeout=10,
            )
            if result.returncode != 0:
                pytest.skip("helm CLI 不可用")
        except (FileNotFoundError, subprocess.SubprocessError):
            pytest.skip("helm CLI 不可用")

        # 执行 helm lint
        result = subprocess.run(
            ["helm", "lint", str(CHART_ROOT)],
            capture_output=True,
            text=True,
            timeout=30,
        )
        assert result.returncode == 0, (
            f"helm lint 失败:\nstdout: {result.stdout}\nstderr: {result.stderr}"
        )

    def test_helm_template_if_available(self):
        """如果 helm CLI 可用，执行 helm template 验证 Chart 可渲染."""
        try:
            result = subprocess.run(
                ["helm", "version", "--short"],
                capture_output=True,
                text=True,
                timeout=10,
            )
            if result.returncode != 0:
                pytest.skip("helm CLI 不可用")
        except (FileNotFoundError, subprocess.SubprocessError):
            pytest.skip("helm CLI 不可用")

        # 执行 helm template（dry-run 渲染）
        result = subprocess.run(
            ["helm", "template", "manufacturing-template", str(CHART_ROOT)],
            capture_output=True,
            text=True,
            timeout=30,
        )
        assert result.returncode == 0, (
            f"helm template 失败:\nstdout: {result.stdout}\nstderr: {result.stderr}"
        )
        # 验证渲染输出包含 ConfigMap 和 Job
        assert "ConfigMap" in result.stdout, "helm template 输出未包含 ConfigMap"
        assert "Job" in result.stdout, "helm template 输出未包含 Job"


# ---------------------------------------------------------------------------
# 测试类：RBAC 场景
# ---------------------------------------------------------------------------
class TestRbacScenarios:
    """RBAC 场景测试：验证角色权限矩阵一致性."""

    def test_rbac_directory_exists(self):
        """RBAC 配置目录存在."""
        assert RBAC_DIR.exists(), f"RBAC 目录不存在: {RBAC_DIR}"

    def test_rbac_files_exist(self):
        """RBAC 配置文件全部存在."""
        expected_files = [
            "roles.yaml",
            "permissions.yaml",
            "role-permissions.yaml",
        ]
        for filename in expected_files:
            filepath = RBAC_DIR / filename
            assert filepath.exists(), f"RBAC 配置文件不存在: {filepath}"

    def test_roles_yaml_valid(self):
        """roles.yaml YAML 格式正确且包含 4 个角色."""
        filepath = RBAC_DIR / "roles.yaml"
        data = yaml.safe_load(_read_file(filepath))
        assert "roles" in data, "roles.yaml 缺少 roles 键"
        role_names = [r["name"] for r in data["roles"]]
        assert len(role_names) >= 4, f"角色数不足 4 个，实际: {len(role_names)}"
        for expected_role in EXPECTED_ROLES:
            assert expected_role in role_names, f"缺少角色: {expected_role}"

    def test_permissions_yaml_valid(self):
        """permissions.yaml YAML 格式正确且包含资源定义."""
        filepath = RBAC_DIR / "permissions.yaml"
        data = yaml.safe_load(_read_file(filepath))
        assert "resources" in data, "permissions.yaml 缺少 resources 键"
        assert "permissions" in data, "permissions.yaml 缺少 permissions 键"
        # 验证资源数 >= 32（25 表 + 4 DAG + 3 Dashboard）
        assert len(data["resources"]) >= 32, (
            f"资源数不足 32 个，实际: {len(data['resources'])}"
        )

    def test_role_permissions_yaml_valid(self):
        """role-permissions.yaml YAML 格式正确."""
        filepath = RBAC_DIR / "role-permissions.yaml"
        data = yaml.safe_load(_read_file(filepath))
        assert "role_permissions" in data, "role-permissions.yaml 缺少 role_permissions 键"
        # 验证每个角色都有权限映射
        for rp in data["role_permissions"]:
            assert "role" in rp, "角色权限关联缺少 role 键"
            assert "permissions" in rp, "角色权限关联缺少 permissions 键"
            assert len(rp["permissions"]) > 0, f"角色 {rp['role']} 无权限"

    def test_rbac_minimal_privilege_principle(self):
        """RBAC 遵循最小权限原则：供应链经理不可访问设备 OEE 表."""
        filepath = RBAC_DIR / "roles.yaml"
        data = yaml.safe_load(_read_file(filepath))
        for role in data["roles"]:
            if role["name"] == "supply_chain_manager":
                denied = role["permissions_scope"].get("tables_denied", [])
                assert "equipment" in denied, "供应链经理应被拒绝访问 equipment 表"
                assert "equipment_oee_daily" in denied, (
                    "供应链经理应被拒绝访问 equipment_oee_daily 表"
                )
            if role["name"] == "quality_engineer":
                denied = role["permissions_scope"].get("tables_denied", [])
                assert "supplier" in denied, "质量员应被拒绝访问 supplier 表"


# ---------------------------------------------------------------------------
# 测试类：交叉一致性
# ---------------------------------------------------------------------------
class TestCrossConsistency:
    """交叉一致性测试：DDL/DAG/Dashboard/RBAC 之间的一致性."""

    def test_ddl_table_count_matches_rbac_resources(self):
        """DDL 表数与 RBAC permissions.yaml 中 table 资源数一致."""
        # 统计 DDL 表数
        all_tables: set[str] = set()
        for ddl_file in DDL_DIR.glob("*.sql"):
            content = _read_file(ddl_file)
            all_tables.update(_extract_create_tables(content))

        # 统计 RBAC table 资源数
        perm_data = yaml.safe_load(_read_file(RBAC_DIR / "permissions.yaml"))
        rbac_tables = {
            r["name"] for r in perm_data["resources"] if r["type"] == "table"
        }

        # 验证 RBAC 覆盖了所有 DDL 表（允许 RBAC 多出，但不应缺少）
        missing = all_tables - rbac_tables
        assert len(missing) == 0, (
            f"RBAC 未覆盖以下 DDL 表: {sorted(missing)}"
        )

    def test_dag_count_matches_rbac_dag_resources(self):
        """DAG 文件数与 RBAC permissions.yaml 中 dag 资源数一致."""
        dag_files = list(DAG_DIR.glob("*.py"))
        perm_data = yaml.safe_load(_read_file(RBAC_DIR / "permissions.yaml"))
        rbac_dags = [r for r in perm_data["resources"] if r["type"] == "dag"]
        assert len(rbac_dags) == len(dag_files), (
            f"RBAC DAG 资源数({len(rbac_dags)}) != DAG 文件数({len(dag_files)})"
        )

    def test_dashboard_count_matches_rbac_dashboard_resources(self):
        """Dashboard 文件数与 RBAC permissions.yaml 中 dashboard 资源数一致."""
        dash_files = list(DASHBOARD_DIR.glob("*.json"))
        perm_data = yaml.safe_load(_read_file(RBAC_DIR / "permissions.yaml"))
        rbac_dashes = [r for r in perm_data["resources"] if r["type"] == "dashboard"]
        assert len(rbac_dashes) == len(dash_files), (
            f"RBAC Dashboard 资源数({len(rbac_dashes)}) != Dashboard 文件数({len(dash_files)})"
        )

    def test_readme_exists(self):
        """制造模板 README.md 存在."""
        readme = TEMPLATE_ROOT / "README.md"
        assert readme.exists(), f"README.md 不存在: {readme}"
        content = _read_file(readme)
        assert "制造行业模板" in content or "Manufacturing" in content, (
            "README.md 未包含制造模板标题"
        )