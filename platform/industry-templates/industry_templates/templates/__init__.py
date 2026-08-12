"""行业模板库 - 注册所有内置行业模板."""

from __future__ import annotations

from industry_templates.models import Template
from industry_templates.templates.fin_risk_scorecard import build_template as _build_fin
from industry_templates.templates.mfg_quality_inspection import build_template as _build_mfg
from industry_templates.templates.retail_user_profile import build_template as _build_retail


def get_builtin_templates() -> list[Template]:
    """返回所有内置行业模板.

    Returns:
        3 个行业模板：金融风控 / 零售画像 / 制造质检
    """
    return [
        _build_fin(),
        _build_retail(),
        _build_mfg(),
    ]


__all__ = ["get_builtin_templates"]
