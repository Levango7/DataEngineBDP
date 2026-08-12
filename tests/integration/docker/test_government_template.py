"""政务行业模板（Government Industry Template, T044）集成测试.

被测对象：platform/industry-templates/templates/government/ 全部交付物
         platform/industry-templates/charts/government-template/ Helm Chart

测试覆盖：
    - DDL 场景：36 张表 DDL 语法正确（CREATE TABLE 语句可解析）
    - DAG 场景：8 个 DAG Python 文件可解析（import 语法正确）
    - Dashboard 场景：4 个 Dashboard JSON 格式正确
    - 合规配置场景：4 个合规 YAML（数据分级/脱敏/审计/访问控制）格式正确
    - Helm 部署场景：Chart.yaml + values.yaml + templates 可渲染
    - RBAC 场景：角色权限矩阵一致性
    - 交叉一致性：DDL 表数与 RBAC 资源数一致、DAG 数与 RBAC DAG 资源一致等

运行方式：
    pytest tests/integration/docker/test_government_template.py -v

Author: T044 政务模板工程师
"""
from __future__ import annotations

import ast
import json
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

# 政务模板根目录：platform/industry-templates/templates/government/
TEMPLATE_ROOT = (
    PROJECT_ROOT / "platform" / "industry-templates" / "templates" / "government"
)

# Helm Chart 目录：platform/industry-templates/charts/government-template/
CHART_ROOT = (
    PROJECT_ROOT
    / "platform"
    / "industry-templates"
    / "charts"
    / "government-template"
)

# 各子目录
DDL_DIR = TEMPLATE_ROOT / "ddl"
DAG_DIR = TEMPLATE_ROOT / "dag"
DASHBOARD_DIR = TEMPLATE_ROOT / "dashboards"
RBAC_DIR = TEMPLATE_ROOT / "rbac"
COMPLIANCE_DIR = TEMPLATE_ROOT / "compliance"

# 期望的表清单（36 张）
EXPECTED_TABLES = [
    # 人口分析域（8 张）
    "population_base",
    "population_structure",
    "population_flow",
    "population_forecast",
    "population_age_distribution",
    "population_gender_distribution",
    "population_education_distribution",
    "population_employment_distribution",
    # 经济运行域（8 张）
    "gdp",
    "industry_structure",
    "fixed_asset_investment",
    "social_retail_consumption",
    "foreign_trade",
    "fiscal_revenue",
    "fiscal_expenditure",
    "economic_indicator",
    # 民生服务域（8 张）
    "government_service",
    "service_transaction",
    "service_satisfaction",
    "service_hot_topic",
    "service_statistics",
    "service_evaluation",
    "service_category",
    "service_channel",
    # 政务合规域（8 张）
    "data_classification",
    "desensitize_rule",
    "audit_log",
    "access_control_policy",
    "access_control_record",
    "compliance_risk_alert",
    "compliance_check_record",
    "compliance_policy",
    # RBAC 域（4 张）
    "gov_role",
    "gov_permission",
    "gov_role_permission",
    "gov_user_role",
]

# 期望的 DAG 文件（8 个）
EXPECTED_DAGS = [
    "population_structure_analysis.py",
    "population_flow_tracking.py",
    "population_forecast.py",
    "gdp_calculation.py",
    "industry_analysis.py",
    "investment_consumption_analysis.py",
    "government_service_statistics.py",
    "satisfaction_analysis.py",
]

# 期望的 Dashboard 文件（4 个）
EXPECTED_DASHBOARDS = [
    "population_dashboard.json",
    "economic_dashboard.json",
    "livelihood_dashboard.json",
    "compliance_dashboard.json",
]

# 期望的 RBAC 角色（5 个）
EXPECTED_ROLES = [
    "gov_admin",
    "data_analyst",
    "dept_user",
    "auditor",
    "public_user",
]

# 期望的合规配置文件（4 个）
EXPECTED_COMPLIANCE_FILES = [
    "data-classification.yaml",
    "desensitize-rules.yaml",
    "audit-policy.yaml",
    "access-control.yaml",
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
    pattern = re.compile(
        r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)", re.IGNORECASE
    )
    return pattern.findall(sql_content)


