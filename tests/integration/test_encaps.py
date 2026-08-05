"""封装层（encaps-layer）集成测试。

被测对象：``platform/encaps-layer``，Java/Spring Boot，默认端口 8080。

覆盖端点：
- GET    /api/v1/health
- POST   /api/v1/tenants
- GET    /api/v1/tenants
- GET    /api/v1/tenants/{id}
- PUT    /api/v1/tenants/{id}
- DELETE /api/v1/tenants/{id}

设计要点：
- 每个测试函数独立，不依赖执行顺序；
- 使用 ``sample_tenant`` fixture 创建测试数据并在结束后自动清理；
- 完整 CRUD 流程通过 ``test_tenant_crud_flow`` 端到端验证。
"""

from __future__ import annotations

import pytest


# ---------------------------------------------------------------------------
# 健康检查
# ---------------------------------------------------------------------------
def test_health_check(api_client, encaps_url):
    """验证封装层健康检查端点返回 200 且 status=UP。"""
    resp = api_client.get(encaps_url + "/api/v1/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("status") == "UP"


# ---------------------------------------------------------------------------
# 租户 CRUD
# ---------------------------------------------------------------------------
def test_create_tenant(api_client, encaps_url):
    """验证 POST /api/v1/tenants 创建租户返回 201，且响应体含 id 与请求字段。"""
    payload = {
        "name": "it-create-tenant",
        "displayName": "创建租户测试",
        "namespace": "ns-it-create",
        "quotaProfile": "small",
    }
    resp = api_client.post(encaps_url + "/api/v1/tenants", json=payload)
    assert resp.status_code == 201
    body = resp.json()
    assert "id" in body
    assert body.get("name") == payload["name"]
    assert body.get("namespace") == payload["namespace"]

    # 清理：删除刚创建的租户，避免污染后续测试。
    try:
        api_client.delete(encaps_url + f"/api/v1/tenants/{body['id']}")
    except Exception:
        pass


def test_list_tenants(api_client, encaps_url, sample_tenant):
    """验证 GET /api/v1/tenants 返回 200 且为列表，包含已创建的租户。"""
    resp = api_client.get(encaps_url + "/api/v1/tenants")
    assert resp.status_code == 200
    body = resp.json()
    assert isinstance(body, list)
    # sample_tenant 已创建，应在列表中。
    ids = [t.get("id") for t in body]
    assert sample_tenant["id"] in ids


def test_get_tenant(api_client, encaps_url, sample_tenant):
    """验证 GET /api/v1/tenants/{id} 返回 200 且字段与创建时一致。"""
    tenant_id = sample_tenant["id"]
    resp = api_client.get(encaps_url + f"/api/v1/tenants/{tenant_id}")
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("id") == tenant_id
    assert body.get("name") == sample_tenant["name"]


def test_get_tenant_not_found(api_client, encaps_url):
    """验证 GET /api/v1/tenants/999999 对不存在的 id 返回 404。"""
    resp = api_client.get(encaps_url + "/api/v1/tenants/999999")
    assert resp.status_code == 404


def test_update_tenant(api_client, encaps_url, sample_tenant):
    """验证 PUT /api/v1/tenants/{id} 更新租户返回 200 且字段已更新。"""
    tenant_id = sample_tenant["id"]
    update_payload = {
        "name": "it-test-tenant-updated",
        "displayName": "集成测试租户-已更新",
        "namespace": "ns-it-test-updated",
        "quotaProfile": "large",
    }
    resp = api_client.put(
        encaps_url + f"/api/v1/tenants/{tenant_id}", json=update_payload
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("id") == tenant_id
    assert body.get("name") == update_payload["name"]
    assert body.get("quotaProfile") == update_payload["quotaProfile"]


def test_update_tenant_not_found(api_client, encaps_url):
    """验证 PUT /api/v1/tenants/999999 对不存在的 id 返回 404。"""
    resp = api_client.put(
        encaps_url + "/api/v1/tenants/999999",
        json={"name": "x", "displayName": "x", "namespace": "x", "quotaProfile": "x"},
    )
    assert resp.status_code == 404


def test_delete_tenant(api_client, encaps_url):
    """验证 DELETE /api/v1/tenants/{id} 删除租户返回 204，再次删除返回 404。"""
    # 先创建一个待删除租户。
    create_resp = api_client.post(
        encaps_url + "/api/v1/tenants",
        json={
            "name": "it-delete-tenant",
            "displayName": "删除租户测试",
            "namespace": "ns-it-delete",
            "quotaProfile": "small",
        },
    )
    assert create_resp.status_code == 201
    tenant_id = create_resp.json()["id"]

    # 删除应返回 204。
    del_resp = api_client.delete(encaps_url + f"/api/v1/tenants/{tenant_id}")
    assert del_resp.status_code == 204

    # 再次删除应返回 404。
    again_resp = api_client.delete(encaps_url + f"/api/v1/tenants/{tenant_id}")
    assert again_resp.status_code == 404


def test_tenant_crud_flow(api_client, encaps_url):
    """端到端 CRUD 流程：创建 → 获取 → 更新 → 删除 → 再获取（404）。

    本测试不依赖任何 fixture，自管理数据，验证完整生命周期。
    """
    # 1. 创建
    create_resp = api_client.post(
        encaps_url + "/api/v1/tenants",
        json={
            "name": "it-flow-tenant",
            "displayName": "流程测试租户",
            "namespace": "ns-it-flow",
            "quotaProfile": "medium",
        },
    )
    assert create_resp.status_code == 201
    tenant = create_resp.json()
    tenant_id = tenant["id"]

    try:
        # 2. 获取
        get_resp = api_client.get(encaps_url + f"/api/v1/tenants/{tenant_id}")
        assert get_resp.status_code == 200
        assert get_resp.json()["name"] == "it-flow-tenant"

        # 3. 更新
        update_resp = api_client.put(
            encaps_url + f"/api/v1/tenants/{tenant_id}",
            json={
                "name": "it-flow-tenant-v2",
                "displayName": "流程测试租户-v2",
                "namespace": "ns-it-flow-v2",
                "quotaProfile": "large",
            },
        )
        assert update_resp.status_code == 200
        assert update_resp.json()["name"] == "it-flow-tenant-v2"

        # 4. 列表中应包含该租户
        list_resp = api_client.get(encaps_url + "/api/v1/tenants")
        assert list_resp.status_code == 200
        assert tenant_id in [t["id"] for t in list_resp.json()]
    finally:
        # 4. 删除（无论上面断言是否通过，都尝试清理）。
        del_resp = api_client.delete(encaps_url + f"/api/v1/tenants/{tenant_id}")
        assert del_resp.status_code == 204

    # 5. 删除后获取应 404
    not_found_resp = api_client.get(encaps_url + f"/api/v1/tenants/{tenant_id}")
    assert not_found_resp.status_code == 404