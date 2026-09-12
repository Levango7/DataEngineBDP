"""asset-exchange subscriptions/assets 认证与租户隔离测试.

覆盖：
    - get_asset 跨租户 403 / 本租户 200 / admin 200
    - list_subscriptions 非-admin 仅看本租户、伪造 subscriberId 403
    - get_delivery_status / list_subscription_billing 跨租户 403

业务约束：不允许订阅本租户资产，故资产 owner=tenant-a、订阅者=tenant-b，
跨租户验证用 tenant-c。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time

from fastapi.testclient import TestClient

SECRET = "ae-sub-asset-tenant-secret-32b!"


def _enc(obj) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()


def make_token(
    sub: str = "u1",
    tenant: str = "tenant-b",
    role: str = "user",
    exp: float | None = None,
) -> str:
    header = {"alg": "HS256", "typ": "JWT"}
    claims = {
        "iss": "shuqing-bigdata",
        "sub": sub,
        "tenantId": tenant,
        "role": role,
        "iat": int(time.time()),
        "exp": exp if exp is not None else int(time.time()) + 600,
    }
    si = f"{_enc(header)}.{_enc(claims)}"
    sig = (
        base64.urlsafe_b64encode(hmac.new(SECRET.encode(), si.encode(), hashlib.sha256).digest())
        .rstrip(b"=")
        .decode()
    )
    return f"{si}.{sig}"


def auth_headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def admin_headers() -> dict[str, str]:
    return auth_headers(make_token(sub="root", tenant="platform-admin", role="admin"))


def user_headers(tenant: str) -> dict[str, str]:
    return auth_headers(make_token(sub=f"user-{tenant}", tenant=tenant))


def jwt_client(monkeypatch, app) -> TestClient:
    monkeypatch.setenv("AUTH_MODE", "jwt")
    monkeypatch.setenv("JWT_SECRET", SECRET)
    return TestClient(app)


def list_asset_as_admin(c: TestClient, name: str, owner: str = "tenant-a") -> str:
    resp = c.post(
        "/api/v1/assets",
        json={"name": name, "type": "table", "owner": owner, "qualityScore": 85.0},
        headers=admin_headers(),
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


def activate_subscription(c: TestClient, asset_id: str, subscriber: str) -> str:
    """以 admin 订阅并审批通过（ACTIVE），返回 subscription_id."""
    resp = c.post(
        f"/api/v1/assets/{asset_id}/subscribe",
        json={"subscriberId": subscriber, "durationDays": 30},
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
    return sid


# 资产提供方租户 / 订阅方租户 / 跨租户旁观者
PROVIDER = "tenant-a"
SUBSCRIBER = "tenant-b"
OUTSIDER = "tenant-c"


class TestGetAssetTenantIsolation:
    """get_asset 租户隔离."""

    def test_get_asset_without_token_401(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        assert c.get("/api/v1/assets/some-id").status_code == 401

    def test_get_asset_cross_tenant_403(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = list_asset_as_admin(c, name="get-iso-asset", owner=PROVIDER)
        resp = c.get(f"/api/v1/assets/{aid}", headers=user_headers(OUTSIDER))
        assert resp.status_code == 403, resp.text

    def test_get_asset_own_tenant_200(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = list_asset_as_admin(c, name="get-own-asset", owner=PROVIDER)
        resp = c.get(f"/api/v1/assets/{aid}", headers=user_headers(PROVIDER))
        assert resp.status_code == 200, resp.text
        assert resp.json()["tenantId"] == PROVIDER

    def test_get_asset_admin_any_tenant_200(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = list_asset_as_admin(c, name="get-admin-asset", owner=PROVIDER)
        resp = c.get(f"/api/v1/assets/{aid}", headers=admin_headers())
        assert resp.status_code == 200, resp.text


class TestListSubscriptionsTenantIsolation:
    """list_subscriptions 租户隔离."""

    def test_list_subscriptions_without_token_401(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        assert c.get("/api/v1/asset-subscriptions").status_code == 401

    def test_list_subscriptions_forged_subscriber_403(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = list_asset_as_admin(c, name="list-forged-asset", owner=PROVIDER)
        activate_subscription(c, aid, SUBSCRIBER)
        # 旁观者伪造 subscriberId=SUBSCRIBER → 403
        resp = c.get(
            f"/api/v1/asset-subscriptions?subscriberId={SUBSCRIBER}",
            headers=user_headers(OUTSIDER),
        )
        assert resp.status_code == 403, resp.text

    def test_list_subscriptions_own_tenant_only(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = list_asset_as_admin(c, name="list-own-asset", owner=PROVIDER)
        activate_subscription(c, aid, SUBSCRIBER)
        # SUBSCRIBER 不传 subscriberId → 回填 ctx.tenantId，只返回自己的
        resp = c.get("/api/v1/asset-subscriptions", headers=user_headers(SUBSCRIBER))
        assert resp.status_code == 200, resp.text
        body = resp.json()
        assert all(s["subscriberId"] == SUBSCRIBER for s in body)


class TestDeliveryAndBillingTenantIsolation:
    """get_delivery_status / list_subscription_billing 租户隔离."""

    def test_get_delivery_status_cross_tenant_403(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = list_asset_as_admin(c, name="del-iso-asset", owner=PROVIDER)
        sid = activate_subscription(c, aid, SUBSCRIBER)
        resp = c.get(
            f"/api/v1/asset-subscriptions/{sid}/delivery-status",
            headers=user_headers(OUTSIDER),
        )
        assert resp.status_code == 403, resp.text

    def test_get_delivery_status_own_tenant_passes_tenant_gate(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = list_asset_as_admin(c, name="del-own-asset", owner=PROVIDER)
        sid = activate_subscription(c, aid, SUBSCRIBER)
        resp = c.get(
            f"/api/v1/asset-subscriptions/{sid}/delivery-status",
            headers=user_headers(SUBSCRIBER),
        )
        # 本租户通过租户门禁（未 403）；业务层可能因无交付记录返回 404，属正常
        assert resp.status_code != 403, resp.text

    def test_list_subscription_billing_cross_tenant_403(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = list_asset_as_admin(c, name="bill-iso-asset", owner=PROVIDER)
        sid = activate_subscription(c, aid, SUBSCRIBER)
        resp = c.get(
            f"/api/v1/asset-subscriptions/{sid}/billing",
            headers=user_headers(OUTSIDER),
        )
        assert resp.status_code == 403, resp.text

    def test_list_subscription_billing_own_tenant_200(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = list_asset_as_admin(c, name="bill-own-asset", owner=PROVIDER)
        sid = activate_subscription(c, aid, SUBSCRIBER)
        resp = c.get(
            f"/api/v1/asset-subscriptions/{sid}/billing",
            headers=user_headers(SUBSCRIBER),
        )
        assert resp.status_code == 200, resp.text
