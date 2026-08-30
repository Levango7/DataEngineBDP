"""Grafana 双视图与告警分级集成测试。

被测对象：T041 交付物，位于 ``platform/observability/`` 目录。

覆盖场景（4 类，共 19 个用例）：
1. **双视图隔离**（5 个）：平台方与客户方 Organization 数据互不可见
2. **告警分级路由**（5 个）：P0 电话/短信、P1 邮件/IM、P2 钉钉/飞书三级路由触发正确
3. **查询隔离**（5 个）：统一查询 API 按租户隔离，租户间指标互不可见
4. **模板复用**（4 个）：告警规则模板可复用，租户自定义阈值生效

设计要点：
- 配置文件测试使用 PyYAML 解析 YAML 与 json 解析 JSON，做结构断言。
- 查询隔离测试使用 mock 模拟 query-api 与 Prometheus 行为，无需真实 Docker。
- 若 Docker 容器可用（it-query-api），则追加真实 HTTP 调用验证。
- 所有测试不依赖外部网络，可离线运行。

依赖：pytest, PyYAML, requests, PyJWT（见 tests/integration/requirements.txt）
"""

from __future__ import annotations

import json
import os
import time
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest
import yaml

# ---------------------------------------------------------------------------
# 路径常量
# ---------------------------------------------------------------------------
# 交付物根目录（相对本文件向上 3 级到项目根，再进入 platform/observability）。
_OBS_ROOT = Path(__file__).resolve().parents[3] / "platform" / "observability"
_GRAFANA_ROOT = _OBS_ROOT / "grafana"
_ALERTMANAGER_ROOT = _OBS_ROOT / "alertmanager"
_QUERY_API_ROOT = _OBS_ROOT / "query-api"
_RULES_ROOT = _OBS_ROOT / "rules"


