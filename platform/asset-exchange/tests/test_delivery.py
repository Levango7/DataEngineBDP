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
        f"/api/v1/subscriptions/{sid}/approve",
        json={"action": "approve", "approverId": "admin"},
    )
    assert resp.status_code == 200, resp.text
    return aid, sid


# ---------- API 交付 ----------


def test_deliver_via_api(client):
    """API 交付：生成 endpoint + apiKey."""
    _, sid = _setup_active_subscription(client)
    resp = client.post(
        f"/api/v1/subscriptions/{sid}/deliver",
        json={
            "method": "api",
            "config": {
                "endpoint": "/api/v1/data/query",
                "headers": {"Content-Type": "application/json"},
                "rateLimit": "100/s",
                "sampleRows": 200,
            },
        },
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["status"] == "succeeded"
    assert body["method"] == "api"
    assert body["artifactUrl"] == "/api/v1/data/query"
    assert "apiKey" in body["artifactMeta"]
    assert body["dataRows"] == 200
    assert body["dataBytes"] > 0
    assert body["startedAt"] is not None
    assert body["finishedAt"] is not None


# ---------- 文件交付 ----------


def test_deliver_via_file(client):
    """文件交付：生成数据文件 URL."""
    _, sid = _setup_active_subscription(client)
    resp = client.post(
        f"/api/v1/subscriptions/{sid}/deliver",
        json={
            "method": "file",
            "config": {
                "format": "csv",
                "encoding": "utf-8",
                "sampleRows": 1000,
            },
        },
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["status"] == "succeeded"
    assert body["method"] == "file"
    assert body["artifactUrl"].endswith(".csv")
    assert body["artifactMeta"]["format"] == "csv"
    assert "checksum" in body["artifactMeta"]
    assert body["dataRows"] == 1000


def test_deliver_via_file_parquet(client):
    """文件交付支持 parquet 格式."""
    _, sid = _setup_active_subscription(client)
    resp = client.post(
        f"/api/v1/subscriptions/{sid}/deliver",
        json={
            "method": "file",
            "config": {"format": "parquet", "sampleRows": 500},
        },
    )
    assert resp.status_code == 201
    body = resp.json()
    assert body["artifactUrl"].endswith(".parquet")
    assert body["dataRows"] == 500


# ---------- 数据库直连交付 ----------


def test_deliver_via_database_direct(client):
    """数据库直连交付：生成只读访问凭证."""
    _, sid = _setup_active_subscription(client)
    resp = client.post(
        f"/api/v1/subscriptions/{sid}/deliver",
        json={
            "method": "database_direct",
            "config": {
                "jdbcUrl": "jdbc:postgresql://db:5432/data",
                "tableName": "user_events",
                "sampleRows": 10000,
            },
        },
    )
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["status"] == "succeeded"
    assert body["method"] == "database_direct"
    assert "jdbc:postgresql" in body["artifactUrl"]
    assert body["artifactMeta"]["username"] == "ro_user"
    assert body["artifactMeta"]["privilege"] == "SELECT"
    assert body["artifactMeta"]["tableName"] == "user_events"
    assert body["dataRows"] == 10000


# ---------- 交付状态 ----------


def test_get_delivery_status(client):
    _, sid = _setup_active_subscription(client)
    # 先交付
    client.post(
        f"/api/v1/subscriptions/{sid}/deliver",
        json={"method": "api", "config": {}},
    )
    # 查交付状态
    resp = client.get(f"/api/v1/subscriptions/{sid}/delivery-status")
    assert resp.status_code == 200
    body = resp.json()
    assert body["subscriptionId"] == sid
    assert body["status"] == "succeeded"
    assert body["method"] == "api"


def test_get_delivery_status_no_delivery(client):
    """无交付记录返回 404."""
    _, sid = _setup_active_subscription(client)
    resp = client.get(f"/api/v1/subscriptions/{sid}/delivery-status")
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
        f"/api/v1/subscriptions/{sid}/deliver",
        json={"method": "api", "config": {}},
    )
    assert resp.status_code == 409


def test_deliver_nonexistent_subscription(client):
    resp = client.post(
        "/api/v1/subscriptions/nonexistent/deliver",
        json={"method": "api", "config": {}},
    )
    assert resp.status_code == 404


# ---------- 多次交付 ----------


def test_multiple_deliveries(client):
    """同一订阅可多次交付."""
    _, sid = _setup_active_subscription(client)
    # 第一次
    resp = client.post(
        f"/api/v1/subscriptions/{sid}/deliver",
        json={"method": "api", "config": {"sampleRows": 100}},
    )
    assert resp.status_code == 201
    # 第二次
    resp = client.post(
        f"/api/v1/subscriptions/{sid}/deliver",
        json={"method": "file", "config": {"format": "csv", "sampleRows": 200}},
    )
    assert resp.status_code == 201
    # 交付状态返回最新一条
    resp = client.get(f"/api/v1/subscriptions/{sid}/delivery-status")
    body = resp.json()
    assert body["method"] == "file"
