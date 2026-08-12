"""Open API Catalog（开放 API 服务目录, L5.5）集成测试.

被测组件：platform/open-api-catalog（FastAPI, 默认端口 8090）
启动方式：cd platform/open-api-catalog && OPENAPI_CATALOG_PORT=8090 python main.py

健康检查：GET /api/v1/health → {"status": "UP", "store": "...", "version": "0.1.0", "module": "open-api-catalog"}
主要端点：
    POST   /api/v1/apis                  注册 API
    GET    /api/v1/apis                   浏览 API 目录
    GET    /api/v1/apis/{id}              API 详情
    PUT    /api/v1/apis/{id}              更新 API
    DELETE /api/v1/apis/{id}              下线 API
    POST   /api/v1/subscriptions          订阅 API
    GET    /api/v1/subscriptions           订阅列表
    POST   /api/v1/invoke/{apiId}         调用 API
"""
from __future__ import annotations

import uuid

import httpx
import pytest

HEALTH_PATH = "/api/v1/health"
APIS_PATH = "/api/v1/apis"
DEFAULT_TIMEOUT = 10.0


# ---------------------------------------------------------------------------
# 健康检查 & 基础冒烟
# ---------------------------------------------------------------------------
def test_health_check(open_api_catalog_url):
    """健康检查返回 200 且 status=UP，module=open-api-catalog.

    端点：GET /api/v1/health
    """
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(open_api_catalog_url + HEALTH_PATH)
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "UP"
    assert body.get("module") == "open-api-catalog"


def test_openapi_schema(open_api_catalog_url):
    """OpenAPI schema 可访问."""
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(open_api_catalog_url + "/openapi.json")
    assert resp.status_code == 200
    schema = resp.json()
    assert schema["info"]["title"] == "Open API Service Catalog"


def test_list_apis(open_api_catalog_url):
    """浏览 API 目录返回 200 且为列表.

    端点：GET /api/v1/apis
    """
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(open_api_catalog_url + APIS_PATH)
    assert resp.status_code == 200
    assert isinstance(resp.json(), list)


# ---------------------------------------------------------------------------
# API 注册 CRUD
# ---------------------------------------------------------------------------
def _make_api_payload() -> dict:
    """构造注册 API 请求体."""
    return {
        "name": f"it-test-api-{uuid.uuid4().hex[:8]}",
        "version": "1.0.0",
        "description": "集成测试 API",
        "category": "data",
        "tags": ["it", "test"],
        "method": "GET",
        "path": "/api/v1/test",
        "params": [],
        "authType": "api_key",
        "upstream": {
            "type": "http",
            "url": "http://upstream:8080/api/v1/data",
            "method": "GET",
            "timeout": 30000,
        },
        "sla": "silver",
        "costStrategy": "by_call",
        "costUnitPrice": 0.01,
        "monthlyQuota": 1000,
        "status": "draft",
        "providerTenantId": "it-test-tenant",
    }


def test_register_api(open_api_catalog_url):
    """注册 API 返回 201，含 id 与 name.

    端点：POST /api/v1/apis
    """
    payload = _make_api_payload()
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.post(open_api_catalog_url + APIS_PATH, json=payload)
    assert resp.status_code == 201, resp.text
    api = resp.json()
    assert api["name"] == payload["name"]
    assert "id" in api

    # 清理
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        client.delete(open_api_catalog_url + f"{APIS_PATH}/{api['id']}")


def test_get_api(open_api_catalog_url):
    """获取 API 详情返回 200.

    端点：GET /api/v1/apis/{id}
    """
    payload = _make_api_payload()
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        created = client.post(open_api_catalog_url + APIS_PATH, json=payload).json()
        resp = client.get(open_api_catalog_url + f"{APIS_PATH}/{created['id']}")
        client.delete(open_api_catalog_url + f"{APIS_PATH}/{created['id']}")

    assert resp.status_code == 200
    assert resp.json()["id"] == created["id"]


def test_get_api_not_found(open_api_catalog_url):
    """不存在的 API id 返回 404."""
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(
            open_api_catalog_url + f"{APIS_PATH}/non-existent-{uuid.uuid4().hex}"
        )
    assert resp.status_code == 404


def test_update_api(open_api_catalog_url):
    """更新 API 返回 200，字段已更新.

    端点：PUT /api/v1/apis/{id}
    """
    payload = _make_api_payload()
    update = {"description": "端到端更新 API"}
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        created = client.post(open_api_catalog_url + APIS_PATH, json=payload).json()
        resp = client.put(
            open_api_catalog_url + f"{APIS_PATH}/{created['id']}", json=update
        )
        client.delete(open_api_catalog_url + f"{APIS_PATH}/{created['id']}")

    assert resp.status_code == 200, resp.text
    assert resp.json()["description"] == update["description"]


def test_delete_api(open_api_catalog_url):
    """下线 API 返回 204.

    端点：DELETE /api/v1/apis/{id}
    """
    payload = _make_api_payload()
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        created = client.post(open_api_catalog_url + APIS_PATH, json=payload).json()
        resp = client.delete(open_api_catalog_url + f"{APIS_PATH}/{created['id']}")

    assert resp.status_code == 204


def test_api_crud_flow(open_api_catalog_url):
    """端到端 CRUD 流程：注册 → 获取 → 更新 → 列表 → 下线."""
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        payload = _make_api_payload()
        created = client.post(open_api_catalog_url + APIS_PATH, json=payload)
        assert created.status_code == 201
        api_id = created.json()["id"]

        try:
            # 获取
            got = client.get(open_api_catalog_url + f"{APIS_PATH}/{api_id}")
            assert got.status_code == 200

            # 更新
            updated = client.put(
                open_api_catalog_url + f"{APIS_PATH}/{api_id}",
                json={"description": "e2e updated"},
            )
            assert updated.status_code == 200

            # 列表
            listed = client.get(open_api_catalog_url + APIS_PATH)
            assert listed.status_code == 200
            ids = [a["id"] for a in listed.json()]
            assert api_id in ids
        finally:
            deleted = client.delete(open_api_catalog_url + f"{APIS_PATH}/{api_id}")
            assert deleted.status_code == 204