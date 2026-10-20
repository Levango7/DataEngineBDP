"""资产响应的租户字段名守卫（台账 #80）。

后端 MODEL-2（`ff939c6eb`，2026-08-06）把 `Asset.tenantId` 的入参写成
`validation_alias=AliasChoices("tenantId", "owner")`。`validation_alias` 只作用于
**入参校验**，不改序列化 ⇒ 响应体里只有 `tenantId`。主应用
`frontend/src/api/assetMarket.ts` 却按 `owner` 声明 `Asset`，运行时恒为 undefined：
资产卡片"提供方"空白，「我的资产」及其上的收益统计恒空（TS 接口挡不住服务端改名）。

本用例钉住服务端"响应体键集"这一侧；消费侧由
`frontend/src/views/__tests__/AssetMarket.tenantId.test.ts` 钉另一侧。
"""

from __future__ import annotations

from asset_exchange.models.asset import Asset

TENANT = "tenant-guard"


def _register(client) -> dict:
    """登记一条资产并返回响应体（走真实序列化，不手工构造 dict）。"""
    resp = client.post(
        "/api/v1/assets/register",
        json={
            "name": "guard-asset",
            "type": "table",
            "tenantId": TENANT,
            "securityLevel": "internal",
            "qualityScore": 80.0,
            "pricing": {"mode": "by_call", "price": 1.0, "unit": "call"},
        },
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


def test_register_response_key_set(client):
    body = _register(client)
    assert "tenantId" in body, f"响应体缺少 tenantId：{sorted(body)}"
    assert "owner" not in body, f"响应体不该出现 owner：{sorted(body)}"
    assert body["tenantId"] == TENANT


def _list_on_market(client, name: str) -> dict:
    """直接建一条已上架资产（`POST /api/v1/assets`）并返回响应体。

    `GET /api/v1/assets` 默认只返回已上架资产，用登记态（draft）做列表断言会空跑。
    """
    resp = client.post(
        "/api/v1/assets",
        json={
            "name": name,
            "type": "table",
            "tenantId": TENANT,
            "description": "守卫用例资产",
            "securityLevel": "internal",
            "qualityScore": 85.0,
            "pricing": {"mode": "by_call", "price": 2.0, "unit": "call"},
        },
    )
    assert resp.status_code == 201, resp.text
    return resp.json()


def test_list_assets_rows_carry_tenant_id(client):
    _list_on_market(client, "guard-listed")
    resp = client.get("/api/v1/assets", params={"limit": 500})
    assert resp.status_code == 200, resp.text
    rows = resp.json()
    assert rows, "Mock 仓储应至少能列出一条已上架资产，否则本用例是空跑"
    assert all("tenantId" in r for r in rows)
    assert not any("owner" in r for r in rows)


def test_get_asset_detail_carries_tenant_id(client):
    created = _register(client)
    resp = client.get(f"/api/v1/assets/{created['id']}")
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["tenantId"] == TENANT
    assert "owner" not in body


def test_openapi_schema_exposes_tenant_id():
    """OpenAPI 里也只见 tenantId——它是契约生成物与前端类型的共同真相源。"""
    props = Asset.model_json_schema()["properties"]
    assert "tenantId" in props
    assert "owner" not in props, "owner 只应是入参别名，不该出现在对外 schema"


def test_owner_still_accepted_as_input_alias(client):
    """向后兼容那半边也钉住：入参写 owner 仍须被收，否则旧客户端直接 422。"""
    resp = client.post(
        "/api/v1/assets/register",
        json={
            "name": "guard-legacy-owner",
            "type": "table",
            "owner": TENANT,
            "securityLevel": "internal",
            "qualityScore": 60.0,
            "pricing": {"mode": "by_call", "price": 1.0, "unit": "call"},
        },
    )
    assert resp.status_code == 201, resp.text
    assert resp.json()["tenantId"] == TENANT
