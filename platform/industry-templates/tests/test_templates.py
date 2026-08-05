"""3 个行业模板完整性测试.

校验每个模板的：
    - 元信息完整（id/name/industry/version/description 非空）
    - 参数定义完整（必填参数有 description）
    - 数据流完整（节点数 >= 4，覆盖 source/transform/sink）
    - 计算逻辑完整（步骤数 >= 3）
    - 可视化完整（面板数 >= 3）
    - README 非空
"""
from __future__ import annotations

import pytest

from industry_templates.models import (
    Industry,
    ParameterType,
    Template,
    TemplateStatus,
)
from industry_templates.templates import get_builtin_templates


@pytest.fixture(scope="module")
def templates() -> list[Template]:
    return get_builtin_templates()


# ---------- 通用完整性 ----------

class TestTemplateIntegrity:
    """所有模板通用完整性校验."""

    def test_all_templates_have_meta(self, templates):
        for t in templates:
            assert t.meta is not None
            assert t.meta.id
            assert t.meta.name
            assert t.meta.description

    def test_all_templates_in_catalog(self, templates):
        for t in templates:
            assert t.meta.status == TemplateStatus.CATALOG

    def test_all_templates_have_readme(self, templates):
        for t in templates:
            assert t.readme
            assert len(t.readme) > 100  # README 应有实质内容

    def test_all_templates_have_schema(self, templates):
        for t in templates:
            assert t.validationSchema
            assert "$schema" in t.validationSchema


# ---------- 金融风控 ----------

class TestFinRiskScorecard:
    """金融风控评分卡模板完整性."""

    @pytest.fixture(scope="class")
    def tpl(self, templates) -> Template:
        return next(t for t in templates if t.id == "fin-risk-scorecard")

    def test_meta(self, tpl):
        assert tpl.meta.industry == Industry.FINANCE
        assert tpl.meta.version == "0.1.0"
        assert "风控" in tpl.meta.name or "评分" in tpl.meta.name

    def test_parameters(self, tpl):
        """参数定义完整."""
        # 至少包含数据源参数
        ds_params = [
            p for p in tpl.parameters if p.type == ParameterType.DATASOURCE
        ]
        assert len(ds_params) >= 2  # order_db + user_db
        # 至少包含阈值参数
        threshold_params = [p for p in tpl.parameters if "threshold" in p.name]
        assert len(threshold_params) >= 2  # high + medium
        # 至少包含模型参数
        model_params = [p for p in tpl.parameters if p.name.startswith("model.")]
        assert len(model_params) >= 1
        # 所有必填参数有 description
        for p in tpl.parameters:
            if p.required:
                assert p.description, f"必填参数 {p.name} 缺少 description"

    def test_data_flow(self, tpl):
        """数据流：覆盖 source/transform/sink 三种节点类型."""
        nodes = tpl.dataFlow.nodes
        assert len(nodes) >= 4  # 至少 4 个节点
        types = {n.nodeType for n in nodes}
        assert "source" in types
        assert "transform" in types
        assert "sink" in types
        # 覆盖 ODS/DWD/DWS/ADS 四层
        layers = {n.layer for n in nodes if n.layer}
        assert "ods" in layers
        assert "dwd" in layers
        assert "dws" in layers
        assert "ads" in layers

    def test_compute_logic(self, tpl):
        """计算逻辑：至少 3 步，覆盖 sql/model/rule."""
        steps = tpl.computeLogic.steps
        assert len(steps) >= 3
        step_types = {s.stepType for s in steps}
        assert "sql" in step_types
        assert "model" in step_types

    def test_visualization(self, tpl):
        """可视化：至少 3 个面板."""
        panels = tpl.visualization.panels
        assert len(panels) >= 3
        for p in panels:
            assert p.title
            assert p.chartType

    def test_flow_pipeline(self, tpl):
        """数据流形成 pipeline：采集 → 特征 → 评分 → 分级."""
        names = [n.name for n in tpl.dataFlow.nodes]
        # 至少包含这 4 个关键环节
        assert any("采集" in n or "数据" in n for n in names)
        assert any("特征" in n for n in names)
        assert any("评分" in n or "模型" in n for n in names)
        assert any("风险" in n or "等级" in n for n in names)


