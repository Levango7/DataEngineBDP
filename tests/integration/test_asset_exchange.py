"""Asset Exchange（数据资产流通平台, L5.6）集成测试.

被测组件：platform/asset-exchange（FastAPI, 默认端口 8087）
启动方式：cd platform/asset-exchange && ASSET_EXCHANGE_PORT=8087 python main.py

健康检查：GET /api/v1/health → {"status": "UP", "store": "...", "version": "0.1.0"}
主要端点：
    POST   /api/v1/assets                  上架资产
    GET    /api/v1/assets                   浏览资产市场
    GET    /api/v1/assets/{id}              资产详情
    PUT    /api/v1/assets/{id}              更新资产
    DELETE /api/v1/assets/{id}              下架资产
    POST   /api/v1/assets/{id}/subscribe    订阅资产
    GET    /api/v1/assets/{id}/subscriptions 资产订阅列表
    GET    /api/v1/assets/{id}/billing      计费记录
    GET    /api/v1/assets/{id}/usage        使用统计
"""
from __future__ import annotations

import uuid

import httpx
import pytest

# 健康检查与基础端点
HEALTH_PATH = "/api/v1/health"
ASSETS_PATH = "/api/v1/assets"
DEFAULT_TIMEOUT = 10.0


# ---------------------------------------------------------------------------
# 健康检查 & 基础冒烟
# ---------------------------------------------------------------------------
def test_health_check(asset_exchange_url):
    """健康检查返回 200 且 status=UP.

    端点：GET /api/v1/health
    """
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(asset_exchange_url + HEALTH_PATH)
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "UP"
    assert "version" in body


def test_openapi_schema(asset_exchange_url):
    """OpenAPI schema 可访问（FastAPI 自带 /openapi.json）."""
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(asset_exchange_url + "/openapi.json")
    assert resp.status_code == 200
    schema = resp.json()
    assert schema["info"]["title"] == "Asset Exchange Platform"


def test_list_assets_empty(asset_exchange_url):
    """浏览资产市场返回 200 且为列表（初始可能为空）.

    端点：GET /api/v1/assets
    """
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(asset_exchange_url + ASSETS_PATH)
    assert resp.status_code == 200
    assert isinstance(resp.json(), list)


# ---------------------------------------------------------------------------
# 资产 CRUD 流程
# ---------------------------------------------------------------------------
def _make_asset_payload() -> dict:
    """构造上架资产请求体."""
    return {
        "name": f"it-test-asset-{uuid.uuid4().hex[:8]}",
        "type": "table",
        "tenantId": "it-test-tenant",
        "description": "集成测试资产",
        "securityLevel": "internal",
        "qualityScore": 85,
        "updateFrequency": "daily",
        "tags": {"env": "it", "owner": "qa"},
        "pricing": {"mode": "by_call", "price": 0.0, "unit": "次"},
        "sourceRef": None,
    }


def test_create_asset(asset_exchange_url):
    """上架资产返回 201，响应含 id 与 name.

    端点：POST /api/v1/assets
    """
    payload = _make_asset_payload()
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.post(asset_exchange_url + ASSETS_PATH, json=payload)
    assert resp.status_code == 201, resp.text
    asset = resp.json()
    assert asset["name"] == payload["name"]
    assert asset["type"] == payload["type"]
    assert "id" in asset

    # 清理
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        client.delete(asset_exchange_url + f"{ASSETS_PATH}/{asset['id']}")


def test_get_asset(asset_exchange_url):
    """获取资产详情返回 200，字段一致.

    端点：GET /api/v1/assets/{id}
    """
    payload = _make_asset_payload()
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        created = client.post(asset_exchange_url + ASSETS_PATH, json=payload).json()
        resp = client.get(asset_exchange_url + f"{ASSETS_PATH}/{created['id']}")
        client.delete(asset_exchange_url + f"{ASSETS_PATH}/{created['id']}")

    assert resp.status_code == 200
    asset = resp.json()
    assert asset["id"] == created["id"]
    assert asset["name"] == payload["name"]


