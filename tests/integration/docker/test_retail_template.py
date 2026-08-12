"""零售行业模板（T038）集成测试.

被测对象：platform/industry-templates/templates/retail/ 零售行业模板资产
         platform/industry-templates/charts/retail-template/ Helm Chart

覆盖范围（≥ 18 个测试用例）：
- DDL 场景：≥ 15 张表 DDL 语法正确（4 个测试）
- DAG 场景：≥ 5 个 DAG 可解析，含机器学习模型（4 个测试）
- Dashboard 场景：≥ 3 个 Dashboard JSON 格式正确（3 个测试）
- 标签引擎场景：标签计算配置正确（2 个测试）
- Helm 部署场景：helm install retail-template 可部署（3 个测试）
- RFM 分群逻辑验证（1 个测试）
- A/B 实验显著性检验验证（1 个测试）
- 转化漏斗计算验证（1 个测试）

运行方式：
    pytest tests/integration/docker/test_retail_template.py -v
"""
from __future__ import annotations

import json
import os
import re
import sys
import textwrap
from pathlib import Path

import pytest
import yaml

# ============================================================
# 路径常量
# ============================================================
_PROJECT_ROOT = Path(__file__).resolve().parents[3]
RETAIL_TEMPLATE_DIR = _PROJECT_ROOT / "platform" / "industry-templates" / "templates" / "retail"
RETAIL_CHART_DIR = _PROJECT_ROOT / "platform" / "industry-templates" / "charts" / "retail-template"

DDL_DIR = RETAIL_TEMPLATE_DIR / "ddl"
DAG_DIR = RETAIL_TEMPLATE_DIR / "dag"
DASHBOARD_DIR = RETAIL_TEMPLATE_DIR / "dashboards"
TAG_ENGINE_DIR = RETAIL_TEMPLATE_DIR / "tag-engine"
CHART_TEMPLATES_DIR = RETAIL_CHART_DIR / "templates"


# ============================================================
# 公共 fixtures
# ============================================================
@pytest.fixture(scope="session")
def ddl_files() -> list[Path]:
    """返回所有 DDL 文件列表."""
    return sorted(DDL_DIR.glob("*.sql"))


@pytest.fixture(scope="session")
def dag_files() -> list[Path]:
    """返回所有 DAG 文件列表."""
    return sorted(DAG_DIR.glob("*.py"))


@pytest.fixture(scope="session")
def dashboard_files() -> list[Path]:
    """返回所有 Dashboard 文件列表."""
    return sorted(DASHBOARD_DIR.glob("*.json"))


# ============================================================
# 1. DDL 场景测试（≥ 15 张表 DDL 语法正确）
# ============================================================
class TestDDL:
    """DDL 场景测试."""

    def test_ddl_files_exist(self, ddl_files: list[Path]):
        """DDL 文件存在且 ≥ 4 个（商品画像/会员分析/营销效果/RBAC）."""
        assert len(ddl_files) >= 4, f"DDL 文件数不足：{len(ddl_files)} < 4"
        expected_files = {
            "product_profile_ddl#sql",
            "member_analysis_ddl#sql",
            "marketing_effect_ddl#sql",
            "rbac_ddl#sql",
        }
        actual_files = {f.name.replace(".", "#") for f in ddl_files}
        missing = expected_files - actual_files
        assert not missing, f"缺失 DDL 文件：{missing}"

    def test_ddl_table_count_at_least_15(self, ddl_files: list[Path]):
        """DDL 表数量 ≥ 15 张（商品画像 6 + 会员分析 6 + 营销效果 6 = 18，加 RBAC 6 = 24）."""
        create_pattern = re.compile(
            r"CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\s+(\w+)", re.IGNORECASE
        )
        all_tables: list[str] = []
        for ddl_file in ddl_files:
            content = ddl_file.read_text(encoding="utf-8")
            tables = create_pattern.findall(content)
            all_tables.extend(tables)
        assert len(all_tables) >= 15, (
            f"DDL 表数量不足：{len(all_tables)} < 15，表列表：{all_tables}"
        )

    def test_ddl_syntax_valid(self, ddl_files: list[Path]):
        """DDL 语法基本正确（CREATE TABLE 语句完整，含 ENGINE/COMMENT）."""
        for ddl_file in ddl_files:
            content = ddl_file.read_text(encoding="utf-8")
            # 检查 CREATE TABLE 语句存在
            assert "CREATE TABLE" in content.upper(), f"{ddl_file.name} 缺少 CREATE TABLE"
            # 检查 ENGINE 关键字
            assert "ENGINE" in content.upper(), f"{ddl_file.name} 缺少 ENGINE 子句"
            # 检查 COMMENT 关键字
            assert "COMMENT" in content.upper(), f"{ddl_file.name} 缺少 COMMENT"

    def test_ddl_expected_tables_present(self, ddl_files: list[Path]):
        """DDL 包含预期的关键表（商品/会员/营销/RBAC）."""
        create_pattern = re.compile(
            r"CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\s+(\w+)", re.IGNORECASE
        )
        all_tables: set[str] = set()
        for ddl_file in ddl_files:
            content = ddl_file.read_text(encoding="utf-8")
            tables = create_pattern.findall(content)
            all_tables.update(t.lower() for t in tables)
        expected_tables = {
            "product", "product_category", "product_brand", "product_sales_stat",
            "product_review_profile", "product_tag",
            "member", "member_rfm", "member_churn_prediction", "member_ltv",
            "member_tag", "member_behavior_profile",
            "ab_experiment", "ab_experiment_variant", "conversion_funnel",
            "marketing_campaign", "marketing_roi", "marketing_channel_stat",
            "retail_role", "retail_permission", "retail_role_permission",
        }
        missing = expected_tables - all_tables
        assert not missing, f"缺失关键表：{missing}"


