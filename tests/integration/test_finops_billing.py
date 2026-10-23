"""FinOps 计费链路集成测试（台账 #91）。

覆盖 dashboard → cost-model 的账单透传：两个服务都在默认 compose 栈里
（cost-model 18084 / finops-dashboard 18087），但此前没有任何集成测试打这条链路。

本文件钉的是一条真实缺陷：dashboard 的 QueryBillingClient 只给 cost-model 传了
``X-Tenant-Id`` 而不转发用户 JWT，而 cost-model 的账单端点用 common-security 的
TenantContext（由 JWT 过滤器写入）判租户 ⇒ ``/api/v1/dashboard/billing/tenant``
恒返 502「账单服务暂不可用: 401」，即使两个服务都健康。
"""

from __future__ import annotations

import pytest
import requests

from conftest import DEFAULT_TIMEOUT


@pytest.fixture(scope="module", autouse=True)
def _require_finops_stack(cost_model_url, finops_dashboard_url):
    for url in (cost_model_url, finops_dashboard_url):
        try:
            resp = requests.get(url + "/api/v1/health", timeout=5)
        except requests.RequestException as exc:  # noqa: BLE001 - 仅用于分类 skip
            pytest.skip(f"FinOps 服务不可达：{url}（{exc}）")
        if resp.status_code != 200:
            pytest.skip(f"FinOps 服务不可达：{url}/api/v1/health（HTTP {resp.status_code}）")


def test_cost_model_health(cost_model_url):
    resp = requests.get(cost_model_url + "/api/v1/health", timeout=DEFAULT_TIMEOUT)
    assert resp.status_code == 200, resp.text
    assert resp.json().get("status") == "UP"


def test_finops_dashboard_health(finops_dashboard_url):
    resp = requests.get(
        finops_dashboard_url + "/api/v1/health", timeout=DEFAULT_TIMEOUT
    )
    assert resp.status_code == 200, resp.text
    assert resp.json().get("status") == "UP"


def test_cost_model_rejects_tenant_header_without_token(cost_model_url):
    """X-Tenant-Id 不是凭据：cost-model 的账单端点必须要求 JWT。"""
    resp = requests.get(
        cost_model_url + "/api/v1/finops/billing/tenant",
        headers={"X-Tenant-Id": "it-test-tenant"},
        timeout=DEFAULT_TIMEOUT,
    )
    assert resp.status_code == 401, (
        f"仅带 X-Tenant-Id 不应通过鉴权，实际 {resp.status_code}：{resp.text}"
    )


def test_dashboard_tenant_billing_passthrough(api_client, finops_dashboard_url):
    """dashboard 必须带着用户 token 调 cost-model，而不是只传 X-Tenant-Id。

    修前实测：502「账单服务暂不可用: 401 Unauthorized: missing or non-Bearer
    Authorization header」；修后 200 且 tenant 与 token 里的 claim 一致。
    """
    resp = api_client.get(
        finops_dashboard_url + "/api/v1/dashboard/billing/tenant"
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body.get("tenant") == "it-test-tenant", body
    assert "totalCost" in body, body


def test_dashboard_tenant_billing_trend(api_client, finops_dashboard_url):
    resp = api_client.get(
        finops_dashboard_url + "/api/v1/dashboard/billing/tenant/trend"
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert isinstance(body.get("points"), list), body
    assert body.get("tenant") == "it-test-tenant", body


def test_dashboard_billing_requires_jwt(finops_dashboard_url):
    resp = requests.get(
        finops_dashboard_url + "/api/v1/dashboard/billing/tenant",
        headers={"Authorization": ""},
        timeout=DEFAULT_TIMEOUT,
    )
    assert resp.status_code == 401, resp.text
