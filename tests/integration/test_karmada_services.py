"""karmada 域四服务集成测试（台账 #90）。

覆盖 compose profile ``karmada-extra`` 拉起的四个服务：
karmada-api（18101）/ failover-api（18107）/ failover-engine（18108）/
federated-query（18109）。

这几条断言防的是「单测与生产挂载前缀不一致」这一类缺陷：
karmada-api 的 ClusterHandler 曾在已挂到 ``/api/v1/clusters`` 的分组里再写
``rg.POST("/clusters")``，实际路径成了 ``/api/v1/clusters/clusters``，而单测把
handler 直接挂在 ``/api/v1`` 上，所以测试一直是绿的、生产按 ``/api/v1/clusters``
调必 404。本文件打的是 compose 里真实运行的进程，不再依赖测试自造的挂载形态。

上游 Karmada 控制面不在本栈内，karmada-api 按 main.go 的既定行为降级为 stub
响应 —— 这里验的是「服务能起、路由形状对、鉴权生效」，不是控制面联调。
"""

from __future__ import annotations

import os
import time

import jwt
import pytest
import requests

from conftest import DEFAULT_TIMEOUT, JWT_ISSUER, JWT_SECRET

# CI 里 compose 以 --profile karmada-extra 显式拉起这四个服务，
# 此时不可达属于真故障，不允许 skip 掉（否则 profile 白开、门禁形同虚设）。
KARMADA_PROFILE_REQUIRED = os.environ.get("SQ_IT_KARMADA_PROFILE") == "1"

_NO_AUTH_HEADERS = {"Authorization": ""}


def _ensure_reachable(url: str, health_path: str) -> None:
    try:
        resp = requests.get(url.rstrip("/") + health_path, timeout=5)
    except requests.RequestException as exc:  # noqa: BLE001 - 只为分类 skip/fail
        detail = str(exc)
    else:
        if resp.status_code == 200:
            return
        detail = f"HTTP {resp.status_code}"
    reason = f"karmada 域服务不可达：{url}{health_path}（{detail}）"
    if KARMADA_PROFILE_REQUIRED:
        pytest.fail(reason + "；SQ_IT_KARMADA_PROFILE=1 时不允许跳过")
    pytest.skip(reason + "；未启用 compose profile: karmada-extra")


def _tenant_token(tenant_id: str = "it-test-tenant") -> str:
    payload = {
        "iss": JWT_ISSUER,
        "sub": "it-karmada-tester",
        "tenantId": tenant_id,
        "realm_access": {"roles": ["USER"]},
        "iat": int(time.time()),
        "exp": int(time.time()) + 3600,
    }
    return jwt.encode(payload, JWT_SECRET, algorithm="HS256")


@pytest.fixture(scope="module", autouse=True)
def _require_karmada_stack(
    karmada_api_url,
    failover_api_url,
    failover_engine_url,
    federated_query_url,
):
    """四个服务缺一不可：本文件的断言正建立在「它们都在栈里」这个前提上。"""
    _ensure_reachable(karmada_api_url, "/api/v1/health")
    _ensure_reachable(failover_api_url, "/api/v1/health")
    _ensure_reachable(failover_engine_url, "/healthz")
    _ensure_reachable(federated_query_url, "/actuator/health")


@pytest.fixture
def tenant_headers(api_client):
    """带 tenantId claim 的请求头（failover-api 的 handler 依赖该 claim）。"""
    return api_client.auth_header


# ===========================================================================
# karmada-api
# ===========================================================================
def test_karmada_api_health(api_client, karmada_api_url):
    resp = api_client.get(karmada_api_url + "/api/v1/health")
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body.get("status") == "UP"
    assert body.get("service") == "karmada-api"


def test_karmada_api_clusters_uses_single_prefix(api_client, karmada_api_url):
    """GET /api/v1/clusters 必须 200；双前缀 /clusters/clusters 必须不存在。

    这是 #89 登记、本 PR 修掉的实现侧缺陷的回归守卫：把 handler 的
    "/clusters" 前缀写回去，本用例立刻红。
    """
    resp = api_client.get(karmada_api_url + "/api/v1/clusters")
    assert resp.status_code == 200, (
        f"/api/v1/clusters 应返回 200，实际 {resp.status_code}：{resp.text}"
    )
    body = resp.json()
    # 控制面未接入时按 stub 形态返回空列表（main.go:107 的既定降级）。
    assert isinstance(body.get("clusters"), list), body

    # 双前缀路径即便命中 /:name 也只能落到单集群查询（控制面缺席时 503），
    # 绝不能是列表端点——是 200 就说明同一份列表路由被注册了两次。
    doubled = api_client.get(karmada_api_url + "/api/v1/clusters/clusters")
    assert doubled.status_code != 200, (
        "双前缀路径不应是列表端点：/api/v1/clusters/clusters 返回了 200"
    )


