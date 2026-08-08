"""能源行业模板（Energy Industry Template, T043）集成测试.

被测对象：platform/industry-templates/templates/energy/ 全部交付物
         platform/industry-templates/charts/energy-template/ Helm Chart

测试覆盖（18 个用例）：
    1. 模板文件结构验证
    2. DDL 语法验证
    3. 设备监测表结构验证
    4. 用能分析表结构验证
    5. 碳排放核算表结构验证
    6. 趋势预测表结构验证
    7. DAG 脚本可导入验证
    8. 设备监控 DAG 逻辑验证
    9. 用能统计 DAG 逻辑验证
    10. 碳排放核算 DAG 逻辑验证
    11. Dashboard JSON 格式验证
    12. 设备监测看板验证
    13. 用能分析看板验证
    14. 碳排放看板验证
    15. Helm Chart 结构验证
    16. Helm Chart lint 验证
    17. RBAC 配置验证
    18. IoTDB 配置验证

运行方式：
    pytest tests/integration/docker/test_energy_template.py -v

Author: T043 能源行业模板工程师
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

# 项目根目录：ShuqingBigDataPlatform/
PROJECT_ROOT = _THIS_DIR.parents[2]

# 能源模板根目录：platform/industry-templates/templates/energy/
TEMPLATE_ROOT = (
    PROJECT_ROOT / "platform" / "industry-templates" / "templates" / "energy"
)

# Helm Chart 目录：platform/industry-templates/charts/energy-template/
CHART_ROOT = (
    PROJECT_ROOT
    / "platform"
    / "industry-templates"
    / "charts"
    / "energy-template"
)

# 各子目录
DDL_DIR = TEMPLATE_ROOT / "ddl"
DAG_DIR = TEMPLATE_ROOT / "dag"
DASHBOARD_DIR = TEMPLATE_ROOT / "dashboards"
IOTDB_DIR = TEMPLATE_ROOT / "iotdb"
RBAC_DIR = TEMPLATE_ROOT / "rbac"

# 期望的表清单（31 张）
EXPECTED_TABLES = [
    # 设备监测域（8 张）
    "energy_device",
    "device_realtime_status",
    "device_alarm_record",
    "device_health_score",
    "device_metric_history",
    "device_alarm_rule",
    "device_maintenance_log",
    "device_status_change",
    # 用能分析域（7 张）
    "energy_consumption_detail",
    "energy_consumption_summary",
    "energy_dimension_compare",
    "energy_trend_data",
    "energy_quota",
    "energy_balance",
    "energy_cost_analysis",
    # 碳排放核算域（7 张）
    "emission_factor_library",
    "emission_source",
    "emission_calculation_result",
    "emission_calculation_model",
    "emission_scope_classification",
    "emission_reduction_target",
    "emission_report",
    # 趋势预测域（5 张）
    "forecast_parameter",
    "forecast_result",
    "forecast_model_evaluation",
    "forecast_model_registry",
    "forecast_confidence_interval",
    # RBAC 域（4 张）
    "energy_role",
    "energy_permission",
    "energy_role_permission",
    "energy_user_role",
]

# 期望的 DAG 文件（5 个）
EXPECTED_DAGS = [
    "device_status_monitoring.py",
    "energy_consumption_statistics.py",
    "carbon_emission_calculation.py",
    "energy_trend_forecast.py",
    "device_alert_routing.py",
]

# 期望的 Dashboard 文件（4 个）
EXPECTED_DASHBOARDS = [
    "device_monitoring_dashboard.json",
    "energy_consumption_dashboard.json",
    "carbon_emission_dashboard.json",
    "energy_forecast_dashboard.json",
]

# 期望的 IoTDB 配置文件（3 个）
EXPECTED_IOTDB_CONFIGS = [
    "iotdb-jdbc-config.yaml",
    "flink-iotdb-connector.yaml",
    "device-data-model.yaml",
]

# 期望的 RBAC 角色（4 个）
EXPECTED_ROLES = [
    "energy_admin",
    "energy_analyst",
    "device_operator",
    "carbon_accountant",
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
# 测试类 1：模板文件结构验证
# ---------------------------------------------------------------------------
class TestTemplateStructure:
    """模板文件结构验证：目录与文件完整性."""

    def test_template_root_exists(self):
        """能源模板根目录存在."""
        assert TEMPLATE_ROOT.exists(), f"能源模板根目录不存在: {TEMPLATE_ROOT}"
        assert TEMPLATE_ROOT.is_dir(), f"能源模板路径不是目录: {TEMPLATE_ROOT}"

    def test_subdirectories_exist(self):
        """5 个子目录全部存在：ddl/dag/dashboards/iotdb/rbac."""
        for subdir in ["ddl", "dag", "dashboards", "iotdb", "rbac"]:
            path = TEMPLATE_ROOT / subdir
            assert path.exists(), f"子目录不存在: {path}"
            assert path.is_dir(), f"路径不是目录: {path}"

    def test_readme_exists(self):
        """README.md 存在且包含能源模板标题."""
        readme = TEMPLATE_ROOT / "README.md"
        assert readme.exists(), f"README.md 不存在: {readme}"
        content = _read_file(readme)
        assert "能源行业模板" in content or "Energy" in content, (
            "README.md 未包含能源模板标题"
        )

    def test_template_metadata_exists(self):
        """template-metadata.yaml 存在且 YAML 格式正确."""
        metadata = TEMPLATE_ROOT / "template-metadata.yaml"
        assert metadata.exists(), f"template-metadata.yaml 不存在: {metadata}"
        data = yaml.safe_load(_read_file(metadata))
        assert data["name"] == "energy-template", "元数据 name 应为 energy-template"
        assert data["version"] == "1.0.0", "元数据 version 应为 1.0.0"


# ---------------------------------------------------------------------------
# 测试类 2-6：DDL 场景
# ---------------------------------------------------------------------------
class TestDdlScenarios:
    """DDL 场景测试：验证 31 张表 DDL 语法正确."""

    def test_ddl_files_exist(self):
        """5 个 DDL 文件全部存在."""
        expected_files = [
            "01_device_monitoring_ddl.sql",
            "02_energy_consumption_ddl.sql",
            "03_carbon_emission_ddl.sql",
            "04_energy_forecast_ddl.sql",
            "rbac_ddl.sql",
        ]
        for filename in expected_files:
            filepath = DDL_DIR / filename
            assert filepath.exists(), f"DDL 文件不存在: {filepath}"


    def test_ddl_syntax_valid(self):
        """所有 DDL 文件包含 CREATE TABLE 语句."""
        for ddl_file in DDL_DIR.glob("*.sql"):
            content = _read_file(ddl_file)
            tables = _extract_create_tables(content)
            assert len(tables) > 0, f"DDL 文件 {ddl_file.name} 未包含 CREATE TABLE"

    def test_device_monitoring_ddl_has_8_tables(self):
        """设备监测 DDL 包含 8 张表."""
        content = _read_file(DDL_DIR / "01_device_monitoring_ddl.sql")
        tables = _extract_create_tables(content)
        assert len(tables) >= 8, f"设备监测 DDL 表数不足 8 张，实际: {len(tables)}"
        for expected_table in [
            "energy_device",
            "device_realtime_status",
            "device_alarm_record",
            "device_health_score",
            "device_metric_history",
            "device_alarm_rule",
            "device_maintenance_log",
            "device_status_change",
        ]:
            assert expected_table in tables, f"设备监测 DDL 缺少表: {expected_table}"

    def test_energy_consumption_ddl_has_7_tables(self):
        """用能分析 DDL 包含 7 张表."""
        content = _read_file(DDL_DIR / "02_energy_consumption_ddl.sql")
        tables = _extract_create_tables(content)
        assert len(tables) >= 7, f"用能分析 DDL 表数不足 7 张，实际: {len(tables)}"
        for expected_table in [
            "energy_consumption_detail",
            "energy_consumption_summary",
            "energy_dimension_compare",
            "energy_trend_data",
            "energy_quota",
            "energy_balance",
            "energy_cost_analysis",
        ]:
            assert expected_table in tables, f"用能分析 DDL 缺少表: {expected_table}"

    def test_carbon_emission_ddl_has_7_tables(self):
        """碳排放核算 DDL 包含 7 张表."""
        content = _read_file(DDL_DIR / "03_carbon_emission_ddl.sql")
        tables = _extract_create_tables(content)
        assert len(tables) >= 7, f"碳排放核算 DDL 表数不足 7 张，实际: {len(tables)}"
        for expected_table in [
            "emission_factor_library",
            "emission_source",
            "emission_calculation_result",
            "emission_calculation_model",
            "emission_scope_classification",
            "emission_reduction_target",
            "emission_report",
        ]:
            assert expected_table in tables, f"碳排放核算 DDL 缺少表: {expected_table}"

    def test_energy_forecast_ddl_has_5_tables(self):
        """趋势预测 DDL 包含 5 张表."""
        content = _read_file(DDL_DIR / "04_energy_forecast_ddl.sql")
        tables = _extract_create_tables(content)
        assert len(tables) >= 5, f"趋势预测 DDL 表数不足 5 张，实际: {len(tables)}"
        for expected_table in [
            "forecast_parameter",
            "forecast_result",
            "forecast_model_evaluation",
            "forecast_model_registry",
            "forecast_confidence_interval",
        ]:
            assert expected_table in tables, f"趋势预测 DDL 缺少表: {expected_table}"

    def test_total_tables_at_least_31(self):
        """所有 DDL 合计表数 >= 31 张."""
        all_tables: list[str] = []
        for ddl_file in DDL_DIR.glob("*.sql"):
            content = _read_file(ddl_file)
            all_tables.extend(_extract_create_tables(content))
        # 去重（不同 DDL 文件不应有同名表）
        unique_tables = set(all_tables)
        assert len(unique_tables) >= 31, (
            f"DDL 总表数不足 31 张，实际: {len(unique_tables)}, 表: {sorted(unique_tables)}"
        )

    def test_ddl_has_health_score_formula(self):
        """设备监测 DDL 包含健康度评分公式注释."""
        content = _read_file(DDL_DIR / "01_device_monitoring_ddl.sql")
        assert "health_score" in content, "设备监测 DDL 未包含 health_score 字段"
        assert "availability_score" in content, "设备监测 DDL 未包含 availability_score"
        assert "performance_score" in content, "设备监测 DDL 未包含 performance_score"
        assert "alarm_score" in content, "设备监测 DDL 未包含 alarm_score"

    def test_ddl_has_emission_formula(self):
        """碳排放 DDL 包含排放量计算公式 E = AD × EF × GWP."""
        content = _read_file(DDL_DIR / "03_carbon_emission_ddl.sql")
        assert "emission_amount" in content, "碳排放 DDL 未包含 emission_amount"
        assert "activity_data" in content, "碳排放 DDL 未包含 activity_data"
        assert "factor_value" in content, "碳排放 DDL 未包含 factor_value"
        assert "gwp" in content.lower(), "碳排放 DDL 未包含 GWP"

    def test_ddl_has_iotdb_reference(self):
        """DDL 包含 IoTDB 时序数据引用."""
        content = _read_file(DDL_DIR / "01_device_monitoring_ddl.sql")
        assert "iotdb" in content.lower(), "设备监测 DDL 未包含 IoTDB 引用"


# ---------------------------------------------------------------------------
# 测试类 7-10：DAG 场景
# ---------------------------------------------------------------------------
class TestDagScenarios:
    """DAG 场景测试：验证 5 个 DAG Python 文件可解析且逻辑正确."""

    def test_dag_files_exist(self):
        """5 个 DAG 文件全部存在."""
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

    def test_device_monitoring_dag_logic(self):
        """设备监控 DAG 包含健康度计算与告警触发逻辑."""
        content = _read_file(DAG_DIR / "device_status_monitoring.py")
        assert "iotdb" in content.lower(), "设备监控 DAG 未包含 iotdb 关键字"
        assert "health" in content.lower(), "设备监控 DAG 未包含 health 计算"
        assert "alarm" in content.lower(), "设备监控 DAG 未包含 alarm 触发"

    def test_energy_consumption_dag_logic(self):
        """用能统计 DAG 包含同比环比与多维聚合逻辑."""
        content = _read_file(DAG_DIR / "energy_consumption_statistics.py")
        assert "yoy" in content.lower() or "同比" in content, (
            "用能统计 DAG 未包含同比计算"
        )
        assert "mom" in content.lower() or "环比" in content, (
            "用能统计 DAG 未包含环比计算"
        )
        assert "dimension" in content.lower() or "维度" in content, (
            "用能统计 DAG 未包含多维聚合"
        )

    def test_carbon_emission_dag_logic(self):
        """碳排放核算 DAG 包含 E=AD×EF×GWP 计算逻辑."""
        content = _read_file(DAG_DIR / "carbon_emission_calculation.py")
        assert "activity_data" in content or "活动数据" in content, (
            "碳排放核算 DAG 未包含活动数据"
        )
        assert "factor" in content.lower() or "因子" in content, (
            "碳排放核算 DAG 未包含排放因子"
        )
        assert "gwp" in content.lower() or "GWP" in content, (
            "碳排放核算 DAG 未包含 GWP"
        )
        assert "scope" in content.lower(), "碳排放核算 DAG 未包含 Scope 分类"

    def test_dag_count_at_least_5(self):
        """DAG 文件数 >= 5 个."""
        dag_files = list(DAG_DIR.glob("*.py"))
        assert len(dag_files) >= 5, f"DAG 文件数不足 5 个，实际: {len(dag_files)}"


# ---------------------------------------------------------------------------
# 测试类 11-14：Dashboard 场景
# ---------------------------------------------------------------------------
class TestDashboardScenarios:
    """Dashboard 场景测试：验证 4 个 Dashboard JSON 格式正确."""

    def test_dashboard_files_exist(self):
        """4 个 Dashboard 文件全部存在."""
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

    def test_device_monitoring_dashboard(self):
        """设备监测看板包含设备地图/实时状态/告警列表/健康度分布图表."""
        filepath = DASHBOARD_DIR / "device_monitoring_dashboard.json"
        data = json.loads(_read_file(filepath))
        dash = data["dashboards"][0]
        chart_ids = [c["chart_id"] for c in dash["charts"]]
        assert "device_map" in chart_ids, "设备监测看板缺少 device_map 图表"
        assert "realtime_status_table" in chart_ids, (
            "设备监测看板缺少 realtime_status_table 图表"
        )
        assert "alarm_list" in chart_ids, "设备监测看板缺少 alarm_list 图表"
        assert "health_score_distribution" in chart_ids, (
            "设备监测看板缺少 health_score_distribution 图表"
        )

    def test_energy_consumption_dashboard(self):
        """用能分析看板包含能耗趋势/多维对比/TopN/同比环比图表."""
        filepath = DASHBOARD_DIR / "energy_consumption_dashboard.json"
        data = json.loads(_read_file(filepath))
        dash = data["dashboards"][0]
        chart_ids = [c["chart_id"] for c in dash["charts"]]
        assert "consumption_trend" in chart_ids, "用能分析看板缺少 consumption_trend 图表"
        assert "department_compare" in chart_ids, (
            "用能分析看板缺少 department_compare 图表"
        )
        assert "topn_devices" in chart_ids, "用能分析看板缺少 topn_devices 图表"
        assert "yoy_mom" in chart_ids, "用能分析看板缺少 yoy_mom 图表"

    def test_carbon_emission_dashboard(self):
        """碳排放看板包含排放总量/排放结构/趋势/因子库图表."""
        filepath = DASHBOARD_DIR / "carbon_emission_dashboard.json"
        data = json.loads(_read_file(filepath))
        dash = data["dashboards"][0]
        chart_ids = [c["chart_id"] for c in dash["charts"]]
        assert "total_emission_trend" in chart_ids, (
            "碳排放看板缺少 total_emission_trend 图表"
        )
        assert "scope_structure" in chart_ids, "碳排放看板缺少 scope_structure 图表"
        assert "factor_library_table" in chart_ids, (
            "碳排放看板缺少 factor_library_table 图表"
        )

    def test_dashboard_count_at_least_4(self):
        """Dashboard 文件数 >= 4 个."""
        dash_files = list(DASHBOARD_DIR.glob("*.json"))
        assert len(dash_files) >= 4, f"Dashboard 文件数不足 4 个，实际: {len(dash_files)}"


# ---------------------------------------------------------------------------
# 测试类 15-16：Helm Chart 场景
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
        assert data["name"] == "energy-template", (
            "Chart.yaml name 应为 energy-template"
        )
        assert data["type"] == "application", "Chart.yaml type 应为 application"
        assert data["version"] == "1.0.0", "Chart.yaml version 应为 1.0.0"
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
            ["helm", "template", "energy-template", str(CHART_ROOT)],
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
# 测试类 17：RBAC 场景
# ---------------------------------------------------------------------------
class TestRbacScenarios:
    """RBAC 场景测试：验证角色权限矩阵一致性."""

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
        # 验证资源数 >= 40（31 表 + 5 DAG + 4 Dashboard）
        assert len(data["resources"]) >= 40, (
            f"资源数不足 40 个，实际: {len(data['resources'])}"
        )

    def test_role_permissions_yaml_valid(self):
        """role-permissions.yaml YAML 格式正确."""
        filepath = RBAC_DIR / "role-permissions.yaml"
        data = yaml.safe_load(_read_file(filepath))
        assert "role_permissions" in data, (
            "role-permissions.yaml 缺少 role_permissions 键"
        )
        # 验证每个角色都有权限映射
        for rp in data["role_permissions"]:
            assert "role" in rp, "角色权限关联缺少 role 键"
            assert "permissions" in rp, "角色权限关联缺少 permissions 键"
            assert len(rp["permissions"]) > 0, f"角色 {rp['role']} 无权限"

    def test_rbac_minimal_privilege_principle(self):
        """RBAC 遵循最小权限原则：设备运维员不可访问碳排放表."""
        filepath = RBAC_DIR / "roles.yaml"
        data = yaml.safe_load(_read_file(filepath))
        for role in data["roles"]:
            if role["name"] == "device_operator":
                denied = role["permissions_scope"].get("tables_denied", [])
                assert "emission_factor_library" in denied, (
                    "设备运维员应被拒绝访问 emission_factor_library 表"
                )
                assert "emission_calculation_result" in denied, (
                    "设备运维员应被拒绝访问 emission_calculation_result 表"
                )
            if role["name"] == "carbon_accountant":
                denied = role["permissions_scope"].get("tables_denied", [])
                assert "energy_device" in denied, (
                    "碳排放核算员应被拒绝访问 energy_device 表"
                )


# ---------------------------------------------------------------------------
# 测试类 18：IoTDB 场景
# ---------------------------------------------------------------------------
class TestIotdbScenarios:
    """IoTDB 场景测试：验证 JDBC 配置 + Flink Connector 配置 + 数据模型正确."""

    def test_iotdb_config_files_exist(self):
        """IoTDB 配置文件全部存在（3 个）."""
        for filename in EXPECTED_IOTDB_CONFIGS:
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
        assert len(data["timeseries"]["metrics"]) >= 8, (
            "IoTDB 配置传感器指标不足 8 个"
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

    def test_device_data_model_valid(self):
        """设备数据时序模型 YAML 格式正确且包含多种设备类型."""
        filepath = IOTDB_DIR / "device-data-model.yaml"
        data = yaml.safe_load(_read_file(filepath))
        assert "model" in data, "设备数据模型缺少 model 键"
        assert "deviceTypes" in data, "设备数据模型缺少 deviceTypes 键"
        # 验证至少包含 7 种设备类型
        assert len(data["deviceTypes"]) >= 7, (
            f"设备类型不足 7 种，实际: {len(data['deviceTypes'])}"
        )
        # 验证包含电表类型
        type_names = [t["type"] for t in data["deviceTypes"]]
        assert "ELECTRIC_METER" in type_names, "设备数据模型缺少 ELECTRIC_METER 类型"


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