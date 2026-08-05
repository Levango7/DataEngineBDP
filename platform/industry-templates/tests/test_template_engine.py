"""TemplateEngine 单元测试：模板解析 + 参数注入 + 一键部署."""
from __future__ import annotations

import pytest

from industry_templates.models import (
    DeploymentRequest,
    Industry,
    TemplateStatus,
)
from industry_templates.services.exceptions import (
    ParameterValidationError,
    TemplateNotFoundError,
    TemplateNotDeployableError,
)
from industry_templates.services.template_engine import TemplateEngine
from industry_templates.templates import get_builtin_templates


# ---------- 模板解析 ----------

class TestTemplateParsing:
    """模板解析测试."""

    def test_builtin_templates_count(self):
        """内置模板数量 = 3."""
        templates = get_builtin_templates()
        assert len(templates) == 3

    def test_builtin_template_ids(self):
        """内置模板 ID 正确."""
        templates = get_builtin_templates()
        ids = {t.id for t in templates}
        assert ids == {
            "fin-risk-scorecard",
            "retail-user-profile",
            "mfg-quality-inspection",
        }

    def test_builtin_template_industries(self):
        """内置模板覆盖 3 个行业."""
        templates = get_builtin_templates()
        industries = {t.meta.industry for t in templates}
        assert industries == {
            Industry.FINANCE,
            Industry.RETAIL,
            Industry.MANUFACTURING,
        }

    def test_all_templates_in_catalog_status(self):
        """所有内置模板状态为 catalog（可部署）."""
        for t in get_builtin_templates():
            assert t.meta.status == TemplateStatus.CATALOG

    def test_engine_list_templates(self, engine: TemplateEngine):
        """引擎列出所有模板."""
        templates = engine.list_templates()
        assert len(templates) == 3

    def test_engine_list_filter_by_industry(self, engine: TemplateEngine):
        """按行业过滤."""
        finance = engine.list_templates(industry="finance")
        assert len(finance) == 1
        assert finance[0].id == "fin-risk-scorecard"

        retail = engine.list_templates(industry="retail")
        assert len(retail) == 1
        assert retail[0].id == "retail-user-profile"

        mfg = engine.list_templates(industry="manufacturing")
        assert len(mfg) == 1
        assert mfg[0].id == "mfg-quality-inspection"

    def test_engine_list_filter_by_keyword(self, engine: TemplateEngine):
        """按关键字过滤."""
        results = engine.list_templates(keyword="风控")
        assert len(results) == 1
        assert results[0].id == "fin-risk-scorecard"

    def test_engine_get_template(self, engine: TemplateEngine):
        """获取模板详情."""
        t = engine.get_template("fin-risk-scorecard")
        assert t.meta.name == "风控评分卡"
        assert t.meta.industry == Industry.FINANCE

    def test_engine_get_template_not_found(self, engine: TemplateEngine):
        """模板不存在抛 TemplateNotFoundError."""
        with pytest.raises(TemplateNotFoundError):
            engine.get_template("nonexistent")


# ---------- 参数注入（渲染） ----------

class TestParameterRendering:
    """参数注入/渲染测试."""

    def test_render_string_placeholder(self):
        """渲染字符串占位符."""
        result = TemplateEngine.render_value(
            "jdbc://${host}:${port}/db", {"host": "1.2.3.4", "port": 3306}
        )
        assert result == "jdbc://1.2.3.4:3306/db"

    def test_render_placeholder_with_default(self):
        """占位符带默认值."""
        result = TemplateEngine.render_value("${missing:default}", {})
        assert result == "default"

    def test_render_placeholder_not_provided(self):
        """占位符未提供且无默认值，保留原占位符."""
        result = TemplateEngine.render_value("${unknown}", {})
        assert result == "${unknown}"

    def test_render_dict(self):
        """渲染 dict."""
        result = TemplateEngine.render_value(
            {"host": "${h}", "port": 8080}, {"h": "localhost"}
        )
        assert result == {"host": "localhost", "port": 8080}

    def test_render_list(self):
        """渲染 list."""
        result = TemplateEngine.render_value(
            ["${a}", "${b}", "literal"], {"a": "X", "b": "Y"}
        )
        assert result == ["X", "Y", "literal"]

    def test_render_nested(self):
        """渲染嵌套结构."""
        result = TemplateEngine.render_value(
            {"config": {"url": "${u}", "opts": ["${o1}", "fixed"]}},
            {"u": "http://api", "o1": "opt1"},
        )
        assert result == {
            "config": {"url": "http://api", "opts": ["opt1", "fixed"]}
        }

    def test_render_non_string_value(self):
        """非字符串值原样返回."""
        assert TemplateEngine.render_value(42, {}) == 42
        assert TemplateEngine.render_value(True, {}) is True
        assert TemplateEngine.render_value(None, {}) is None

    def test_render_template(self, engine: TemplateEngine):
        """渲染整个模板."""
        template = engine.get_template("fin-risk-scorecard")
        rendered = engine.render_template(
            template,
            {
                "scoring.threshold_high": 0.9,
                "scoring.threshold_medium": 0.7,
            },
        )
        # 渲染后仍是 dict 结构
        assert isinstance(rendered, dict)
        assert rendered["meta"]["id"] == "fin-risk-scorecard"