# ---------------------------------------------------------------------------
# 测试类：DDL 场景
# ---------------------------------------------------------------------------
class TestDdlScenarios:
    """DDL 场景测试：验证 36 张表 DDL 语法正确."""

    def test_ddl_directory_exists(self):
        """DDL 目录存在."""
        assert DDL_DIR.exists(), f"DDL 目录不存在: {DDL_DIR}"
        assert DDL_DIR.is_dir(), f"DDL 路径不是目录: {DDL_DIR}"

    def test_ddl_files_exist(self):
        """5 个 DDL 文件全部存在."""
        expected_files = [
            "01_population_analysis_ddl.sql",
            "02_economic_operation_ddl.sql",
            "03_livelihood_service_ddl.sql",
            "04_government_compliance_ddl.sql",
            "rbac_ddl.sql",
        ]
        for filename in expected_files:
            filepath = DDL_DIR / filename
            assert filepath.exists(), f"DDL 文件不存在: {filepath}"

    def test_population_ddl_has_8_tables(self):
        """人口分析 DDL 包含 8 张表."""
        content = _read_file(DDL_DIR / "01_population_analysis_ddl.sql")
        tables = _extract_create_tables(content)
        assert len(tables) >= 8, f"人口分析 DDL 表数不足 8 张，实际: {len(tables)}"
        for expected_table in [
            "population_base",
            "population_structure",
            "population_flow",
            "population_forecast",
        ]:
            assert expected_table in tables, f"人口分析 DDL 缺少表: {expected_table}"

    def test_economic_ddl_has_8_tables(self):
        """经济运行 DDL 包含 8 张表."""
        content = _read_file(DDL_DIR / "02_economic_operation_ddl.sql")
        tables = _extract_create_tables(content)
        assert len(tables) >= 8, f"经济运行 DDL 表数不足 8 张，实际: {len(tables)}"
        for expected_table in [
            "gdp",
            "industry_structure",
            "fixed_asset_investment",
            "fiscal_revenue",
        ]:
            assert expected_table in tables, f"经济运行 DDL 缺少表: {expected_table}"

    def test_livelihood_ddl_has_8_tables(self):
        """民生服务 DDL 包含 8 张表."""
        content = _read_file(DDL_DIR / "03_livelihood_service_ddl.sql")
        tables = _extract_create_tables(content)
        assert len(tables) >= 8, f"民生服务 DDL 表数不足 8 张，实际: {len(tables)}"
        for expected_table in [
            "government_service",
            "service_transaction",
            "service_satisfaction",
            "service_hot_topic",
        ]:
            assert expected_table in tables, f"民生服务 DDL 缺少表: {expected_table}"

    def test_compliance_ddl_has_8_tables(self):
        """政务合规 DDL 包含 8 张表."""
        content = _read_file(DDL_DIR / "04_government_compliance_ddl.sql")
        tables = _extract_create_tables(content)
        assert len(tables) >= 8, f"政务合规 DDL 表数不足 8 张，实际: {len(tables)}"
        for expected_table in [
            "data_classification",
            "desensitize_rule",
            "audit_log",
            "access_control_policy",
        ]:
            assert expected_table in tables, f"政务合规 DDL 缺少表: {expected_table}"

    def test_rbac_ddl_has_4_tables(self):
        """RBAC DDL 包含 4 张表."""
        content = _read_file(DDL_DIR / "rbac_ddl.sql")
        tables = _extract_create_tables(content)
        assert len(tables) >= 4, f"RBAC DDL 表数不足 4 张，实际: {len(tables)}"
        for expected_table in [
            "gov_role",
            "gov_permission",
            "gov_role_permission",
            "gov_user_role",
        ]:
            assert expected_table in tables, f"RBAC DDL 缺少表: {expected_table}"

    def test_total_tables_at_least_36(self):
        """所有 DDL 合计表数 >= 36 张."""
        all_tables: list[str] = []
        for ddl_file in DDL_DIR.glob("*.sql"):
            content = _read_file(ddl_file)
            all_tables.extend(_extract_create_tables(content))
        unique_tables = set(all_tables)
        assert len(unique_tables) >= 36, (
            f"DDL 总表数不足 36 张，实际: {len(unique_tables)}, 表: {sorted(unique_tables)}"
        )

    def test_ddl_uses_doris_syntax(self):
        """DDL 使用 Doris 兼容语法（DUPLICATE KEY / OLAP 引擎）."""
        for ddl_file in DDL_DIR.glob("*.sql"):
            content = _read_file(ddl_file)
            # 至少一个文件应包含 Doris OLAP 引擎特征
            if "population_base" in content or "gdp" in content:
                assert "DUPLICATE" in content.upper() or "OLAP" in content.upper(), (
                    f"DDL 文件 {ddl_file.name} 未使用 Doris OLAP 引擎语法"
                )