def test_get_asset_not_found(asset_exchange_url):
    """不存在的资产 id 返回 404."""
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(asset_exchange_url + f"{ASSETS_PATH}/non-existent-{uuid.uuid4().hex}")
    assert resp.status_code == 404


def test_update_asset(asset_exchange_url):
    """更新资产返回 200，字段已更新.

    端点：PUT /api/v1/assets/{id}
    """
    payload = _make_asset_payload()
    update = {"name": f"it-test-asset-updated-{uuid.uuid4().hex[:8]}", "qualityScore": 0.95}
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        created = client.post(asset_exchange_url + ASSETS_PATH, json=payload).json()
        resp = client.put(
            asset_exchange_url + f"{ASSETS_PATH}/{created['id']}", json=update
        )
        client.delete(asset_exchange_url + f"{ASSETS_PATH}/{created['id']}")

    assert resp.status_code == 200, resp.text
    asset = resp.json()
    assert asset["name"] == update["name"]
    assert asset["qualityScore"] == update["qualityScore"]


def test_delete_asset(asset_exchange_url):
    """下架资产返回 204.

    端点：DELETE /api/v1/assets/{id}
    """
    payload = _make_asset_payload()
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        created = client.post(asset_exchange_url + ASSETS_PATH, json=payload).json()
        resp = client.delete(asset_exchange_url + f"{ASSETS_PATH}/{created['id']}")

    assert resp.status_code == 204


def test_asset_usage(asset_exchange_url):
    """获取资产使用统计返回 200.

    端点：GET /api/v1/assets/{id}/usage
    """
    payload = _make_asset_payload()
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        created = client.post(asset_exchange_url + ASSETS_PATH, json=payload).json()
        resp = client.get(
            asset_exchange_url + f"{ASSETS_PATH}/{created['id']}/usage"
        )
        client.delete(asset_exchange_url + f"{ASSETS_PATH}/{created['id']}")

    assert resp.status_code == 200


def test_asset_billing(asset_exchange_url):
    """获取资产计费记录返回 200.

    端点：GET /api/v1/assets/{id}/billing
    """
    payload = _make_asset_payload()
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        created = client.post(asset_exchange_url + ASSETS_PATH, json=payload).json()
        resp = client.get(
            asset_exchange_url + f"{ASSETS_PATH}/{created['id']}/billing"
        )
        client.delete(asset_exchange_url + f"{ASSETS_PATH}/{created['id']}")

    assert resp.status_code == 200


def test_asset_subscriptions_empty(asset_exchange_url):
    """新上架资产的订阅列表为空.

    端点：GET /api/v1/assets/{id}/subscriptions
    """
    payload = _make_asset_payload()
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        created = client.post(asset_exchange_url + ASSETS_PATH, json=payload).json()
        resp = client.get(
            asset_exchange_url + f"{ASSETS_PATH}/{created['id']}/subscriptions"
        )
        client.delete(asset_exchange_url + f"{ASSETS_PATH}/{created['id']}")

    assert resp.status_code == 200
    assert isinstance(resp.json(), list)


def test_asset_crud_flow(asset_exchange_url):
    """端到端 CRUD 流程：上架 → 获取 → 更新 → 列表 → 下架."""
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        # 1. 上架
        payload = _make_asset_payload()
        created = client.post(asset_exchange_url + ASSETS_PATH, json=payload)
        assert created.status_code == 201
        asset_id = created.json()["id"]

        try:
            # 2. 获取
            got = client.get(asset_exchange_url + f"{ASSETS_PATH}/{asset_id}")
            assert got.status_code == 200
            assert got.json()["id"] == asset_id

            # 3. 更新
            updated = client.put(
                asset_exchange_url + f"{ASSETS_PATH}/{asset_id}",
                json={"description": "端到端更新"},
            )
            assert updated.status_code == 200

            # 4. 列表应包含该资产
            listed = client.get(asset_exchange_url + ASSETS_PATH)
            assert listed.status_code == 200
            ids = [a["id"] for a in listed.json()]
            assert asset_id in ids
        finally:
            # 5. 下架
            deleted = client.delete(
                asset_exchange_url + f"{ASSETS_PATH}/{asset_id}"
            )
            assert deleted.status_code == 204