def test_karmada_api_clusters_require_jwt(karmada_api_url):
    resp = requests.get(
        karmada_api_url + "/api/v1/clusters",
        headers=_NO_AUTH_HEADERS,
        timeout=DEFAULT_TIMEOUT,
    )
    assert resp.status_code == 401, resp.text


def test_karmada_api_propagation_policy_roundtrip(api_client, karmada_api_url):
    name = "it-propagation-policy"
    payload = {
        "name": name,
        "namespace": "it-test",
        "spec": {
            "resourceSelectors": [
                {
                    "apiVersion": "apps/v1",
                    "kind": "Deployment",
                    "name": "it-demo",
                }
            ],
            "placement": {
                "replicaScheduling": {
                    "replicaSchedulingType": "Duplicated",
                    "replicaDivisionPreference": "Aggregated",
                }
            },
            "priority": 10,
        },
    }
    created = api_client.post(
        karmada_api_url + "/api/v1/propagation-policies", json=payload
    )
    assert created.status_code in (201, 409), created.text

    listed = api_client.get(
        karmada_api_url + "/api/v1/propagation-policies",
        params={"namespace": "it-test"},
    )
    assert listed.status_code == 200, listed.text
    body = listed.json()
    items = body.get("items") or body.get("data") or []
    assert any(item.get("name") == name for item in items), body


# ===========================================================================
# failover-api
# ===========================================================================
def test_failover_api_health(api_client, failover_api_url):
    resp = api_client.get(failover_api_url + "/api/v1/health")
    assert resp.status_code == 200, resp.text
    assert resp.json().get("status") == "UP"


def test_failover_api_override_policies_shape(api_client, failover_api_url):
    resp = api_client.get(failover_api_url + "/api/v1/override-policies")
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert isinstance(body.get("items"), list), body
    assert isinstance(body.get("total"), int), body


def test_failover_api_requires_jwt(failover_api_url):
    resp = requests.get(
        failover_api_url + "/api/v1/override-policies",
        headers=_NO_AUTH_HEADERS,
        timeout=DEFAULT_TIMEOUT,
    )
    assert resp.status_code == 401, resp.text


def test_failover_api_cluster_health_route(api_client, failover_api_url):
    """``/api/v1/clusters/health`` 是 failover-api 注册的裸前缀端点。

    网关侧该前缀被 infra-orchestrator 粗前缀覆盖（台账 #89），这里只验实现
    自身可达；路由归属不靠本用例证明。
    """
    resp = api_client.get(failover_api_url + "/api/v1/clusters/health")
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert isinstance(body.get("items"), list), body


# ===========================================================================
# failover-engine
# ===========================================================================
def test_failover_engine_healthz(failover_engine_url):
    resp = requests.get(failover_engine_url + "/healthz", timeout=DEFAULT_TIMEOUT)
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body.get("status") == "ok"
    assert body.get("service") == "failover-engine"


def test_failover_engine_reports_state(failover_engine_url):
    """引擎主循环已启动时 /metrics 应给出可解析的 JSON（非 5xx）。"""
    resp = requests.get(failover_engine_url + "/metrics", timeout=DEFAULT_TIMEOUT)
    assert resp.status_code == 200, resp.text
    assert isinstance(resp.json(), dict)


# ===========================================================================
# federated-query
# ===========================================================================
def test_federated_query_actuator_health(federated_query_url):
    resp = requests.get(
        federated_query_url + "/actuator/health", timeout=DEFAULT_TIMEOUT
    )
    assert resp.status_code == 200, resp.text
    assert resp.json().get("status") == "UP"


def test_federated_query_health_requires_jwt(federated_query_url):
    resp = requests.get(
        federated_query_url + "/api/v1/federated/health",
        headers=_NO_AUTH_HEADERS,
        timeout=DEFAULT_TIMEOUT,
    )
    assert resp.status_code in (401, 403), resp.text


def test_federated_query_health_with_jwt(api_client, federated_query_url):
    resp = api_client.get(federated_query_url + "/api/v1/federated/health")
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body.get("status") == "UP"
    assert body.get("service") == "federated-query"


def test_federated_query_lists_clusters(api_client, federated_query_url):
    resp = api_client.get(federated_query_url + "/api/v1/federated/clusters")
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert isinstance(body.get("data"), list), body
    # 栈内没有成员集群，但配置里有三条拓扑项；shape 必须稳定。
    assert isinstance(body.get("total"), int), body
