"""数据交付测试（3种方式：API / 文件 / 数据库直连）."""

from __future__ import annotations

# ---------- 辅助函数 ----------


def _setup_active_subscription(client, owner="tenant-A", subscriber="tenant-B"):
    """上架资产 + 订阅 + 审批通过，返回 (asset_id, subscription_id)."""
    resp = client.post(
        "/api/v1/assets",
        json={
            "name": "delivery-test-asset",
            "type": "table",
            "owner": owner,
            "securityLevel": "internal",
            "qualityScore": 85.0,
            "pricing": {"mode": "by_call", "price": 0.01, "unit": "次"},
        },
    )
    assert resp.status_code == 201, resp.text
    aid = resp.json()["id"]

    resp = client.post(
        f"/api/v1/assets/{aid}/subscribe",
        json={"subscriberId": subscriber, "durationDays": 30},
    )
    assert resp.status_code == 201, resp.text
    sid = resp.json()["id"]

    resp = client.post(
        f"/api/v1/asset-subscriptions/{sid}/approve",
        json={"action": "approve", "approverId": "admin"},
    )
    assert resp.status_code == 200, resp.text
    return aid, sid


# ---------- API 交付（真实凭证来自开放 API 目录） ----------


def _patch_credential(monkeypatch, credential):
    """把 catalog 取凭证结果固定为 credential（避免测试打到真实 :8090）."""
    monkeypatch.setattr(
        "asset_exchange.services.openapi_catalog_client.OpenApiCatalogClient.obtain_credential",
        lambda self, **kwargs: _async_value(credential),
    )


def _async_value(value):
    """构造可直接 await 的假协程返回值."""

    async def _coro():
        return value

    return _coro()