# ---------- 零售画像 ----------

class TestRetailUserProfile:
    """零售用户画像模板完整性."""

    @pytest.fixture(scope="class")
    def tpl(self, templates) -> Template:
        return next(t for t in templates if t.id == "retail-user-profile")

    def test_meta(self, tpl):
        assert tpl.meta.industry == Industry.RETAIL
        assert "画像" in tpl.meta.name or "标签" in tpl.meta.name

    def test_parameters(self, tpl):
        ds_params = [p for p in tpl.parameters if p.type == ParameterType.DATASOURCE]
        assert len(ds_params) >= 2  # trade + user
        # 推荐算法参数为 enum
        algo = next(
            p for p in tpl.parameters if p.name == "recommend.algorithm"
        )
        assert algo.type == ParameterType.ENUM
        assert algo.enumOptions is not None
        assert "item_cf" in algo.enumOptions

    def test_data_flow(self, tpl):
        nodes = tpl.dataFlow.nodes
        assert len(nodes) >= 5  # 至少 5 个节点（更长的链路）
        types = {n.nodeType for n in nodes}
        assert "source" in types
        assert "transform" in types
        assert "sink" in types

    def test_compute_logic(self, tpl):
        steps = tpl.computeLogic.steps
        assert len(steps) >= 3

    def test_visualization(self, tpl):
        panels = tpl.visualization.panels
        assert len(panels) >= 3

    def test_flow_pipeline(self, tpl):
        """数据流：交易 → 标签 → 圈选 → 推荐."""
        names = [n.name for n in tpl.dataFlow.nodes]
        assert any("交易" in n or "采集" in n for n in names)
        assert any("标签" in n or "RFM" in n for n in names)
        assert any("圈选" in n for n in names)
        assert any("推荐" in n for n in names)


# ---------- 制造质检 ----------

class TestMfgQualityInspection:
    """制造产线质检模板完整性."""

    @pytest.fixture(scope="class")
    def tpl(self, templates) -> Template:
        return next(t for t in templates if t.id == "mfg-quality-inspection")

    def test_meta(self, tpl):
        assert tpl.meta.industry == Industry.MANUFACTURING
        assert "质检" in tpl.meta.name or "质量" in tpl.meta.name

    def test_parameters(self, tpl):
        ds_params = [p for p in tpl.parameters if p.type == ParameterType.DATASOURCE]
        assert len(ds_params) >= 2  # image + mes
        # 缺陷检测模型为 enum
        model = next(
            p for p in tpl.parameters if p.name == "defect.model_type"
        )
        assert model.type == ParameterType.ENUM
        assert "yolov8" in (model.enumOptions or [])

    def test_data_flow(self, tpl):
        nodes = tpl.dataFlow.nodes
        assert len(nodes) >= 4
        types = {n.nodeType for n in nodes}
        assert "source" in types
        assert "transform" in types
        assert "sink" in types

    def test_compute_logic(self, tpl):
        steps = tpl.computeLogic.steps
        assert len(steps) >= 3
        # 包含模型步骤
        step_types = {s.stepType for s in steps}
        assert "model" in step_types

    def test_visualization(self, tpl):
        panels = tpl.visualization.panels
        assert len(panels) >= 3

    def test_flow_pipeline(self, tpl):
        """数据流：图像 → 缺陷检测 → 质量分级 → 报告."""
        names = [n.name for n in tpl.dataFlow.nodes]
        assert any("图像" in n or "采集" in n for n in names)
        assert any("缺陷" in n for n in names)
        assert any("质量" in n or "分级" in n for n in names)
        assert any("报告" in n for n in names)


# ---------- 可部署性 ----------

class TestDeployability:
    """3 个模板均可解析、可部署."""

    def test_all_deployable(self, templates):
        from industry_templates.services.template_engine import TemplateEngine

        engine = TemplateEngine(templates)
        for t in templates:
            # 列表中存在
            assert engine.get_template(t.id) is not None
            # 预览成功
            preview = engine.preview_template(t.id)
            assert preview.templateId == t.id