# ---------- 参数校验 ----------

class TestParameterValidation:
    """参数校验测试."""

    def test_validate_missing_required(self, engine: TemplateEngine):
        """缺少必填参数抛 ParameterValidationError."""
        template = engine.get_template("fin-risk-scorecard")
        with pytest.raises(ParameterValidationError) as exc_info:
            TemplateEngine.validate_parameters(template, {})
        assert exc_info.value.missing  # missing 列表非空

    def test_validate_with_defaults_merged(self, engine: TemplateEngine):
        """合并默认值后校验通过（仅必填无默认的需提供）."""
        template = engine.get_template("fin-risk-scorecard")
        merged = TemplateEngine.merge_default_values(
            template,
            {
                "datasource.order_db": "jdbc://...",
                "datasource.user_db": "jdbc://...",
            },
        )
        # 合并后包含默认值
        assert merged["scoring.threshold_high"] == 0.85
        # 校验通过（不抛异常）
        TemplateEngine.validate_parameters(template, merged)

    def test_validate_wrong_type_integer(self, engine: TemplateEngine):
        """整数类型校验."""
        template = engine.get_template("fin-risk-scorecard")
        values = {
            "datasource.order_db": "jdbc://...",
            "datasource.user_db": "jdbc://...",
            "model.xgboost.max_depth": "not-an-int",  # 应为 int
        }
        with pytest.raises(ParameterValidationError):
            TemplateEngine.validate_parameters(template, values)

    def test_validate_enum_invalid(self, engine: TemplateEngine):
        """枚举校验."""
        template = engine.get_template("retail-user-profile")
        values = {
            "datasource.trade_db": "jdbc://...",
            "datasource.user_db": "jdbc://...",
            "datasource.behavior_log": "/path",
            "recommend.algorithm": "invalid_algo",  # 不在 enum 内
        }
        with pytest.raises(ParameterValidationError):
            TemplateEngine.validate_parameters(template, values)

    def test_validate_enum_valid(self, engine: TemplateEngine):
        """枚举合法值通过."""
        template = engine.get_template("retail-user-profile")
        values = {
            "datasource.trade_db": "jdbc://...",
            "datasource.user_db": "jdbc://...",
            "datasource.behavior_log": "/path",
            "recommend.algorithm": "als",
        }
        TemplateEngine.validate_parameters(template, values)


# ---------- 一键部署 ----------