# ============================================================
# 2. DAG 场景测试（≥ 5 个 DAG 可解析，含机器学习模型）
# ============================================================
class TestDAG:
    """DAG 场景测试."""

    def test_dag_files_exist(self, dag_files: list[Path]):
        """DAG 文件存在且 ≥ 5 个."""
        assert len(dag_files) >= 5, f"DAG 文件数不足：{len(dag_files)} < 5"
        expected_dags = {
            "rfm_segmentation#py",
            "churn_prediction#py",
            "ltv_calculation#py",
            "ab_experiment#py",
            "conversion_funnel#py",
            "roi_analysis#py",
        }
        actual_dags = {f.name.replace(".", "#") for f in dag_files}
        missing = expected_dags - actual_dags
        assert not missing, f"缺失 DAG 文件：{missing}"

    def test_dag_python_syntax_valid(self, dag_files: list[Path]):
        """DAG Python 语法正确（可编译）."""
        for dag_file in dag_files:
            content = dag_file.read_text(encoding="utf-8")
            # 使用 compile 检查语法
            try:
                compile(content, str(dag_file), "exec")
            except SyntaxError as e:
                pytest.fail(f"{dag_file.name} Python 语法错误：{e}")

    def test_dag_has_build_dag_function(self, dag_files: list[Path]):
        """每个 DAG 都有 build_dag() 函数和 DAG_ID 常量."""
        for dag_file in dag_files:
            content = dag_file.read_text(encoding="utf-8")
            assert "def build_dag" in content, f"{dag_file.name} 缺少 build_dag() 函数"
            assert "DAG_ID" in content, f"{dag_file.name} 缺少 DAG_ID 常量"
            assert "DAG_SCHEDULE" in content, f"{dag_file.name} 缺少 DAG_SCHEDULE 常量"

    def test_dag_contains_ml_model(self, dag_files: list[Path]):
        """流失预测 DAG 包含机器学习模型（逻辑回归 + GBDT）."""
        churn_dag = DAG_DIR / "churn_prediction.py"
        assert churn_dag.exists(), "流失预测 DAG 不存在"
        content = churn_dag.read_text(encoding="utf-8")
        # �& 检查 ML 相关关键字
        assert "logistic_regression" in content.lower() or "sigmoid" in content.lower(), \
            "流失预测 DAG 缺少逻辑回归模型"
        assert "gbdt" in content.lower() or "ensemble" in content.lower(), \
            "流失预测 DAG 缺少 GBDT 模型"
        assert "churn_probability" in content, "流失预测 DAG 缺少 churn_probability 输出"


