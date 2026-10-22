"""`resolve_tenant` 在无租户上下文时必须 fail-closed 成 4xx，而不是 500。

复现来自 C1b 的资产登记页：`AUTH_MODE=none` 下 JWT claim 的 tenantId 是空串，
而 `Asset.tenantId` 有 `min_length=1`。登记请求不带 tenantId 时，旧实现把 ''
直接交给模型构造 ⇒ pydantic ValidationError ⇒ 全局兜到 **500**，回显
`"1 validation error for Asset / tenantId / String should have at least 1 character"`
这种内部文本（`client.ts` 又优先读 `error` 字段，用户看到的是 "internal_error"）。
现在按 `settlements.py` 既有口径返回 401「缺少租户上下文」。
"""

from __future__ import annotations


def _register(client, body: dict):
    return client.post("/api/v1/assets/register", json=body)


def test_register_without_any_tenant_context_is_401_not_500(client):
    resp = _register(client, {"name": "no-tenant", "type": "table", "qualityScore": 80.0})
    assert resp.status_code == 401, resp.text
    body = resp.json()
    assert "租户" in body["message"]
    # 不许再把 pydantic 的内部校验文本回显给调用方
    assert "validation error" not in body["message"]


def test_register_with_explicit_tenant_still_works(client):
    resp = _register(
        client,
        {"name": "with-tenant", "type": "table", "tenantId": "tenant-x", "qualityScore": 80.0},
    )
    assert resp.status_code == 201, resp.text
    assert resp.json()["tenantId"] == "tenant-x"
