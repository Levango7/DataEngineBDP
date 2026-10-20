"""asset-exchange 资产侧读取端点的租户门禁（2026-10-21 收编 C1 期间发现并修复）.

现象：`GET /assets/{id}/{settlements,billing,subscriptions,usage}` 四个 handler 原本
**不接收 AuthContext、也不查资产归属**（router 级只有 `Depends(getAuthContext)` 认证门槛），
任何持合法 JWT 的租户都能读到别家资产的钱与订阅名册。

对照证据（本机起 AUTH_MODE=jwt 的真服务，两个自签 HS256 token）：
    tenant-B(role=user) GET /assets/<tenant-A 的资产>/settlements
      -> 200 [{"tenantId": "tenant-A", "period": "2026-10", ...}]   # 修复前
    同身份 GET /settlements（租户级端点）
      -> 200 {"tenant": "tenant-B", "count": 0, "settlements": []}  # 一直是正确的

即：租户级端点会按 claim 过滤，资产级端点完全不设防。本文件钉住修复后的三态
（旁观 403 / 本方 200 且有真实数据 / admin 200），并要求"本方 200"这一支
断言非空——否则门禁可以在零数据夹具下永远绿。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time

from fastapi.testclient import TestClient

SECRET = "ae-asset-read-gate-secret-32b!"

PROVIDER = "tenant-a"  # 资产提供方
SUBSCRIBER = "tenant-b"  # 订阅方（会触发 invoke 计费）
OUTSIDER = "tenant-c"  # 跨租户旁观者

READ_PATHS = (
    "settlements",
    "billing",
    "subscriptions",
    "usage",
)


def _enc(obj) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()


def make_token(sub: str, tenant: str, role: str = "user") -> str:
    header = {"alg": "HS256", "typ": "JWT"}
    claims = {
        "iss": "shuqing-bigdata",
        "sub": sub,
        "tenantId": tenant,
        "role": role,
        "iat": int(time.time()),
        "exp": int(time.time()) + 600,
    }
    si = f"{_enc(header)}.{_enc(claims)}"
    sig = (
        base64.urlsafe_b64encode(hmac.new(SECRET.encode(), si.encode(), hashlib.sha256).digest()).rstrip(b"=").decode()
    )
    return f"{si}.{sig}"


def admin_headers() -> dict[str, str]:
    return {"Authorization": f"Bearer {make_token('root', 'platform-admin', 'admin')}"}


def user_headers(tenant: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {make_token(f'user-{tenant}', tenant)}"}


def jwt_client(monkeypatch, app) -> TestClient:
    monkeypatch.setenv("AUTH_MODE", "jwt")
    monkeypatch.setenv("JWT_SECRET", SECRET)
    return TestClient(app)


def seed_asset_with_money(c: TestClient, name: str) -> str:
    """造出一个"真的有流水"的资产：上架 → 订阅并审批 → 调用计费 → 出结算单.

    每一步都断言 HTTP 状态，避免夹具静默退化成空数据、让下游 403 断言变得毫无意义。
    """
    resp = c.post(
        "/api/v1/assets",
        json={
            "name": name,
            "type": "table",
            "owner": PROVIDER,
            "qualityScore": 88.0,
            # 默认定价是 by_call/price=0 ⇒ charge 出来金额为 0，"金额非空"这类断言会在零价夹具上失真
            "pricing": {"mode": "by_call", "price": 1.5, "unit": "次"},
        },
        headers=admin_headers(),
    )
    assert resp.status_code == 201, resp.text
    aid = resp.json()["id"]

    resp = c.post(
        f"/api/v1/assets/{aid}/subscribe",
        json={"subscriberId": SUBSCRIBER, "durationDays": 30},
        headers=admin_headers(),
    )
    assert resp.status_code == 201, resp.text
    sid = resp.json()["id"]
    resp = c.post(
        f"/api/v1/asset-subscriptions/{sid}/approve",
        json={"action": "approve"},
        headers=admin_headers(),
    )
    assert resp.status_code == 200, resp.text

    resp = c.post(
        f"/api/v1/assets/{aid}/invoke",
        json={"subscriberId": SUBSCRIBER, "params": {"limit": 10}},
        headers=admin_headers(),
    )
    assert resp.status_code == 200, resp.text

    # 计费记录只由 `POST /asset-subscriptions/{sid}/charge` 产生（billingService.charge 的唯一调用点，
    # subscriptions.py:221）；invoke 不落账，所以必须显式打这一刀，否则 billing 支会变空表。
    resp = c.post(
        f"/api/v1/asset-subscriptions/{sid}/charge",
        json={"usage": 10, "period": "2026-10"},
        headers=admin_headers(),
    )
    assert resp.status_code == 200, resp.text

    resp = c.post(
        f"/api/v1/assets/{aid}/settle",
        json={"period": "2026-10"},
        headers=admin_headers(),
    )
    assert resp.status_code == 200, resp.text
    return aid


class TestAssetReadEndpointsTenantGate:
    def test_all_four_cross_tenant_403(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = seed_asset_with_money(c, "read-gate-403")
        for suffix in READ_PATHS:
            resp = c.get(f"/api/v1/assets/{aid}/{suffix}", headers=user_headers(OUTSIDER))
            assert resp.status_code == 403, f"{suffix}: {resp.status_code} {resp.text}"

    def test_all_four_without_token_401(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = seed_asset_with_money(c, "read-gate-401")
        for suffix in READ_PATHS:
            assert c.get(f"/api/v1/assets/{aid}/{suffix}").status_code == 401, suffix

    def test_owner_sees_real_rows_not_empty_shell(self, app, monkeypatch) -> None:
        """本方 200 且数据非空——证明确实有东西被门禁挡住了，而不是在空表上比划."""
        c = jwt_client(monkeypatch, app)
        aid = seed_asset_with_money(c, "read-gate-owner")

        resp = c.get(f"/api/v1/assets/{aid}/settlements", headers=user_headers(PROVIDER))
        assert resp.status_code == 200, resp.text
        rows = resp.json()
        assert len(rows) >= 1, rows
        assert rows[0]["tenantId"] == PROVIDER
        assert rows[0]["period"] == "2026-10"

        resp = c.get(f"/api/v1/assets/{aid}/subscriptions", headers=user_headers(PROVIDER))
        assert resp.status_code == 200, resp.text
        assert [s["subscriberId"] for s in resp.json()] == [SUBSCRIBER]

        resp = c.get(f"/api/v1/assets/{aid}/billing", headers=user_headers(PROVIDER))
        assert resp.status_code == 200, resp.text
        assert resp.json()["assetId"] == aid
        assert resp.json()["recordCount"] >= 1, resp.json()
        assert resp.json()["totalAmount"] > 0, resp.json()

        resp = c.get(f"/api/v1/assets/{aid}/usage", headers=user_headers(PROVIDER))
        assert resp.status_code == 200, resp.text
        assert resp.json()["subscriberCount"] >= 1, resp.json()

    def test_admin_still_reads_any_tenant(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = seed_asset_with_money(c, "read-gate-admin")
        for suffix in READ_PATHS:
            resp = c.get(f"/api/v1/assets/{aid}/{suffix}", headers=admin_headers())
            assert resp.status_code == 200, f"{suffix}: {resp.text}"

    def test_unknown_asset_404_before_any_leak(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        for suffix in READ_PATHS:
            resp = c.get(f"/api/v1/assets/no-such-asset/{suffix}", headers=user_headers(OUTSIDER))
            assert resp.status_code == 404, f"{suffix}: {resp.status_code} {resp.text}"


class TestSettlementsEndpointIsNotTheSameThing:
    """租户级 GET /settlements 一直是按 claim 过滤的正例；这里钉住它没被这次改动带跑."""

    def test_tenant_scoped_list_filters_by_claim(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = seed_asset_with_money(c, "read-gate-scoped")
        resp = c.get("/api/v1/settlements", headers=user_headers(OUTSIDER))
        assert resp.status_code == 200, resp.text
        body = resp.json()
        assert body["tenant"] == OUTSIDER
        assert body["settlements"] == []

        resp = c.get("/api/v1/settlements", headers=user_headers(PROVIDER))
        assert resp.status_code == 200, resp.text
        body = resp.json()
        assert body["count"] >= 1, body
        assert [s["assetId"] for s in body["settlements"]] == [aid], body
