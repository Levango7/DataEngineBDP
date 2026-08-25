"""行业模板库 - 注册所有内置行业模板."""

from __future__ import annotations

from industry_templates.models import Template
from industry_templates.templates.agri_crop import build_template as _build_agri
from industry_templates.templates.edu_student import build_template as _build_edu
from industry_templates.templates.fin_risk_scorecard import build_template as _build_fin
from industry_templates.templates.med_emr import build_template as _build_med
from industry_templates.templates.mfg_quality_inspection import build_template as _build_mfg
from industry_templates.templates.retail_user_profile import build_template as _build_retail
from industry_templates.templates.trans_traffic import build_template as _build_trans


def get_builtin_templates() -> list[Template]:
    """返回所有内置行业模板.

    Returns:
        7 个行业模板：金融风控 / 零售画像 / 制造质检 / 医疗质控
        / 交通流量 / 教育学情 / 农牧产量
    """
    return [
        _build_fin(),
        _build_retail(),
        _build_mfg(),
        _build_med(),
        _build_trans(),
        _build_edu(),
        _build_agri(),
    ]


__all__ = ["get_builtin_templates"]
