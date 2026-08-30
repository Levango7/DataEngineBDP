"""Business Portal（对内业务线门户, L5.4）集成测试.

被测组件：platform/business-portal（FastAPI, 默认端口 8088）
启动方式：cd platform/business-portal && BP_PORT=8088 python main.py

健康检查：GET /api/v1/health → {"status": "UP", "store": "...", "version": "0.1.0", "module": "business-portal"}
主要端点：
    POST   /api/v1/business-lines              创建业务线
    GET    /api/v1/business-lines               列出业务线
    GET    /api/v1/business-lines/{id}          业务线详情
    PUT    /api/v1/business-lines/{id}          更新业务线
    DELETE /api/v1/business-lines/{id}          删除业务线
    GET    /api/v1/dashboard                    仪表盘
    GET    /api/v1/workbench                    工作台
"""
from __future__ import annotations

import uuid

import httpx
import pytest

HEALTH_PATH = "/api/v1/health"
BL_PATH = "/api/v1/business-lines"
DEFAULT_TIMEOUT = 10.0


# ---------------------------------------------------------------------------
# 健康检查 & 基础冒烟
# ---------------------------------------------------------------------------
def test_health_check(business_portal_url):
    """健康检查返回 200 且 status=UP，module=business-portal.

    端点：GET /api/v1/health
    """
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(business_portal_url + HEALTH_PATH)
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "UP"
    assert body.get("module") == "business-portal"


def test_openapi_schema(business_portal_url):
    """OpenAPI schema 可访问."""
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(business_portal_url + "/openapi.json")
    assert resp.status_code == 200
    schema = resp.json()
    assert schema["info"]["title"] == "Business Portal"


def test_list_business_lines(business_portal_url):
    """列出业务线返回 200 且为列表.

    端点：GET /api/v1/business-lines
    """
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(
            business_portal_url + BL_PATH,
            headers={"X-Tenant-Id": "it-test-tenant"},
        )
    assert resp.status_code == 200
    assert isinstance(resp.json(), list)


# ---------------------------------------------------------------------------
# 业务线 CRUD
# ---------------------------------------------------------------------------
def _make_bl_payload() -> dict:
    """构造创建业务线请求体."""
    return {
        "name": f"it-test-bl-{uuid.uuid4().hex[:8]}",
        "tenantId": "it-test-tenant",
        "description": "集成测试业务线",
        "budget": {"total": 100000, "period": "monthly"},
        "config": {"settlement": "internal"},
        "ownerIds": ["it-tester"],
        "teamIds": [],
        "memberIds": [],
    }


def test_create_business_line(business_portal_url):
    """创建业务线返回 201，含 id 与 name.

    端点：POST /api/v1/business-lines
    """
    payload = _make_bl_payload()
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.post(business_portal_url + BL_PATH, json=payload)
    assert resp.status_code == 201, resp.text
    bl = resp.json()
    assert bl["name"] == payload["name"]
    assert "id" in bl

    # 清理
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        client.delete(business_portal_url + f"{BL_PATH}/{bl['id']}")


def test_get_business_line(business_portal_url):
    """获取业务线详情返回 200.

    端点：GET /api/v1/business-lines/{id}
    """
    payload = _make_bl_payload()
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        created = client.post(business_portal_url + BL_PATH, json=payload).json()
        resp = client.get(business_portal_url + f"{BL_PATH}/{created['id']}")
        client.delete(business_portal_url + f"{BL_PATH}/{created['id']}")

    assert resp.status_code == 200
    assert resp.json()["id"] == created["id"]


def test_get_business_line_not_found(business_portal_url):
    """不存在的业务线 id 返回 404."""
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        resp = client.get(
            business_portal_url + f"{BL_PATH}/non-existent-{uuid.uuid4().hex}"
        )
    assert resp.status_code == 404


def test_update_business_line(business_portal_url):
    """更新业务线返回 200，字段已更新.

    端点：PUT /api/v1/business-lines/{id}
    """
    payload = _make_bl_payload()
    update = {"description": "端到端更新业务线"}
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        created = client.post(business_portal_url + BL_PATH, json=payload).json()
        resp = client.put(
            business_portal_url + f"{BL_PATH}/{created['id']}", json=update
        )
        client.delete(business_portal_url + f"{BL_PATH}/{created['id']}")

    assert resp.status_code == 200, resp.text
    assert resp.json()["description"] == update["description"]


def test_delete_business_line(business_portal_url):
    """删除业务线返回 204.

    端点：DELETE /api/v1/business-lines/{id}
    """
    payload = _make_bl_payload()
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        created = client.post(business_portal_url + BL_PATH, json=payload).json()
        resp = client.delete(business_portal_url + f"{BL_PATH}/{created['id']}")

    assert resp.status_code == 204


def test_business_line_crud_flow(business_portal_url):
    """端到端 CRUD 流程：创建 → 获取 → 更新 → 列表 → 删除."""
    with httpx.Client(timeout=DEFAULT_TIMEOUT) as client:
        payload = _make_bl_payload()
        created = client.post(business_portal_url + BL_PATH, json=payload)
        assert created.status_code == 201
        bl_id = created.json()["id"]

        try:
            # 获取
            got = client.get(business_portal_url + f"{BL_PATH}/{bl_id}")
            assert got.status_code == 200

            # 更新
            updated = client.put(
                business_portal_url + f"{BL_PATH}/{bl_id}",
                json={"description": "e2e updated"},
            )
            assert updated.status_code == 200

            # 列表
            listed = client.get(
                business_portal_url + BL_PATH,
                headers={"X-Tenant-Id": payload["tenantId"]},
            )
            assert listed.status_code == 200
            ids = [b["id"] for b in listed.json()]
            assert bl_id in ids
        finally:
            deleted = client.delete(business_portal_url + f"{BL_PATH}/{bl_id}")
            assert deleted.status_code == 204