# ============================================================
# 3. Dashboard 场景测试（≥ 3 个 Dashboard JSON 格式正确）
# ============================================================
class TestDashboard:
    """Dashboard 场景测试."""

    def test_dashboard_files_exist(self, dashboard_files: list[Path]):
        """Dashboard 文件存在且 ≥ 3 个."""
        assert len(dashboard_files) >= 3, f"Dashboard 文件数不足：{len(dashboard_files)} < 3"
        expected_dashboards = {
            "product_profile_dashboard#json",
            "member_dashboard#json",
            "marketing_dashboard#json",
        }
        actual_dashboards = {f.name.replace(".", "#") for f in dashboard_files}
        missing = expected_dashboards - actual_dashboards
        assert not missing, f"缺失 Dashboard 文件：{missing}"

    def test_dashboard_json_valid(self, dashboard_files: list[Path]):
        """Dashboard JSON 格式正确，含 databases/datasets/dashboards 字段."""
        for dash_file in dashboard_files:
            content = dash_file.read_text(encoding="utf-8")
            try:
                data = json.loads(content)
            except json.JSONDecodeError as e:
                pytest.fail(f"{dash_file.name} JSON 解析失败：{e}")
            assert "version" in data, f"{dash_file.name} 缺少 version 字段"
            assert "databases" in data, f"{dash_file.name} 缺少 databases 字段"
            assert "datasets" in data, f"{dash_file.name} 缺少 datasets 字段"
            assert "dashboards" in data, f"{dash_file.name} 缺少 dashboards 字段"
            assert len(data["dashboards"]) >= 1, f"{dash_file.name} dashboards 为空"

    def test_dashboard_has_charts(self, dashboard_files: list[Path]):
        """每个 Dashboard 含 ≥ 6 个图表."""
        for dash_file in dashboard_files:
            content = dash_file.read_text(encoding="utf-8")
            data = json.loads(content)
            for dashboard in data["dashboards"]:
                assert "charts" in dashboard, f"{dash_file.name} 缺少 charts 字段"
                assert len(dashboard["charts"]) >= 6, (
                    f"{dash_file.name} 图表数不足：{len(dashboard['charts'])} < 6"
                )
                assert "slug" in dashboard, f"{dash_file.name} 缺少 slug 字段"
                assert "title" in dashboard, f"{dash_file.name} 缺少 title 字段"


# ============================================================
# 4. 标签引擎场景测试（标签计算配置正确）
# ============================================================
class TestTagEngine:
    """标签引擎场景测试."""

    @pytest.fixture
    def tag_engine_config(self) -> dict:
        """加载标签引擎配置."""
        config_file = TAG_ENGINE_DIR / "tag-engine-config.yaml"
        assert config_file.exists(), "标签引擎配置文件不存在"
        content = config_file.read_text(encoding="utf-8")
        return yaml.safe_load(content)

    def test_tag_engine_config_valid(self, tag_engine_config: dict):
        """标签引擎配置 YAML 格式正确，含 tag_engine/member_tags/product_tags 字段."""
        assert "tag_engine" in tag_engine_config, "缺少 tag_engine 字段"
        assert "member_tags" in tag_engine_config, "缺少 member_tags 字段"
        assert "product_tags" in tag_engine_config, "缺少 product_tags 字段"

    def test_tag_engine_has_expected_tags(self, tag_engine_config: dict):
        """标签引擎配置含预期的会员/商品标签."""
        member_tags = tag_engine_config.get("member_tags", {}).get("tags", [])
        product_tags = tag_engine_config.get("product_tags", {}).get("tags", [])
        # 会员标签：RFM/CHURN/LTV/BEHAVIOR
        member_tag_codes = {t["code"] for t in member_tags}
        expected_member_tags = {"RFM_CHAMPION", "CHURN_HIGH_RISK", "LTV_VIP", "PRICE_SENSITIVE"}
        missing_member = expected_member_tags - member_tag_codes
        assert not missing_member, f"缺失会员标签：{missing_member}"
        # 商品标签：HOT_PRODUCT/LONG_TAIL/NEW_ARRIVAL
        product_tag_codes = {t["code"] for t in product_tags}
        expected_product_tags = {"HOT_PRODUCT", "LONG_TAIL", "NEW_ARRIVAL"}
        missing_product = expected_product_tags - product_tag_codes
        assert not missing_product, f"缺失商品标签：{missing_product}"


