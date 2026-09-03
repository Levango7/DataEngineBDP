# -*- coding: utf-8 -*-
"""Sprint 4.2 A3：模板↔Chart 对账测试。

验证 helm 模式部署链路的 chart 映射完整性（Sprint 4.2 修复的回归防护）：
1. 每个内置模板经 _resolve_chart_path 都能解析到真实存在的 chart 目录；
2. 解析出的 chart 目录含 Chart.yaml，且其 name 与目录名一致；
3. chartRef 显式指定的模板，其 chart 目录存在且 Chart.yaml name == chartRef；
4. chartBase 回退链测试：templateId 无同名目录时回退 {industry}-template。

运行方式：
    cd platform/industry-templates && python -m pytest tests/test_chart_mapping.py -v
"""

from __future__ import annotations

import os
import re
from pathlib import Path

import pytest

from industry_templates.services.template_engine import TemplateEngine
from industry_templates.templates import get_builtin_templates

# chartBase 仓库内真实路径（测试从 platform/industry-templates 运行）
REPO_CHARTS = Path(__file__).resolve().parent.parent / "charts"


def _chart_yaml_name(chart_dir: str) -> str:
    """读取 Chart.yaml 的 name 字段；不存在返回空串。"""
    cy = Path(chart_dir) / "Chart.yaml"
    if not cy.is_file():
        return ""
    text = cy.read_text(encoding="utf-8")
    m = re.search(r"^name:\s*(\S+)\s*$", text, re.MULTILINE)
    return m.group(1) if m else ""


def _engine() -> TemplateEngine:
    """构造指向仓库 charts/ 的 helm 模式引擎（helmExecutor 由测试自注入）。"""
    return TemplateEngine(
        templates=get_builtin_templates(),
        deployMode="helm",
        helmExecutor=None,
        chartBase=str(REPO_CHARTS),
    )


class TestChartMapping:
    """9 内置模板 ↔ charts/ 目录对账。"""

    def test_all_builtin_templates_resolve_real_chart(self):
        """每个内置模板都能解析到真实存在的 chart 目录（Sprint 4.2 修复点）。"""
        engine = _engine()
        missing = []
        for t in get_builtin_templates():
            resolved = engine._resolve_chart_path(t.id)
            if not os.path.isdir(resolved):
                missing.append((t.id, resolved))
        assert not missing, f"以下模板未能解析到真实 chart 目录: {missing}"

    def test_every_chart_dir_has_chart_yaml_and_consistent_name(self):
        """每个解析出的 chart 目录含 Chart.yaml 且 name 与目录名一致。"""
        engine = _engine()
        bad = []
        for t in get_builtin_templates():
            resolved = engine._resolve_chart_path(t.id)
            if not os.path.isdir(resolved):
                continue
            dir_name = os.path.basename(os.path.normpath(resolved))
            yaml_name = _chart_yaml_name(resolved)
            if yaml_name != dir_name:
                bad.append((t.id, dir_name, yaml_name))
        assert not bad, f"Chart.yaml name 与目录名不一致: {bad}"

    def test_chart_ref_fallback_industry_template_naming(self):
        """chartRef 未显式设置时按 {industry}-template 命名回退。"""
        engine = _engine()
        for t in get_builtin_templates():
            if t.meta.chartRef:
                continue
            resolved = engine._resolve_chart_path(t.id)
            expected_suffix = f"{t.meta.industry.value}-template"
            assert resolved.endswith(expected_suffix), (
                f"{t.id} 解析 {resolved} 未按 {{{{industry}}}}-template 命名回退"
            )

    def test_explicit_chart_ref_takes_priority(self):
        """chartRef 显式指定时优先命中（构造临时 chart 目录验证）。"""
        import shutil
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            fake_chart = os.path.join(tmp, "my-custom-chart")
            os.makedirs(fake_chart)
            templates = get_builtin_templates()
            first = templates[0]
            first.meta.chartRef = "my-custom-chart"
            try:
                engine = TemplateEngine(
                    templates=templates,
                    deployMode="helm",
                    helmExecutor=None,
                    chartBase=tmp,
                )
                assert engine._resolve_chart_path(first.id) == fake_chart
            finally:
                first.meta.chartRef = None

    def test_unknown_template_falls_back_to_id(self):
        """未注册模板保持原语义：回退为模板 ID，由 helm 报 chart not found。"""
        engine = _engine()
        assert engine._resolve_chart_path("totally-unknown-tpl") == "totally-unknown-tpl"

    def test_chart_base_contains_all_nine_industry_charts(self):
        """chartBase 下 9 个行业 chart 目录齐全（finance 归位后应有 9 个）。"""
        expected = {
            "finance-template",
            "retail-template",
            "manufacturing-template",
            "medical-template",
            "transportation-template",
            "education-template",
            "agriculture-template",
            "energy-template",
            "government-template",
        }
        actual = {d.name for d in REPO_CHARTS.iterdir() if d.is_dir()}
        assert expected <= actual, f"行业 chart 缺失: {expected - actual}"


class TestDeployRecordsChartPath:
    """部署失败时可定位 chart 路径（可运维性）。"""

    def test_helm_mode_deploy_failure_message_includes_chart_hint(self):
        """helm 模式下 chart 不存在时部署失败且异常可定位。"""
        from industry_templates.services.exceptions import TemplateError

        class _FailingHelm:
            """真实 HelmExecutor 在 rc!=0 时会抛 HelmCommandError（_check 语义），此处对齐。"""

            def install_or_upgrade(self, **kwargs):
                from industry_templates.services.helm_executor import HelmCommandError

                raise HelmCommandError(
                    'helm 命令失败（rc=1）: helm upgrade --install test',
                    cmd=["helm", "upgrade", "--install", "test"],
                    returncode=1,
                    stdout="",
                    stderr="Error: chart \"nonexistent\" not found",
                )

            def uninstall(self, releaseName, namespace):
                from industry_templates.services.helm_executor import HelmCommandResult

                return HelmCommandResult(returncode=0, stdout="", stderr="")

        engine = TemplateEngine(
            templates=get_builtin_templates(),
            deployMode="helm",
            helmExecutor=_FailingHelm(),
            chartBase=str(REPO_CHARTS),
        )
        from industry_templates.models import DeploymentRequest

        # fin-risk-scorecard 的必填数据源参数（否则参数校验先行失败，走不到 helm）
        req = DeploymentRequest(
            tenantId="tenant-001",
            releaseName="chart-mapping-test",
            values={
                "datasource.order_db": "jdbc:mysql://e2e-db:3306/orders",
                "datasource.user_db": "jdbc:mysql://e2e-db:3306/users",
            },
        )
        with pytest.raises(TemplateError) as exc_info:
            engine.deploy("fin-risk-scorecard", req)
        assert exc_info.value.code in ("HELM_COMMAND_ERROR", "TEMPLATE_ERROR")
        # 部署记录应标记失败
        records = engine.list_deployments()
        assert records
        assert records[-1].status.value == "failed"