class TestDeployment:
    """一键部署测试."""

    def _make_request(self, template_id: str) -> DeploymentRequest:
        """构造各模板的有效部署请求."""
        if template_id == "fin-risk-scorecard":
            values = {
                "datasource.order_db": "jdbc:mysql://order:3306/order",
                "datasource.user_db": "jdbc:mysql://user:3306/user",
            }
        elif template_id == "retail-user-profile":
            values = {
                "datasource.trade_db": "jdbc:mysql://trade:3306/trade",
                "datasource.user_db": "jdbc:mysql://user:3306/user",
                "datasource.behavior_log": "/data/behavior",
            }
        elif template_id == "mfg-quality-inspection":
            values = {
                "datasource.image_stream": "kafka://image-topic",
                "datasource.mes_db": "jdbc:mysql://mes:3306/mes",
                "datasource.iotdb": "iotdb://localhost:6667",
            }
        else:
            values = {}
        return DeploymentRequest(
            tenantId="tenant-001",
            releaseName=f"test-{template_id}",
            values=values,
        )

    def test_deploy_fin_risk(self, engine: TemplateEngine):
        """部署金融风控模板."""
        req = self._make_request("fin-risk-scorecard")
        record = engine.deploy("fin-risk-scorecard", req)
        assert record.templateId == "fin-risk-scorecard"
        assert record.tenantId == "tenant-001"
        assert record.status.value == "running"
        assert record.jobRunId is not None
        assert record.dashboardSnapshotUrl is not None

    def test_deploy_retail_profile(self, engine: TemplateEngine):
        """部署零售画像模板."""
        req = self._make_request("retail-user-profile")
        record = engine.deploy("retail-user-profile", req)
        assert record.templateId == "retail-user-profile"
        assert record.status.value == "running"

    def test_deploy_mfg_quality(self, engine: TemplateEngine):
        """部署制造质检模板."""
        req = self._make_request("mfg-quality-inspection")
        record = engine.deploy("mfg-quality-inspection", req)
        assert record.templateId == "mfg-quality-inspection"
        assert record.status.value == "running"

    def test_deploy_increases_install_count(self, engine: TemplateEngine):
        """部署后安装计数 +1."""
        template = engine.get_template("fin-risk-scorecard")
        before = template.meta.installCount
        req = self._make_request("fin-risk-scorecard")
        engine.deploy("fin-risk-scorecard", req)
        assert template.meta.installCount == before + 1

    def test_deploy_not_found(self, engine: TemplateEngine):
        """部署不存在的模板."""
        req = DeploymentRequest(
            tenantId="t1", releaseName="r1", values={}
        )
        with pytest.raises(TemplateNotFoundError):
            engine.deploy("nonexistent", req)

    def test_deploy_missing_required_params(self, engine: TemplateEngine):
        """缺少必填参数部署失败."""
        req = DeploymentRequest(
            tenantId="t1", releaseName="r1", values={}
        )
        with pytest.raises(ParameterValidationError):
            engine.deploy("fin-risk-scorecard", req)

    def test_get_deployment(self, engine: TemplateEngine):
        """获取部署记录."""
        req = self._make_request("fin-risk-scorecard")
        record = engine.deploy("fin-risk-scorecard", req)
        fetched = engine.get_deployment(record.deploymentId)
        assert fetched.deploymentId == record.deploymentId

    def test_list_deployments_by_tenant(self, engine: TemplateEngine):
        """按租户列出部署记录."""
        req = self._make_request("fin-risk-scorecard")
        engine.deploy("fin-risk-scorecard", req)
        records = engine.list_deployments(tenantId="tenant-001")
        assert len(records) >= 1
        for r in records:
            assert r.tenantId == "tenant-001"

    def test_undeploy(self, engine: TemplateEngine):
        """卸载部署."""
        req = self._make_request("fin-risk-scorecard")
        record = engine.deploy("fin-risk-scorecard", req)
        stopped = engine.undeploy(record.deploymentId)
        assert stopped.status.value == "stopped"


# ---------- 预览 ----------

class TestPreview:
    """模板预览测试."""

    def test_preview_fin(self, engine: TemplateEngine):
        """预览金融风控模板."""
        preview = engine.preview_template("fin-risk-scorecard")
        assert preview.templateId == "fin-risk-scorecard"
        assert preview.templateName == "风控评分卡"
        assert preview.industry == Industry.FINANCE
        assert preview.stats["dataFlowNodes"] > 0
        assert preview.stats["computeSteps"] > 0
        assert preview.stats["visualizationPanels"] > 0
        assert len(preview.architecture["nodes"]) > 0

    def test_preview_not_found(self, engine: TemplateEngine):
        """预览不存在的模板."""
        with pytest.raises(TemplateNotFoundError):
            engine.preview_template("nonexistent")


# ---------- 分类 ----------

class TestCategories:
    """模板分类测试."""

    def test_list_categories(self, engine: TemplateEngine):
        """列出分类."""
        cats = engine.list_categories()
        assert len(cats) == 3
        industries = {c["industry"] for c in cats}
        assert industries == {"finance", "retail", "manufacturing"}
        for c in cats:
            assert c["count"] == 1
            assert len(c["templates"]) == 1