# ============================================================
# 5. Helm 部署场景测试（helm install retail-template 可部署）
# ============================================================
class TestHelmChart:
    """Helm 部署场景测试."""

    def test_chart_yaml_valid(self):
        """Chart.yaml 格式正确，含 apiVersion/name/version 字段."""
        chart_file = RETAIL_CHART_DIR / "Chart.yaml"
        assert chart_file.exists(), "Chart.yaml 不存在"
        content = chart_file.read_text(encoding="utf-8")
        chart = yaml.safe_load(content)
        assert chart["apiVersion"] == "v2", "Chart.yaml apiVersion 应为 v2"
        assert chart["name"] == "retail-template", "Chart.yaml name 应为 retail-template"
        assert "version" in chart, "Chart.yaml 缺少 version 字段"
        assert "description" in chart, "Chart.yaml 缺少 description 字段"
        assert "appVersion" in chart, "Chart.yaml 缺少 appVersion 字段"

    def test_values_yaml_valid(self):
        """values.yaml 格式正确，含 template/namespace/target/configMap 字段."""
        values_file = RETAIL_CHART_DIR / "values.yaml"
        assert values_file.exists(), "values.yaml 不存在"
        content = values_file.read_text(encoding="utf-8")
        values = yaml.safe_load(content)
        assert "template" in values, "values.yaml 缺少 template 字段"
        assert "namespace" in values, "values.yaml 缺少 namespace 字段"
        assert "target" in values, "values.yaml 缺少 target 字段"
        assert "configMap" in values, "values.yaml 缺少 configMap 字段"
        assert "importJob" in values, "values.yaml 缺少 importJob 字段"
        # 检查 target 含各组件
        target = values["target"]
        for component in ["doris", "dolphinscheduler", "superset", "keycloak", "tagEngine"]:
            assert component in target, f"values.yaml target 缺少 {component}"

    def test_chart_templates_exist(self):
        """Chart templates 目录含 ConfigMap 和 Job 模板."""
        assert CHART_TEMPLATES_DIR.exists(), "Chart templates 目录不存在"
        template_files = list(CHART_TEMPLATES_DIR.glob("*"))
        assert len(template_files) >= 2, f"Chart templates 文件数不足：{len(template_files)} < 2"
        template_names = {f.name for f in template_files}
        assert any("configmap" in n.lower() for n in template_names), \
            "Chart templates 缺少 ConfigMap 模板"
        assert any("job" in n.lower() for n in template_names), \
            "Chart templates 缺少 Job 模板"


# ============================================================
# 6. RFM 分群逻辑验证
# ============================================================
class TestRFMLogic:
    """RFM 分群逻辑验证."""

    @pytest.fixture
    def rfm_module(self):
        """导入 RFM 分群 DAG 模块."""
        if str(DAG_DIR) not in sys.path:
            sys.path.insert(0, str(DAG_DIR))
        import rfm_segmentation
        return rfm_segmentation

    def test_rfm_segmentation_logic(self, rfm_module):
        """RFM 分群逻辑正确（冠军/流失/新客分群合理）."""
        r_quantiles = [30, 60, 120, 240]
        f_quantiles = [1, 3, 6, 12]
        m_quantiles = [100, 500, 2000, 5000]
        # 冠军会员：最近购买 + 高频 + 高金额
        champion = rfm_module.compute_rfm_for_member(
            "m_champion", 7, 20, 8000, r_quantiles, f_quantiles, m_quantiles
        )
        assert champion["r_score"] >= 4, f"冠军 R 评分应 >= 4，实际 {champion['r_score']}"
        assert champion["f_score"] >= 4, f"冠军 F 评分应 >= 4，实际 {champion['f_score']}"
        assert champion["m_score"] >= 4, f"冠军 M 评分应 >= 4，实际 {champion['m_score']}"
        assert champion["rfm_segment"] == "CHAMPION", \
            f"冠军分群应为 CHAMPION，实际 {champion['rfm_segment']}"
        assert champion["segment_value_level"] == "HIGH"
        # 流失会员：久未购买 + 低频 + 低金额
        lost = rfm_module.compute_rfm_for_member(
            "m_lost", 300, 1, 50, r_quantiles, f_quantiles, m_quantiles
        )
        assert lost["r_score"] <= 2, f"流失 R 评分应 <= 2，实际 {lost['r_score']}"
        assert lost["segment_value_level"] == "LOW"
        # 新客：最近购买 + 低频
        new_member = rfm_module.compute_rfm_for_member(
            "m_new", 5, 1, 200, r_quantiles, f_quantiles, m_quantiles
        )
        assert new_member["r_score"] >= 4, f"新客 R 评分应 >= 4，实际 {new_member['r_score']}"
        # RFM 总分格式正确（125~555）
        for result in [champion, lost, new_member]:
            assert 111 <= result["rfm_score"] <= 555, \
                f"RFM 总分应在 111~555，实际 {result['rfm_score']}"


