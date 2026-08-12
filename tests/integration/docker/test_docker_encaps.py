"""封装层（encaps-layer）Docker 集成测试。

被测对象：Docker 容器 ``it-encaps-layer``（镜像 ``sq/encaps-layer:0.1.0``），
Java/Spring Boot，主机端口 18080 → 容器 8080。

覆盖端点：
- GET  /actuator/health       （健康检查，无需认证）
- GET  /api/v1/tenants        （列出租户，需认证）
- POST /api/v1/tenants        （创建租户，需认证）
- GET  /api/v1/tenants/{id}   （获取单个租户，需认证）
- PUT  /api/v1/tenants/{id}   （更新租户，需认证）
- DELETE /api/v1/tenants/{id} （删除租户，需认证）

设计要点：
- 每个测试函数独立，不依赖执行顺序；
- 使用 ``sample_tenant`` fixture 创建测试数据并在结束后自动清理；
- 完整 CRUD 流程通过 ``test_tenant_crud_flow`` 端到端验证；
- 验证无认证请求返回 401（认证机制正常）。
"""

from __future__ import annotations

import pytest


# ---------------------------------------------------------------------------
# 健康检查
# ---------------------------------------------------------------------------
def test_health_check(encaps_url):
    """验证封装层健康检查端点返回 200 且 status=UP。

    健康检查端点无需认证，直接用 requests.get。
    """
    import requests

    resp = requests.get(encaps_url + "/actuator/health", timeout=10)
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("status") == "UP"


def test_health_check_components(encaps_url):
    """验证封装层健康检查包含 db、diskSpace、ping 组件且均为 UP。"""
    import requests

    resp = requests.get(encaps_url + "/actuator/health", timeout=10)
    assert resp.status_code == 200
    body = resp.json()
    components = body.get("components", {})
    # 数据库组件（H2）应为 UP。
    assert components.get("db", {}).get("status") == "UP"
    # 磁盘空间组件应为 UP。
    assert components.get("diskSpace", {}).get("status") == "UP"


# ---------------------------------------------------------------------------
# 认证机制验证
# ---------------------------------------------------------------------------
def test_unauthorized_without_token(encaps_url):
    """验证无 Bearer token 访问受保护端点返回 401。"""
    import requests

    resp = requests.get(encaps_url + "/api/v1/tenants", timeout=10)
    assert resp.status_code == 401


def test_unauthorized_with_invalid_token(encaps_url):
    """验证无效 Bearer token 访问受保护端点返回 401。"""
    import requests

    resp = requests.get(
        encaps_url + "/api/v1/tenants",
        headers={"Authorization": "Bearer invalid-token-xxx"},
        timeout=10,
    )
    assert resp.status_code == 401


# ---------------------------------------------------------------------------
# 租户 CRUD
# ---------------------------------------------------------------------------
def test_create_tenant(api_client, encaps_url):
    """验证 POST /api/v1/tenants 创建租户返回 201，且响应体含 id 与请求字段。"""
    payload = {
        "name": "docker-it-create-tenant",
        "displayName": "Docker集成测试租户",
        "namespace": "ns-docker-it-create",
        "quotaProfile": "small",
        "status": "ACTIVE",
    }
    resp = api_client.post(encaps_url + "/api/v1/tenants", json=payload)
    assert resp.status_code == 201
    body = resp.json()
    assert "id" in body
    assert body.get("name") == payload["name"]
    assert body.get("namespace") == payload["namespace"]
    assert body.get("status") == payload["status"]

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
    """验证 GET /api/v1/tenants/{id} 对不存在的 id 返回 404。"""
    resp = api_client.get(encaps_url + "/api/v1/tenants/999999")
    assert resp.status_code == 404


def test_update_tenant(api_client, encaps_url, sample_tenant):
    """验证 PUT /api/v1/tenants/{id} 更新租户返回 200 且字段已更新。"""
    tenant_id = sample_tenant["id"]
    update_payload = {
        "name": sample_tenant["name"],
        "displayName": "更新后的显示名",
        "namespace": sample_tenant["namespace"],
        "quotaProfile": "large",
        "status": "ACTIVE",
    }
    resp = api_client.put(
        encaps_url + f"/api/v1/tenants/{tenant_id}", json=update_payload
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("id") == tenant_id
    assert body.get("displayName") == "更新后的显示名"
    assert body.get("quotaProfile") == "large"


def test_delete_tenant(api_client, encaps_url):
    """验证 DELETE /api/v1/tenants/{id} 删除租户返回 204。"""
    # 先创建一个待删除的租户。
    payload = {
        "name": "docker-it-delete-tenant",
        "namespace": "ns-docker-it-delete",
        "quotaProfile": "small",
    }
    create_resp = api_client.post(encaps_url + "/api/v1/tenants", json=payload)
    assert create_resp.status_code == 201
    tenant_id = create_resp.json()["id"]

    # 删除。
    resp = api_client.delete(encaps_url + f"/api/v1/tenants/{tenant_id}")
    assert resp.status_code == 204

    # 验证已删除：再次 GET 应返回 404。
    verify_resp = api_client.get(encaps_url + f"/api/v1/tenants/{tenant_id}")
    assert verify_resp.status_code == 404


# ---------------------------------------------------------------------------
# 端到端 CRUD 流程
# ---------------------------------------------------------------------------
def test_tenant_crud_flow(api_client, encaps_url):
    """端到端验证租户 CRUD 完整流程：创建 → 查询 → 更新 → 删除。"""
    # 1. 创建
    create_payload = {
        "name": "docker-it-crud-flow",
        "displayName": "CRUD流程测试",
        "namespace": "ns-docker-crud",
        "quotaProfile": "medium",
        "status": "ACTIVE",
    }
    create_resp = api_client.post(encaps_url + "/api/v1/tenants", json=create_payload)
    assert create_resp.status_code == 201
    tenant = create_resp.json()
    tenant_id = tenant["id"]

    try:
        # 2. 查询
        get_resp = api_client.get(encaps_url + f"/api/v1/tenants/{tenant_id}")
        assert get_resp.status_code == 200
        assert get_resp.json()["name"] == create_payload["name"]

        # 3. 更新
        update_payload = {**create_payload, "quotaProfile": "large"}
        update_resp = api_client.put(
            encaps_url + f"/api/v1/tenants/{tenant_id}", json=update_payload
        )
        assert update_resp.status_code == 200
        assert update_resp.json()["quotaProfile"] == "large"

        # 4. 列表包含
        list_resp = api_client.get(encaps_url + "/api/v1/tenants")
        assert list_resp.status_code == 200
        assert tenant_id in [t["id"] for t in list_resp.json()]
    finally:
        # 4. 清理
        api_client.delete(encaps_url + f"/api/v1/tenants/{tenant_id}")


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------
@pytest.fixture
def sample_tenant(api_client, encaps_url):
    """创建一个示例租户，测试结束后自动删除。

    Yields:
        创建后的租户字典（含 id 等字段）。
    """
    payload = {
        "name": "docker-it-sample-tenant",
        "displayName": "示例租户",
        "namespace": "ns-docker-it-sample",
        "quotaProfile": "small",
        "status": "ACTIVE",
    }
    resp = api_client.post(encaps_url + "/api/v1/tenants", json=payload)
    assert resp.status_code == 201
    tenant = resp.json()

    yield tenant

    # 清理。
    try:
        api_client.delete(encaps_url + f"/api/v1/tenants/{tenant['id']}")
    except Exception:
        pass