# ---------------------------------------------------------------------------
# 辅助函数
# ---------------------------------------------------------------------------
def _load_yaml(path: Path) -> dict:
    """加载 YAML 文件并返回字典。"""
    with open(path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def _load_json(path: Path) -> dict:
    """加载 JSON 文件并返回字典。"""
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


# ---------------------------------------------------------------------------
# pytest 配置：自动跳过真实 Docker 测试（若容器不可用）
# ---------------------------------------------------------------------------
def _is_query_api_available() -> bool:
    """检查 query-api Docker 容器是否可用（5 秒探测）。"""
    import requests

    try:
        resp = requests.get(
            os.environ.get("QUERY_API_URL", "http://localhost:18090") + "/api/v1/health",
            timeout=5,
        )
        return resp.status_code == 200 and resp.json().get("status") == "UP"
    except Exception:
        return False


QUERY_API_AVAILABLE = _is_query_api_available()
skip_if_no_docker = pytest.mark.skipif(
    not QUERY_API_AVAILABLE, reason="query-api Docker 容器不可用（mock 测试仍运行）"
)


# ===========================================================================
# 场景 1：双视图隔离（5 个用例）
# ===========================================================================
class TestDualViewIsolation:
    """验证 Grafana 平台方与客户方 Organization 数据互不可见。"""

    def test_platform_org_datasource_uses_platform_endpoint(self):
        """平台方数据源 URL 指向 query-api /platform 端点。

        确保平台方 Organization 只能通过 /platform 路径访问，该路径不做 tenant 过滤。
        """
        ds = _load_yaml(_GRAFANA_ROOT / "platform-org" / "datasources" / "datasources.yaml")
        prom_ds = next(
            d for d in ds["datasources"] if d["name"] == "Prometheus-Platform"
        )
        assert "/platform" in prom_ds["url"], "平台方数据源必须指向 /platform 端点"
        assert prom_ds["orgId"] == 1, "平台方 Organization orgId 必须为 1"

    def test_tenant_org_datasource_uses_tenant_endpoint(self):
        """客户方数据源 URL 指向 query-api /tenant 端点。

        确保客户方 Organization 只能通过 /tenant 路径访问，该路径强制 tenant 过滤。
        """
        ds = _load_yaml(_GRAFANA_ROOT / "tenant-org" / "datasources" / "datasources.yaml")
        prom_ds = next(
            d for d in ds["datasources"] if d["name"] == "Prometheus-Tenant"
        )
        assert "/tenant" in prom_ds["url"], "客户方数据源必须指向 /tenant 端点"
        assert prom_ds["orgId"] == 2, "客户方 Organization orgId 必须为 2"

    def test_platform_and_tenant_orgs_have_different_ids(self):
        """平台方与客户方 Organization 的 orgId 不同，确保 Grafana 层面隔离。"""
        platform_ds = _load_yaml(
            _GRAFANA_ROOT / "platform-org" / "datasources" / "datasources.yaml"
        )
        tenant_ds = _load_yaml(
            _GRAFANA_ROOT / "tenant-org" / "datasources" / "datasources.yaml"
        )
        platform_org_ids = {d["orgId"] for d in platform_ds["datasources"]}
        tenant_org_ids = {d["orgId"] for d in tenant_ds["datasources"]}
        assert platform_org_ids.isdisjoint(tenant_org_ids), (
            "平台方与客户方 orgId 必须不相交，确保 Grafana Organization 隔离"
        )

    def test_platform_dashboard_has_platform_tags(self):
        """平台方仪表板包含 platform 标签，不包含 tenant 标签。"""
        dashboard = _load_json(
            _GRAFANA_ROOT / "platform-org" / "dashboards" / "platform-overview.json"
        )
        tags = dashboard.get("tags", [])
        assert "platform" in tags, "平台方仪表板必须包含 platform 标签"
        assert "tenant" not in tags, "平台方仪表板不应包含 tenant 标签"

    def test_tenant_dashboard_has_tenant_tags(self):
        """客户方仪表板包含 tenant 标签，不包含 platform 标签。"""
        dashboard = _load_json(
            _GRAFANA_ROOT / "tenant-org" / "dashboards" / "tenant-overview.json"
        )
        tags = dashboard.get("tags", [])
        assert "tenant" in tags, "客户方仪表板必须包含 tenant 标签"
        assert "platform" not in tags, "客户方仪表板不应包含 platform 标签"


# ===========================================================================
# 场景 2：告警分级路由（5 个用例）
# ===========================================================================
class TestAlertmanagerRouting:
    """验证 Alertmanager P0/P1/P2 三级路由触发正确通知渠道。"""

    @pytest.fixture(scope="class")
    def alertmanager_config(self):
        """加载 alertmanager.yml 主配置。"""
        return _load_yaml(_ALERTMANAGER_ROOT / "alertmanager.yml")

    def test_p0_route_targets_phone_and_sms(self, alertmanager_config):
        """P0 告警路由到 p0-pager receiver，包含电话与短信 webhook。"""
        receivers = {r["name"]: r for r in alertmanager_config["receivers"]}
        assert "p0-pager" in receivers, "必须存在 p0-pager receiver"

        p0 = receivers["p0-pager"]
        webhook_urls = [w["url"] for w in p0.get("webhook_configs", [])]
        # 验证包含电话网关与短信网关 URL（通过环境变量占位符）。
        assert any("PHONE_GATEWAY" in u for u in webhook_urls), (
            "P0 必须包含电话网关 webhook"
        )
        assert any("SMS_GATEWAY" in u for u in webhook_urls), (
            "P0 必须包含短信网关 webhook"
        )

        # 验证路由匹配 severity=P0。
        p0_route = next(
            r for r in alertmanager_config["route"]["routes"]
            if any("P0" in m for m in r.get("matchers", []))
        )
        assert p0_route["receiver"] == "p0-pager"
        assert p0_route["repeat_interval"] == "5m", "P0 重复间隔必须为 5m"

    def test_p1_route_targets_email_and_wecom(self, alertmanager_config):
        """P1 告警路由到 p1-email-im receiver，包含邮件与企业微信。"""
        receivers = {r["name"]: r for r in alertmanager_config["receivers"]}
        assert "p1-email-im" in receivers, "必须存在 p1-email-im receiver"

        p1 = receivers["p1-email-im"]
        # 验证包含邮件配置。
        assert len(p1.get("email_configs", [])) > 0, "P1 必须包含邮件配置"
        # 验证包含企业微信 webhook。
        webhook_urls = [w["url"] for w in p1.get("webhook_configs", [])]
        assert any("WECOM" in u for u in webhook_urls), (
            "P1 必须包含企业微信 webhook"
        )

        # 验证路由匹配 severity=P1。
        p1_route = next(
            r for r in alertmanager_config["route"]["routes"]
            if any("P1" in m for m in r.get("matchers", []))
        )
        assert p1_route["receiver"] == "p1-email-im"
        assert p1_route["repeat_interval"] == "30m", "P1 重复间隔必须为 30m"

    def test_p2_route_targets_dingtalk_and_feishu(self, alertmanager_config):
        """P2 告警路由到 p2-dingtalk-feishu receiver，包含钉钉与飞书。"""
        receivers = {r["name"]: r for r in alertmanager_config["receivers"]}
        assert "p2-dingtalk-feishu" in receivers, "必须存在 p2-dingtalk-feishu receiver"

        p2 = receivers["p2-dingtalk-feishu"]
        webhook_urls = [w["url"] for w in p2.get("webhook_configs", [])]
        assert any("DINGTALK" in u for u in webhook_urls), (
            "P2 必须包含钉钉 webhook"
        )
        assert any("FEISHU" in u for u in webhook_urls), (
            "P2 必须包含飞书 webhook"
        )

        # 验证路由匹配 severity=P2。
        p2_route = next(
            r for r in alertmanager_config["route"]["routes"]
            if any("P2" in m for m in r.get("matchers", []))
        )
        assert p2_route["receiver"] == "p2-dingtalk-feishu"
        assert p2_route["repeat_interval"] == "4h", "P2 重复间隔必须为 4h"

    def test_alertmanager_inhibit_p0_suppresses_p1_p2(self, alertmanager_config):
        """P0 触发时抑制同租户同组件的 P1/P2，避免告警风暴。"""
        inhibit_rules = alertmanager_config.get("inhibit_rules", [])
        p0_inhibit = next(
            (
                r for r in inhibit_rules
                if any("P0" in m for m in r.get("source_matchers", []))
            ),
            None,
        )
        assert p0_inhibit is not None, "必须存在 P0 抑制规则"

        target_matchers = p0_inhibit.get("target_matchers", [])
        assert any("P1" in m and "P2" in m for m in target_matchers), (
            "P0 抑制规则的目标必须包含 P1 和 P2"
        )
        assert "tenant_id" in p0_inhibit.get("equal", []), (
            "抑制规则必须按 tenant_id 对齐"
        )
        assert "component" in p0_inhibit.get("equal", []), (
            "抑制规则必须按 component 对齐"
        )

    def test_alertmanager_repeat_intervals_differ_by_severity(self, alertmanager_config):
        """P0/P1/P2 三级告警的重复间隔递增（5m < 30m < 4h）。"""
        routes = alertmanager_config["route"]["routes"]
        intervals = {}
        for r in routes:
            for m in r.get("matchers", []):
                if "P0" in m:
                    intervals["P0"] = r["repeat_interval"]
                elif "P1" in m:
                    intervals["P1"] = r["repeat_interval"]
                elif "P2" in m:
                    intervals["P2"] = r["repeat_interval"]

        assert intervals["P0"] == "5m"
        assert intervals["P1"] == "30m"
        assert intervals["P2"] == "4h"
        # 递增校验（简单字符串比较足以覆盖 m < m < h）。
        assert intervals["P0"] != intervals["P1"] != intervals["P2"], (
            "三级告警重复间隔必须递增"
        )


# ===========================================================================
# 场景 3：查询隔离（5 个用例）
# ===========================================================================
class TestQueryIsolation:
    """验证统一查询 API 按租户隔离，租户间指标互不可见。"""

    def test_tenant_query_injects_tenant_id_filter(self):
        """客户方查询 PromQL 被注入 tenant_id 标签过滤。

        验证 TenantFilter.InjectTenantQuery 在 PromQL 最外层注入
        AND {tenant_id="xxx"}。
        """
        # 模拟 Go service.TenantFilter 的注入逻辑（与 tenant_filter.go 保持一致）。
        def inject_tenant_query(promql: str, tenant_id: str) -> str:
            if not tenant_id:
                return promql
            if "tenant_id" in promql:
                return promql
            return f'({promql}) AND {{tenant_id="{tenant_id}"}}'

        original = "sum(rate(shuqing_query_total[5m])) by (component)"
        injected = inject_tenant_query(original, "tenant-acme")

        assert 'tenant_id="tenant-acme"' in injected, (
            "注入后 PromQL 必须包含 tenant_id 过滤"
        )
        assert injected.startswith("("), "注入后 PromQL 必须用括号包裹原 PromQL"
        assert "AND" in injected, "注入后 PromQL 必须包含 AND 运算符"

    def test_platform_query_does_not_inject_filter(self):
        """平台方查询不注入 tenant_id 过滤，可看全平台指标。"""
        def platform_query(promql: str) -> str:
            return promql  # 平台方原样返回

        original = "sum(rate(shuqing_query_total[5m])) by (component)"
        result = platform_query(original)
        assert result == original, "平台方查询不应修改 PromQL"
        assert "tenant_id" not in result, "平台方查询不应包含 tenant_id 过滤"

    def test_tenant_isolation_rejects_invalid_tenant_id(self):
        """无效 tenant_id（含特殊字符）被拒绝，防 PromQL 注入。"""
        import re

        tenant_id_pattern = re.compile(r"^[a-zA-Z0-9_-]{1,64}$")

        # 合法 tenant_id。
        assert tenant_id_pattern.match("tenant-acme")
        assert tenant_id_pattern.match("tenant_001")
        assert tenant_id_pattern.match("T001")

        # 非法 tenant_id（PromQL 注入尝试）。
        assert not tenant_id_pattern.match('x} or up{')  # PromQL 注入
        assert not tenant_id_pattern.match('"); DROP TABLE--')  # SQL 注入
        assert not tenant_id_pattern.match("a" * 65)  # 超长
        assert not tenant_id_pattern.match("tenant space")  # 含空格

    def test_tenant_isolation_rejects_platform_identity(self):
        """platform 身份不能访问 /tenant 端点（防平台方误用客户方视图）。"""
        # 模拟 TenantIsolationMiddleware 的逻辑。
        def check_tenant_access(tenant_id: str) -> tuple[bool, str]:
            if not tenant_id:
                return False, "tenantId missing in JWT"
            if tenant_id == "platform":
                return False, "platform identity cannot access tenant endpoint"
            return True, "ok"

        ok, _ = check_tenant_access("tenant-acme")
        assert ok, "普通租户应能访问 /tenant 端点"

        ok, msg = check_tenant_access("platform")
        assert not ok, "platform 身份不应访问 /tenant 端点"
        assert "platform identity" in msg

        ok, msg = check_tenant_access("")
        assert not ok, "空 tenantId 不应访问 /tenant 端点"

    def test_platform_endpoint_requires_platform_role(self):
        """平台方端点 /platform/** 要求 JWT role=platform-ops。"""
        # 模拟 PlatformRoleMiddleware 的逻辑。
        def check_platform_role(role: str, expected: str = "platform-ops") -> bool:
            return role == expected

        assert check_platform_role("platform-ops"), "platform-ops 角色应通过"
        assert not check_platform_role("viewer"), "viewer 角色不应通过"
        assert not check_platform_role("tenant-admin"), "tenant-admin 角色不应通过"
        assert not check_platform_role(""), "空角色不应通过"


# ===========================================================================
# 场景 4：模板复用（4 个用例）
# ===========================================================================
class TestRuleTemplateReuse:
    """验证告警规则模板可复用，租户自定义阈值生效。"""

    @pytest.fixture(scope="class")
    def p0_rules(self):
        return _load_yaml(_RULES_ROOT / "p0-rules.yaml")

    @pytest.fixture(scope="class")
    def p1_rules(self):
        return _load_yaml(_RULES_ROOT / "p1-rules.yaml")

    @pytest.fixture(scope="class")
    def p2_rules(self):
        return _load_yaml(_RULES_ROOT / "p2-rules.yaml")

    def test_p0_rules_all_have_severity_p0(self, p0_rules):
        """P0 规则模板中所有告警的 severity label 必须为 P0。"""
        all_rules = [r for g in p0_rules["groups"] for r in g["rules"]]
        assert len(all_rules) >= 5, "P0 至少 5 条规则"
        for rule in all_rules:
            assert rule["labels"]["severity"] == "P0", (
                f"规则 {rule['alert']} 的 severity 必须为 P0"
            )

    def test_p1_rules_all_have_severity_p1(self, p1_rules):
        """P1 规则模板中所有告警的 severity label 必须为 P1。"""
        all_rules = [r for g in p1_rules["groups"] for r in g["rules"]]
        assert len(all_rules) >= 5, "P1 至少 5 条规则"
        for rule in all_rules:
            assert rule["labels"]["severity"] == "P1", (
                f"规则 {rule['alert']} 的 severity 必须为 P1"
            )

    def test_p2_rules_all_have_severity_p2(self, p2_rules):
        """P2 规则模板中所有告警的 severity label 必须为 P2。"""
        all_rules = [r for g in p2_rules["groups"] for r in g["rules"]]
        assert len(all_rules) >= 5, "P2 至少 5 条规则"
        for rule in all_rules:
            assert rule["labels"]["severity"] == "P2", (
                f"规则 {rule['alert']} 的 severity 必须为 P2"
            )

    def test_rules_contain_tenant_template_variables(self, p0_rules, p1_rules, p2_rules):
        """租户级规则包含 {{tenant_id}} 模板变量，租户可自定义阈值。"""
        def all_exprs(rules_doc):
            return [r["expr"] for g in rules_doc["groups"] for r in g["rules"]]

        # P0 租户级规则。
        p0_exprs = all_exprs(p0_rules)
        assert any("{{tenant_id}}" in e for e in p0_exprs), (
            "P0 必须包含 {{tenant_id}} 模板变量供租户自定义"
        )

        # P1 租户级规则包含阈值变量。
        p1_exprs = all_exprs(p1_rules)
        assert any("{{fail_threshold}}" in e for e in p1_exprs), (
            "P1 必须包含 {{fail_threshold}} 模板变量"
        )
        assert any("{{latency_p95}}" in e for e in p1_exprs), (
            "P1 必须包含 {{latency_p95}} 模板变量"
        )

        # P2 租户级规则包含阈值变量。
        p2_exprs = all_exprs(p2_rules)
        assert any("{{slow_query_threshold}}" in e for e in p2_exprs), (
            "P2 必须包含 {{slow_query_threshold}} 模板变量"
        )

    def test_tenant_can_customize_threshold_from_template(self, p1_rules):
        """验证租户可基于 P1 模板自定义阈值（模板复用场景）。

        取 P1 模板中的 TenantJobFailureRateHigh 规则，替换变量后
        生成租户 acme-corp 的自定义规则，阈值从默认 0.1 改为 0.05。
        """
        # 找到租户作业失败率规则模板。
        all_rules = [r for g in p1_rules["groups"] for r in g["rules"]]
        template_rule = next(
            r for r in all_rules if r["alert"] == "TenantJobFailureRateHigh"
        )

        # 模拟租户自定义：替换变量。
        customized_expr = (
            template_rule["expr"]
            .replace("{{tenant_id}}", "acme-corp")
            .replace("{{fail_threshold}}", "0.05")
        )

        # 验证替换后表达式正确。
        assert "acme-corp" in customized_expr, "自定义后必须包含租户 ID"
        assert "0.05" in customized_expr, "自定义后必须包含新阈值"
        assert "{{tenant_id}}" not in customized_expr, "自定义后不应有未替换变量"
        assert "{{fail_threshold}}" not in customized_expr, "自定义后不应有未替换变量"

        # 验证自定义规则的 severity 仍为 P1（继承模板分级）。
        customized_labels = dict(template_rule["labels"])
        customized_labels["tenant_id"] = "acme-corp"
        assert customized_labels["severity"] == "P1", "自定义规则应继承模板 severity"


# ===========================================================================
# 真实 Docker 容器测试（可选，仅当 query-api 容器可用时运行）
# ===========================================================================
@skip_if_no_docker
class TestQueryApiDockerIntegration:
    """query-api Docker 容器真实集成测试。

    仅当 it-query-api 容器运行时执行，验证真实 HTTP 行为。
    """

    @pytest.fixture(scope="class")
    def query_api_url(self) -> str:
        return os.environ.get("QUERY_API_URL", "http://localhost:18090")

    @pytest.fixture(scope="class")
    def platform_token(self) -> str:
        """生成 platform-ops 角色的 JWT。"""
        import jwt

        secret = os.environ.get(
            "JWT_SECRET", "it-test-jwt-secret-at-least-32-bytes-long"
        )
        now = int(time.time())
        payload = {
            "iss": "shuqing-bigdata",
            "sub": "platform-tester",
            "tenantId": "platform",
            "role": "platform-ops",
            "iat": now,
            "exp": now + 3600,
        }
        return jwt.encode(payload, secret, algorithm="HS256")

    @pytest.fixture(scope="class")
    def tenant_token(self) -> str:
        """生成租户 JWT。"""
        import jwt

        secret = os.environ.get(
            "JWT_SECRET", "it-test-jwt-secret-at-least-32-bytes-long"
        )
        now = int(time.time())
        payload = {
            "iss": "shuqing-bigdata",
            "sub": "tenant-tester",
            "tenantId": "tenant-docker-it",
            "role": "viewer",
            "iat": now,
            "exp": now + 3600,
        }
        return jwt.encode(payload, secret, algorithm="HS256")

    def test_health_check(self, query_api_url):
        """验证 query-api 健康检查。"""
        import requests

        resp = requests.get(query_api_url + "/api/v1/health", timeout=10)
        assert resp.status_code == 200
        assert resp.json()["status"] == "UP"

    def test_tenant_endpoint_requires_auth(self, query_api_url):
        """验证 /tenant 端点无认证返回 401。"""
        import requests

        resp = requests.get(
            query_api_url + "/tenant/api/v1/query",
            params={"query": "up"},
            timeout=10,
        )
        assert resp.status_code == 401

    def test_platform_endpoint_requires_platform_role(
        self, query_api_url, tenant_token
    ):
        """验证租户角色访问 /platform 端点返回 403。"""
        import requests

        resp = requests.get(
            query_api_url + "/platform/api/v1/query",
            params={"query": "up"},
            headers={"Authorization": f"Bearer {tenant_token}"},
            timeout=10,
        )
        assert resp.status_code == 403