# ============================================================
# 7. A/B 实验显著性检验验证
# ============================================================
class TestABExperiment:
    """A/B 实验显著性检验验证."""

    @pytest.fixture
    def ab_module(self):
        """导入 A/B 实验 DAG 模块."""
        if str(DAG_DIR) not in sys.path:
            sys.path.insert(0, str(DAG_DIR))
        import ab_experiment
        return ab_experiment

    def test_ab_significance_test(self, ab_module):
        """A/B 实验显著性检验正确（显著提升 / 不显著场景）."""
        # 场景 1：显著提升（实验组转化率明显高于对照组）
        result_significant = ab_module.run_ab_test(
            "exp_significant",
            control_data={"success": 1000, "total": 10000},  # 10%
            treatment_data={"success": 1300, "total": 10000},  # 13%
        )
        assert result_significant["is_significant"], \
            f"显著场景应判定为显著，P 值={result_significant['p_value']}"
        assert result_significant["lift"] > 0, "显著场景提升度应 > 0"
        assert result_significant["is_winner"], "显著场景应判定为获胜"
        assert 0 <= result_significant["p_value"] < 0.05, \
            f"显著场景 P 值应 < 0.05，实际 {result_significant['p_value']}"
        # 场景 2：不显著（实验组与对照组转化率接近）
        result_not_significant = ab_module.run_ab_test(
            "exp_not_significant",
            control_data={"success": 1000, "total": 10000},  # 10%
            treatment_data={"success": 1010, "total": 10000},  # 10.1%
        )
        assert not result_not_significant["is_significant"], \
            f"不显著场景应判定为不显著，P 值={result_not_significant['p_value']}"
        assert result_not_significant["p_value"] > 0.05, \
            f"不显著场景 P 值应 > 0.05，实际 {result_not_significant['p_value']}"
        # 场景 3：Z 检验基本属性
        result = ab_module.z_test_proportions(100, 1000, 150, 1000)
        assert "z_score" in result
        assert "p_value" in result
        assert "lift" in result
        assert "ci_lower" in result
        assert "ci_upper" in result


# ============================================================
# 8. 转化漏斗计算验证
# ============================================================
class TestConversionFunnel:
    """转化漏斗计算验证."""

    @pytest.fixture
    def funnel_module(self):
        """导入转化漏斗 DAG 模块."""
        if str(DAG_DIR) not in sys.path:
            sys.path.insert(0, str(DAG_DIR))
        import conversion_funnel
        return conversion_funnel

    def test_conversion_funnel_calculation(self, funnel_module):
        """转化漏斗计算正确（各步骤转化率/流失率/总体转化率）."""
        result = funnel_module.compute_funnel(
            funnel_name="测试漏斗",
            campaign_id="camp_test",
            stat_date="2026-08-08",
            exposure_count=100000,
            click_count=15000,
            cart_count=6000,
            order_count=3000,
            pay_count=2400,
            avg_time_to_pay_seconds=3600,
        )
        # 各步骤转化率
        assert abs(result["step_click_rate"] - 0.15) < 0.001, \
            f"点击率应为 0.15，实际 {result['step_click_rate']}"
        assert abs(result["step_cart_rate"] - 0.4) < 0.001, \
            f"加购率应为 0.4，实际 {result['step_cart_rate']}"
        # 总体转化率 = 支付/曝光 = 2400/100000 = 0.024
        assert abs(result["overall_conversion_rate"] - 0.024) < 0.001, \
            f"总体转化率应为 0.024，实际 {result['overall_conversion_rate']}"
        # 流失率 = 1 - 转化率
        assert abs(result["drop_off_exposure_click"] - (1 - 0.15)) < 0.001, \
            f"曝光→点击流失率应为 0.85，实际 {result['drop_off_exposure_click']}"
        # 各步骤数
        assert result["step_exposure_count"] == 100000
        assert result["step_pay_count"] == 2400
        # 边界场景：曝光为 0
        empty_result = funnel_module.compute_funnel(
            "空漏斗", None, "2026-08-08", 0, 0, 0, 0, 0
        )
        assert empty_result["overall_conversion_rate"] == 0.0
        assert empty_result["step_click_rate"] == 0.0