# ---------------------------------------------------------------------------
# 测试类：DAG 场景
# ---------------------------------------------------------------------------
class TestDagScenarios:
    """DAG 场景测试：验证 8 个 DAG Python 文件可解析."""

    def test_dag_directory_exists(self):
        """DAG 目录存在."""
        assert DAG_DIR.exists(), f"DAG 目录不存在: {DAG_DIR}"

    def test_dag_files_exist(self):
        """8 个 DAG 文件全部存在."""
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

    def test_dag_count_at_least_8(self):
        """DAG 文件数 >= 8 个."""
        dag_files = list(DAG_DIR.glob("*.py"))
        assert len(dag_files) >= 8, f"DAG 文件数不足 8 个，实际: {len(dag_files)}"


# ---------------------------------------------------------------------------
# 测试类：Dashboard 场景
# ---------------------------------------------------------------------------
class TestDashboardScenarios:
    """Dashboard 场景测试：验证 4 个 Dashboard JSON 格式正确."""

    def test_dashboard_directory_exists(self):
        """Dashboard 目录存在."""
        assert DASHBOARD_DIR.exists(), f"Dashboard 目录不存在: {DASHBOARD_DIR}"

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


# ---------------------------------------------------------------------------
# 测试类：政务合规配置场景
# ---------------------------------------------------------------------------
class TestComplianceScenarios:
    """政务合规配置场景测试：验证 4 个合规 YAML 格式正确."""

    def test_compliance_directory_exists(self):
        """合规配置目录存在."""
        assert COMPLIANCE_DIR.exists(), f"合规配置目录不存在: {COMPLIANCE_DIR}"

    def test_compliance_files_exist(self):
        """4 个合规配置文件全部存在."""
        for filename in EXPECTED_COMPLIANCE_FILES:
            filepath = COMPLIANCE_DIR / filename
            assert filepath.exists(), f"合规配置文件不存在: {filepath}"

    def test_data_classification_yaml_valid(self):
        """数据分级配置 YAML 格式正确且包含 4 级分级."""
        filepath = COMPLIANCE_DIR / "data-classification.yaml"
        data = yaml.safe_load(_read_file(filepath))
        assert "data_classification" in data, "数据分级配置缺少 data_classification 键"
        assert "levels" in data["data_classification"], "数据分级配置缺少 levels 键"
        levels = data["data_classification"]["levels"]
        assert len(levels) >= 4, f"数据分级不足 4 级，实际: {len(levels)}"
        level_codes = [lvl["level_code"] for lvl in levels]
        for expected_level in ["L1", "L2", "L3", "L4"]:
            assert expected_level in level_codes, f"缺少数据分级: {expected_level}"

    def test_desensitize_rules_yaml_valid(self):
        """脱敏规则 YAML 格式正确且包含脱敏规则."""
        filepath = COMPLIANCE_DIR / "desensitize-rules.yaml"
        data = yaml.safe_load(_read_file(filepath))
        assert "desensitize_rules" in data, "脱敏规则配置缺少 desensitize_rules 键"
        rules = data["desensitize_rules"]
        assert isinstance(rules, list), "脱敏规则应为列表"
        assert len(rules) >= 5, f"脱敏规则不足 5 条，实际: {len(rules)}"

    def test_audit_policy_yaml_valid(self):
        """审计策略 YAML 格式正确."""
        filepath = COMPLIANCE_DIR / "audit-policy.yaml"
        data = yaml.safe_load(_read_file(filepath))
        assert "audit_policy" in data, "审计策略配置缺少 audit_policy 键"

    def test_access_control_yaml_valid(self):
        """访问控制策略 YAML 格式正确."""
        filepath = COMPLIANCE_DIR / "access-control.yaml"
        data = yaml.safe_load(_read_file(filepath))
        assert "access_control" in data, "访问控制配置缺少 access_control 键"


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
        assert data["name"] == "government-template", (
            "Chart.yaml name 应为 government-template"
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
        assert "complianceSync" in data, "values.yaml 缺少 complianceSync 键"
        # 验证目标组件配置
        assert "doris" in data["target"], "values.yaml 缺少 doris 目标配置"
        assert "spark" in data["target"], "values.yaml 缺少 spark 目标配置"

    def test_chart_templates_exist(self):
        """Chart templates 目录存在且包含模板文件."""
        templates_dir = CHART_ROOT / "templates"
        assert templates_dir.exists(), f"templates 目录不存在: {templates_dir}"
        template_files = list(templates_dir.glob("*"))
        assert len(template_files) >= 2, (
            f"templates 目录文件数不足 2 个，实际: {len(template_files)}"
        )
        template_names = [f.name for f in template_files]
        assert any("configmap" in n for n in template_names), (
            "缺少 ConfigMap 模板"
        )
        assert any("import" in n and "job" in n for n in template_names), (
            "缺少导入 Job 模板"
        )

    def test_helm_lint_if_available(self):
        """如果 helm CLI 可用，执行 helm lint 验证 Chart 有效性."""
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

        result = subprocess.run(
            ["helm", "template", "government-template", str(CHART_ROOT)],
            capture_output=True,
            text=True,
            timeout=30,
        )
        assert result.returncode == 0, (
            f"helm template 失败:\nstdout: {result.stdout}\nstderr: {result.stderr}"
        )
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
        """roles.yaml YAML 格式正确且包含 5 个角色."""
        filepath = RBAC_DIR / "roles.yaml"
        data = yaml.safe_load(_read_file(filepath))
        assert "roles" in data, "roles.yaml 缺少 roles 键"
        role_names = [r["name"] for r in data["roles"]]
        assert len(role_names) >= 5, f"角色数不足 5 个，实际: {len(role_names)}"
        for expected_role in EXPECTED_ROLES:
            assert expected_role in role_names, f"缺少角色: {expected_role}"

    def test_permissions_yaml_valid(self):
        """permissions.yaml YAML 格式正确且包含资源定义."""
        filepath = RBAC_DIR / "permissions.yaml"
        data = yaml.safe_load(_read_file(filepath))
        assert "resources" in data, "permissions.yaml 缺少 resources 键"
        assert "permissions" in data, "permissions.yaml 缺少 permissions 键"
        # 验证资源数 >= 40（36 表 + 8 DAG + 4 Dashboard = 48，但至少 40）
        assert len(data["resources"]) >= 40, (
            f"资源数不足 40 个，实际: {len(data['resources'])}"
        )

    def test_role_permissions_yaml_valid(self):
        """role-permissions.yaml YAML 格式正确."""
        filepath = RBAC_DIR / "role-permissions.yaml"
        data = yaml.safe_load(_read_file(filepath))
        assert "role_permissions" in data, "role-permissions.yaml 缺少 role_permissions 键"
        for rp in data["role_permissions"]:
            assert "role" in rp, "角色权限关联缺少 role 键"
            assert "permissions" in rp, "角色权限关联缺少 permissions 键"
            assert len(rp["permissions"]) > 0, f"角色 {rp['role']} 无权限"

    def test_rbac_minimal_privilege_principle(self):
        """RBAC 遵循最小权限原则：公众用户不可访问审计日志."""
        filepath = RBAC_DIR / "roles.yaml"
        data = yaml.safe_load(_read_file(filepath))
        for role in data["roles"]:
            if role["name"] == "public_user":
                denied = role["permissions_scope"].get("tables_denied", [])
                assert "audit_log" in denied, "公众用户应被拒绝访问 audit_log 表"
                assert "gov_permission" in denied, (
                    "公众用户应被拒绝访问 gov_permission 表"
                )


# ---------------------------------------------------------------------------
# 测试类：交叉一致性
# ---------------------------------------------------------------------------
class TestCrossConsistency:
    """交叉一致性测试：DDL/DAG/Dashboard/RBAC 之间的一致性."""

    def test_ddl_table_count_matches_rbac_resources(self):
        """DDL 表数与 RBAC permissions.yaml 中 table 资源数一致."""
        all_tables: set[str] = set()
        for ddl_file in DDL_DIR.glob("*.sql"):
            content = _read_file(ddl_file)
            all_tables.update(_extract_create_tables(content))

        perm_data = yaml.safe_load(_read_file(RBAC_DIR / "permissions.yaml"))
        rbac_tables = {
            r["name"] for r in perm_data["resources"] if r["type"] == "table"
        }

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
        """政务模板 README.md 存在."""
        readme = TEMPLATE_ROOT / "README.md"
        assert readme.exists(), f"README.md 不存在: {readme}"
        content = _read_file(readme)
        assert "政务" in content or "Government" in content or "government" in content, (
            "README.md 未包含政务模板标题"
        )

    def test_template_metadata_exists(self):
        """模板元数据 template-metadata.yaml 存在且格式正确."""
        metadata = TEMPLATE_ROOT / "template-metadata.yaml"
        assert metadata.exists(), f"template-metadata.yaml 不存在: {metadata}"
        data = yaml.safe_load(_read_file(metadata))
        assert "template" in data, "template-metadata.yaml 缺少 template 键"
        assert data["template"]["name"] == "government-template", (
            "template-metadata.yaml 模板名应为 government-template"
        )
        assert "dependencies" in data, "template-metadata.yaml 缺少 dependencies 键"
        assert "contents" in data, "template-metadata.yaml 缺少 contents 键"