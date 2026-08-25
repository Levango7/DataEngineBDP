"""新增行业模板单测（医疗/交通/教育/农牧）."""

from __future__ import annotations

import pytest

from industry_templates.models import Industry
from industry_templates.templates import get_builtin_templates


@pytest.mark.parametrize(
    "template_id, industry, node_count",
    [
        ("med-emr-quality", Industry.MEDICAL, 6),
        ("trans-traffic-flow", Industry.TRANSPORTATION, 5),
        ("edu-student-profile", Industry.EDUCATION, 5),
        ("agri-crop-yield", Industry.AGRICULTURE, 6),
    ],
)
def test_new_template_metadata(template_id, industry, node_count):
    """新模板元数据与行业归属."""
    templates = {t.meta.id: t for t in get_builtin_templates()}
    t = templates.get(template_id)
    assert t is not None, f"模板 {template_id} 未注册"
    assert t.meta.industry == industry
    assert t.meta.status.value == "catalog"  # 已上架
    assert t.meta.icon  # 有图标
    assert len(t.meta.tags) >= 3  # 有标签


@pytest.mark.parametrize(
    "template_id, min_params, min_steps",
    [
        ("med-emr-quality", 5, 3),
        ("trans-traffic-flow", 4, 3),
        ("edu-student-profile", 4, 3),
        ("agri-crop-yield", 5, 3),
    ],
)
def test_new_template_structure(template_id, min_params, min_steps):
    """新模板参数/数据流/计算逻辑完整性."""
    templates = {t.meta.id: t for t in get_builtin_templates()}
    t = templates[template_id]
    assert len(t.parameters) >= min_params
    assert len(t.dataFlow.nodes) >= 5  # ods→dwd→dws→ads 分层
    assert len(t.computeLogic.steps) >= min_steps
    assert len(t.visualization.panels) >= 3  # 3 个面板
    assert t.readme  # 有使用文档
    assert t.validationSchema  # 有校验 schema


def test_get_builtin_templates_returns_seven():
    """内置模板总数 = 7（4 旧 + 3 新行业 + 原 3）。"""
    templates = get_builtin_templates()
    assert len(templates) == 7
    ids = {t.meta.id for t in templates}
    assert {"med-emr-quality", "trans-traffic-flow", "edu-student-profile", "agri-crop-yield"} <= ids