# ============================================================
# 9. LTV 计算验证
# ============================================================
class TestLTVCalculation:
    """LTV 计算验证."""

    @pytest.fixture
    def ltv_module(self):
        """导入 LTV 计算 DAG 模块."""
        if str(DAG_DIR) not in sys.path:
            sys.path.insert(0, str(DAG_DIR))
        import ltv_calculation
        return ltv_calculation

    def test_ltv_calculation(self, ltv_module):
        """LTV 计算正确（历史价值 + 预测价值 + 分层）."""
        result = ltv_module.compute_ltv(
            "m_test", frequency=10, recency_days=15, customer_age_days=365,
            monetary=200,
            bgnbd_params=ltv_module.DEFAULT_BGNBD_PARAMS,
            gg_params=ltv_module.DEFAULT_GG_PARAMS,
        )
        # 历史价值 = 10 * 200 = 2000
        assert result["historical_value"] == 2000.0, \
            f"历史价值应为 2000，实际 {result['historical_value']}"
        # 总 LTV 应 >= 历史价值
        assert result["total_ltv"] >= result["historical_value"], \
            f"总 LTV 应 >= 历史价值，总 LTV={result['total_ltv']}, 历史价值={result['historical_value']}"
        # P(Alive) 在 0~1 之间
        assert 0 <= result["predicted_p_alive"] <= 1, \
            f"P(Alive) 应在 0~1，实际 {result['predicted_p_alive']}"
        # LTV 分层有效
        assert result["ltv_segment"] in {"VIP", "HIGH", "MEDIUM", "LOW", "BOTTOM"}, \
            f"LTV 分层无效：{result['ltv_segment']}"
        # 置信区间 lower < upper
        ci = result["confidence_interval"]
        assert ci["lower"] < ci["upper"], \
            f"置信区间 lower 应 < upper，lower={ci['lower']}, upper={ci['upper']}"


# ============================================================
# 10. ROI 计算验证
# ============================================================
class TestROIAnalysis:
    """ROI 计算验证."""

    @pytest.fixture
    def roi_module(self):
        """导入 ROI 分析 DAG 模块."""
        if str(DAG_DIR) not in sys.path:
            sys.path.insert(0, str(DAG_DIR))
        import roi_analysis
        return roi_analysis

    def test_roi_calculation(self, roi_module):
        """ROI 计算正确（ROI/ROAS/CPA/CPC/盈亏判定）."""
        # 盈利场景：投入 10000，产出 35000
        profitable = roi_module.compute_roi(
            "camp_profitable", "2026-08-08",
            investment_amount=10000, revenue_amount=35000,
            conversion_count=350, click_count=5000, impression_count=100000,
        )
        # ROI = (35000 - 10000) / 10000 = 2.5
        assert abs(profitable["roi"] - 2.5) < 0.001, \
            f"ROI 应为 2.5，实际 {profitable['roi']}"
        # ROAS = 35000 / 10000 = 3.5
        assert abs(profitable["roas"] - 3.5) < 0.001, \
            f"ROAS 应为 3.5，实际 {profitable['roas']}"
        # CPA = 10000 / 350 ≈ 28.57
        assert abs(profitable["cpa"] - 28.57) < 0.1, \
            f"CPA 应为 28.57，实际 {profitable['cpa']}"
        # CPC = 10000 / 5000 = 2.0
        assert abs(profitable["cpc"] - 2.0) < 0.001, \
            f"CPC 应为 2.0，实际 {profitable['cpc']}"
        assert profitable["is_profitable"], "盈利场景应判定为盈利"
        assert profitable["profit_amount"] == 25000.0
        # 亏损场景：投入 10000，产出 5000
        loss = roi_module.compute_roi(
            "camp_loss", "2026-08-08",
            investment_amount=10000, revenue_amount=5000,
            conversion_count=100, click_count=2000, impression_count=50000,
        )
        assert not loss["is_profitable"], "亏损场景应判定为亏损"
        assert loss["roi"] < 0, "亏损场景 ROI 应 < 0"