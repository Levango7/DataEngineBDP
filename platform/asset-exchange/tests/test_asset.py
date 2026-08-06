"""资产上架/下架/浏览/更新测试."""
from __future__ import annotations


# ---------- health ----------

def test_health(client):
    resp = client.get("/api/v1/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "UP"
    assert body["store"] == "mock"


# ---------- 上架 ----------

def _list_asset(client, name="user-events", owner="tenant-A"):
    """上架资产辅助函数，返回 asset_id."""
    resp = client.post(
        "/api/v1/assets",
        json={
            "name": name,
            "type": "table",
            "owner": owner,
            "description": "用户行为事件表",
            "securityLevel": "internal",
            "qualityScore": 85.0,
            "schema": {
                "fields": [
                    {"name": "user_id", "type": "string", "description": "用户ID"},
                    {"name": "event", "type": "string", "description": "事件名"},
                    {"name": "ts", "type": "timestamp", "description": "时间戳"},
                ]
            },
            "sample": [
                {"user_id": "u1", "event": "click", "ts": "2026-08-01T00:00:00Z"},
            ],
            "updateFrequency": "daily",
            "tags": {"domain": "marketing"},
            "pricing": {"mode": "by_call", "price": 0.01, "unit": "次"},
        },
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


def test_list_asset(client):
    aid = _list_asset(client)
    resp = client.get(f"/api/v1/assets/{aid}")
    body = resp.json()
    assert body["name"] == "user-events"
    assert body["type"] == "table"
    assert body["status"] == "listed"
    # 租户标识字段统一为 tenantId（MODEL-2）：响应体使用 tenantId 字段名
    assert body["tenantId"] == "tenant-A"
    assert body["qualityScore"] == 85.0
    assert body["pricing"]["price"] == 0.01


def test_list_asset_with_tenant_id_input(client):
    """验证新契约：请求体使用 tenantId 字段名可正常上架."""
    resp = client.post(
        "/api/v1/assets",
        json={
            "name": "tenant-id-input-asset",
            "type": "table",
            "tenantId": "tenant-New",
            "securityLevel": "internal",
        },
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["tenantId"] == "tenant-New"
    # 确认旧字段名 owner 不再出现在响应中（字段已统一为 tenantId）
    assert "owner" not in body


def test_list_asset_owner_alias_backward_compat(client):
    """验证向后兼容：请求体使用旧字段名 owner 仍可正常上架."""
    resp = client.post(
        "/api/v1/assets",
        json={
            "name": "owner-alias-asset",
            "type": "table",
            "owner": "tenant-Legacy",
            "securityLevel": "internal",
        },
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    # 即使输入用 owner，响应统一为 tenantId
    assert body["tenantId"] == "tenant-Legacy"


def test_list_assets_filter_by_tenant_id(client):
    """验证查询参数 tenantId 过滤生效."""
    _list_asset(client, name="asset-for-tenant-a", owner="tenant-A")
    resp = client.post(
        "/api/v1/assets",
        json={
            "name": "asset-for-tenant-c",
            "type": "table",
            "tenantId": "tenant-C",
            "securityLevel": "internal",
        },
    )
    assert resp.status_code == 201
    # 用 tenantId 查询参数过滤
    resp = client.get("/api/v1/assets?tenantId=tenant-A")
    assert resp.status_code == 200
    body = resp.json()
    assert len(body) == 1
    assert body[0]["name"] == "asset-for-tenant-a"


def test_list_assets_filter_by_owner_backward_compat(client):
    """验证向后兼容：查询参数 owner 仍可过滤."""
    _list_asset(client, name="asset-for-owner-filter", owner="tenant-A")
    resp = client.get("/api/v1/assets?owner=tenant-A")
    assert resp.status_code == 200
    body = resp.json()
    assert all(a["tenantId"] == "tenant-A" for a in body)


def test_list_asset_duplicate_name(client):
    """同名上架返回 409."""
    _list_asset(client, name="dup")
    resp = client.post(
        "/api/v1/assets",
        json={"name": "dup", "type": "table", "owner": "tenant-A"},
    )
    assert resp.status_code == 409


def test_list_asset_sensitive_without_desensitize(client):
    """敏感资产未配置脱敏规则返回 422."""
    resp = client.post(
        "/api/v1/assets",
        json={
            "name": "sensitive-data",
            "type": "table",
            "owner": "tenant-A",
            "securityLevel": "sensitive",
        },
    )
    assert resp.status_code == 422


def test_list_asset_sensitive_with_desensitize(client):
    """敏感资产配置脱敏规则后可上架."""
    resp = client.post(
        "/api/v1/assets",
        json={
            "name": "sensitive-data",
            "type": "table",
            "owner": "tenant-A",
            "securityLevel": "sensitive",
            "tags": {"desensitize": "true"},
        },
    )
    assert resp.status_code == 201


# ---------- 浏览 ----------

def test_list_assets(client):
    _list_asset(client, name="a1")
    _list_asset(client, name="a2")
    resp = client.get("/api/v1/assets")
    assert resp.status_code == 200
    body = resp.json()
    assert len(body) == 2


def test_list_assets_filter_by_type(client):
    _list_asset(client, name="table-1")
    client.post(
        "/api/v1/assets",
        json={"name": "api-1", "type": "api", "owner": "tenant-A"},
    )
    resp = client.get("/api/v1/assets?type=api")
    assert resp.status_code == 200
    body = resp.json()
    assert len(body) == 1
    assert body[0]["name"] == "api-1"


def test_list_assets_default_only_listed(client):
    """默认只返回上架资产，下架资产不显示."""
    aid = _list_asset(client, name="will-offline")
    # 下架
    resp = client.delete(f"/api/v1/assets/{aid}")
    assert resp.status_code == 204
    # 默认列表不包含下架资产
    resp = client.get("/api/v1/assets")
    body = resp.json()
    assert all(a["status"] == "listed" for a in body)
    # 显式查 offline 状态可看到
    resp = client.get("/api/v1/assets?status=offline")
    body = resp.json()
    assert len(body) == 1
    assert body[0]["name"] == "will-offline"


# ---------- 详情 ----------

def test_get_asset(client):
    aid = _list_asset(client)
    resp = client.get(f"/api/v1/assets/{aid}")
    assert resp.status_code == 200
    assert resp.json()["id"] == aid


def test_get_asset_not_found(client):
    resp = client.get("/api/v1/assets/nonexistent")
    assert resp.status_code == 404


# ---------- 更新 ----------

def test_update_asset(client):
    aid = _list_asset(client)
    resp = client.put(
        f"/api/v1/assets/{aid}",
        json={"description": "更新后的描述", "qualityScore": 90.0},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["description"] == "更新后的描述"
    assert body["qualityScore"] == 90.0


def test_update_asset_not_found(client):
    resp = client.put(
        "/api/v1/assets/nonexistent",
        json={"description": "x"},
    )
    assert resp.status_code == 404


# ---------- 下架 ----------

def test_offline_asset(client):
    aid = _list_asset(client)
    resp = client.delete(f"/api/v1/assets/{aid}")
    assert resp.status_code == 204
    # 状态应为 offline
    resp = client.get(f"/api/v1/assets/{aid}")
    assert resp.json()["status"] == "offline"


def test_offline_asset_not_found(client):
    resp = client.delete("/api/v1/assets/nonexistent")
    assert resp.status_code == 404


# ---------- 使用统计 ----------

def test_get_asset_usage(client):
    aid = _list_asset(client)
    resp = client.get(f"/api/v1/assets/{aid}/usage")
    assert resp.status_code == 200
    body = resp.json()
    assert body["assetId"] == aid
    assert body["subscriberCount"] == 0
    assert body["activeSubscriptions"] == 0


# ---------- docs ----------

def test_openapi_docs_accessible(client):
    """FastAPI 自动文档可访问."""
    resp = client.get("/docs")
    assert resp.status_code == 200

    resp = client.get("/openapi.json")
    assert resp.status_code == 200
    spec = resp.json()
    assert spec["info"]["title"] == "Asset Exchange Platform"
    paths = spec["paths"]
    assert "/api/v1/assets" in paths
    assert "/api/v1/health" in paths