def test_deliver_via_api_uses_real_credential(client, monkeypatch):
    """API 交付产物必须是目录发放的真实 AK；且明文不入库。"""
    from asset_exchange.services.openapi_catalog_client import ApiCredential

    _patch_credential(
        monkeypatch,
        ApiCredential(
            subscriptionId="cs-1",
            status="active",
            accessKey="ak-real-abcdef1234567890",
            grantedQuota=100,
            invokePath="/api/v1/apis/api-9/invoke",
        ),
    )
    _, sid = _setup_active_subscription(client)
    resp = client.post(
        f"/api/v1/asset-subscriptions/{sid}/deliver",
        json={"method": "api", "config": {"apiId": "api-9"}},
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    meta = body["artifactMeta"]

    assert body["status"] == "succeeded"
    assert body["artifactUrl"] == "/api/v1/apis/api-9/invoke"
    assert meta["credentialSource"] == "open-api-catalog"
    assert meta["catalogSubscriptionId"] == "cs-1"
    assert meta["grantedQuota"] == 100
    # 只存掩码：交付记录里不得出现明文凭据
    assert meta["accessKeyMasked"] == "ak-r****7890"
    assert "accessKey" not in meta
    assert "ak-real-abcdef1234567890" not in resp.text
    # 用量不在交付时刻估算
    assert body["dataRows"] == 0
    assert body["dataBytes"] == 0
    assert meta["usageMeasuredBy"] == "open-api-catalog"


def test_deliver_via_api_stays_pending_until_credential_issued(client, monkeypatch):
    """提供方尚未审批（无 AK）时：不谎报成功，交付留在 pending 并说明原因。"""
    from asset_exchange.services.openapi_catalog_client import ApiCredential

    _patch_credential(
        monkeypatch,
        ApiCredential(subscriptionId="cs-2", status="pending", accessKey=None),
    )
    _, sid = _setup_active_subscription(client)
    resp = client.post(
        f"/api/v1/asset-subscriptions/{sid}/deliver",
        json={"method": "api", "config": {"apiId": "api-9"}},
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()

    assert body["status"] == "pending"
    assert body["artifactUrl"] is None
    assert "审批" in body["errorMessage"]
    assert body["artifactMeta"]["catalogSubscriptionId"] == "cs-2"
    assert "accessKeyMasked" not in body["artifactMeta"]


def test_deliver_via_api_requires_api_id(client):
    """缺 apiId 时如实失败，而不是回落到一个假端点。"""
    _, sid = _setup_active_subscription(client)
    resp = client.post(
        f"/api/v1/asset-subscriptions/{sid}/deliver",
        json={"method": "api", "config": {}},
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["status"] == "failed"
    assert "apiId" in body["errorMessage"]
    assert body["artifactUrl"] is None


def test_deliver_via_api_catalog_unreachable(client, monkeypatch):
    """目录服务不可达 → failed，并把原因原样留给排障。"""
    from asset_exchange.services.openapi_catalog_client import OpenApiCatalogError

    async def _boom(self, **kwargs):
        raise OpenApiCatalogError("开放 API 目录网络异常: connection refused")

    monkeypatch.setattr(
        "asset_exchange.services.openapi_catalog_client.OpenApiCatalogClient.obtain_credential",
        _boom,
    )
    _, sid = _setup_active_subscription(client)
    resp = client.post(
        f"/api/v1/asset-subscriptions/{sid}/deliver",
        json={"method": "api", "config": {"apiId": "api-9"}},
    )
    body = resp.json()
    assert body["status"] == "failed"
    assert "开放 API 目录" in body["errorMessage"]


# ---------- 文件 / 数据库直连交付：暂不具备条件，如实失败 ----------


def test_deliver_via_file_fails_honestly(client):
    """文件交付不再返回假 URL/假 checksum，而是明确失败并指向解锁条件。"""
    _, sid = _setup_active_subscription(client)
    resp = client.post(
        f"/api/v1/asset-subscriptions/{sid}/deliver",
        json={"method": "file", "config": {"format": "csv", "sampleRows": 1000}},
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()

    assert body["status"] == "failed"
    assert body["artifactUrl"] is None
    assert body["artifactMeta"] == {}
    assert "FILE 交付尚未具备真实实现条件" in body["errorMessage"]
    assert "资产交付实现状态" in body["errorMessage"]
    # 关键回归点：不得再出现假产物
    assert "example.com" not in resp.text
    assert "sha256:0000" not in resp.text


def test_deliver_via_database_direct_fails_honestly(client):
    """数据库直连交付不再返回假只读账号（尤其不能出现明文凭据字段）。"""
    _, sid = _setup_active_subscription(client)
    resp = client.post(
        f"/api/v1/asset-subscriptions/{sid}/deliver",
        json={
            "method": "database_direct",
            "config": {"jdbcUrl": "jdbc:postgresql://db:5432/data", "tableName": "user_events"},
        },
    )
    body = resp.json()
    assert body["status"] == "failed"
    assert "DATABASE_DIRECT 交付尚未具备真实实现条件" in body["errorMessage"]
    assert "ro_user" not in resp.text


# ---------- 交付状态 ----------


def test_get_delivery_status(client, monkeypatch):
    from asset_exchange.services.openapi_catalog_client import ApiCredential

    _patch_credential(
        monkeypatch,
        ApiCredential(subscriptionId="cs-3", status="active", accessKey="ak-abcdefgh12345678"),
    )
    _, sid = _setup_active_subscription(client)
    client.post(
        f"/api/v1/asset-subscriptions/{sid}/deliver",
        json={"method": "api", "config": {"apiId": "api-9"}},
    )
    resp = client.get(f"/api/v1/asset-subscriptions/{sid}/delivery-status")
    assert resp.status_code == 200
    body = resp.json()
    assert body["subscriptionId"] == sid
    assert body["status"] == "succeeded"
    assert body["method"] == "api"


def test_get_delivery_status_no_delivery(client):
    """无交付记录返回 404."""
    _, sid = _setup_active_subscription(client)
    resp = client.get(f"/api/v1/asset-subscriptions/{sid}/delivery-status")
    assert resp.status_code == 404


# ---------- 不可交付场景 ----------


def test_deliver_pending_subscription(client):
    """待审批订阅不可交付."""
    resp = client.post(
        "/api/v1/assets",
        json={"name": "x", "type": "table", "owner": "tenant-A", "qualityScore": 85.0},
    )
    aid = resp.json()["id"]
    resp = client.post(
        f"/api/v1/assets/{aid}/subscribe",
        json={"subscriberId": "tenant-B"},
    )
    sid = resp.json()["id"]
    # 未审批直接交付应失败
    resp = client.post(
        f"/api/v1/asset-subscriptions/{sid}/deliver",
        json={"method": "api", "config": {}},
    )
    assert resp.status_code == 409


def test_deliver_nonexistent_subscription(client):
    resp = client.post(
        "/api/v1/asset-subscriptions/nonexistent/deliver",
        json={"method": "api", "config": {}},
    )
    assert resp.status_code == 404


# ---------- 多次交付 ----------


def test_multiple_deliveries(client):
    """同一订阅可多次交付."""
    _, sid = _setup_active_subscription(client)
    # 第一次
    resp = client.post(
        f"/api/v1/asset-subscriptions/{sid}/deliver",
        json={"method": "api", "config": {"sampleRows": 100}},
    )
    assert resp.status_code == 201
    # 第二次
    resp = client.post(
        f"/api/v1/asset-subscriptions/{sid}/deliver",
        json={"method": "file", "config": {"format": "csv", "sampleRows": 200}},
    )
    assert resp.status_code == 201
    # 交付状态返回最新一条
    resp = client.get(f"/api/v1/asset-subscriptions/{sid}/delivery-status")
    body = resp.json()
    assert body["method"] == "file"
