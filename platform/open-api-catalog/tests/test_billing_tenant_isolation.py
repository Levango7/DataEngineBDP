"""billing 路由认证与租户隔离测试.

覆盖：
    - get_key_info / get_rate_limit / get_billing 未认证 401
    - 跨租户访问 403（非 admin 访问他租户订阅/API）
    - admin 可访问任意租户
    - 限流配置持久化：configure 后 get 能读回
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time

from fastapi.testclient import TestClient

SECRET = "oac-billing-tenant-secret-32b!"


def _enc(obj) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()


def make_token(
    sub: str = "u1",
    tenant: str = "tenant-a",
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
        base64.urlsafe_b64encode(hmac.new(SECRET.encode(), si.encode(), hashlib.sha256).digest()).rstrip(b"=").decode()
    )
    return f"{si}.{sig}"


def auth_headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def admin_headers() -> dict[str, str]:
    return auth_headers(make_token(sub="root", tenant="platform-admin", role="admin"))


def jwt_client(monkeypatch, app) -> TestClient:
    monkeypatch.setenv("AUTH_MODE", "jwt")
    monkeypatch.setenv("JWT_SECRET", SECRET)
    return TestClient(app)


def _register_api(c: TestClient, name: str, provider_tenant: str = "tenant-a") -> str:
    resp = c.post(
        "/api/v1/apis",
        json={
            "name": name,
            "version": "1.0.0",
            "method": "GET",
            "path": f"/{name}",
            "upstream": {"type": "trino", "url": "http://trino:8080", "method": "GET"},
            "providerTenantId": provider_tenant,
        },
        headers=admin_headers(),
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


def _subscribe_and_approve(c: TestClient, api_id: str, subscriber_tenant: str = "tenant-a") -> str:
    resp = c.post(
        f"/api/v1/apis/{api_id}/subscribe",
        json={
            "subscriberId": f"sub-{subscriber_tenant}",
            "subscriberTenantId": subscriber_tenant,
            "purpose": "租户隔离测试",
            "quotaExpect": 10,
        },
        headers=admin_headers(),
    )
    assert resp.status_code == 201, resp.text
    sub_id = resp.json()["id"]
    resp = c.post(
        f"/api/v1/subscriptions/{sub_id}/approve",
        json={"approve": True, "grantedQuota": 10, "approver": "root"},
        headers=admin_headers(),
    )
    assert resp.status_code == 200, resp.text
    return sub_id


class TestBillingEndpointsAuthN:
    """未认证请求一律 401."""

    def test_get_key_info_without_token_401(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        assert c.get("/api/v1/subscriptions/sub-x/keys").status_code == 401

    def test_get_rate_limit_without_token_401(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        assert c.get("/api/v1/subscriptions/sub-x/rate-limit").status_code == 401

    def test_get_billing_without_token_401(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        assert c.get("/api/v1/apis/api-x/billing").status_code == 401


class TestBillingTenantIsolation:
    """非 admin 仅可访问本租户订阅/API 的计费配置."""

    def test_get_key_info_cross_tenant_403(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        api_id = _register_api(c, "key-iso-api", provider_tenant="tenant-a")
        sub_id = _subscribe_and_approve(c, api_id, subscriber_tenant="tenant-a")
        # tenant-b user 访问 tenant-a 的订阅 Key 信息 → 403
        resp = c.get(
            f"/api/v1/subscriptions/{sub_id}/keys",
            headers=auth_headers(make_token(tenant="tenant-b")),
        )
        assert resp.status_code == 403, resp.text

    def test_get_key_info_own_tenant_200(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        api_id = _register_api(c, "key-own-api", provider_tenant="tenant-a")
        sub_id = _subscribe_and_approve(c, api_id, subscriber_tenant="tenant-a")
        resp = c.get(
            f"/api/v1/subscriptions/{sub_id}/keys",
            headers=auth_headers(make_token(tenant="tenant-a")),
        )
        assert resp.status_code == 200, resp.text

    def test_get_rate_limit_cross_tenant_403(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        api_id = _register_api(c, "rl-iso-api", provider_tenant="tenant-a")
        sub_id = _subscribe_and_approve(c, api_id, subscriber_tenant="tenant-a")
        resp = c.get(
            f"/api/v1/subscriptions/{sub_id}/rate-limit",
            headers=auth_headers(make_token(tenant="tenant-b")),
        )
        assert resp.status_code == 403, resp.text

    def test_get_billing_cross_tenant_403(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        api_id = _register_api(c, "bill-iso-api", provider_tenant="tenant-a")
        # tenant-b user 访问 tenant-a 的 API 计费配置 → 403
        resp = c.get(
            f"/api/v1/apis/{api_id}/billing",
            headers=auth_headers(make_token(tenant="tenant-b")),
        )
        assert resp.status_code == 403, resp.text

    def test_get_billing_own_tenant_200(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        api_id = _register_api(c, "bill-own-api", provider_tenant="tenant-a")
        resp = c.get(
            f"/api/v1/apis/{api_id}/billing",
            headers=auth_headers(make_token(tenant="tenant-a")),
        )
        assert resp.status_code == 200, resp.text

    def test_admin_can_access_any_tenant(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        api_id = _register_api(c, "admin-any-api", provider_tenant="tenant-a")
        sub_id = _subscribe_and_approve(c, api_id, subscriber_tenant="tenant-a")
        # admin 访问任意租户 → 200
        assert c.get(f"/api/v1/subscriptions/{sub_id}/keys", headers=admin_headers()).status_code == 200
        assert c.get(f"/api/v1/subscriptions/{sub_id}/rate-limit", headers=admin_headers()).status_code == 200
        assert c.get(f"/api/v1/apis/{api_id}/billing", headers=admin_headers()).status_code == 200


class TestRateLimitPersistence:
    """限流配置持久化：configure 后 get 能读回（mock 模式内存单例）."""

    def test_configure_then_get_roundtrip(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        api_id = _register_api(c, "persistence-api", provider_tenant="tenant-a")
        sub_id = _subscribe_and_approve(c, api_id, subscriber_tenant="tenant-a")
        # admin 配置限流
        resp = c.put(
            f"/api/v1/subscriptions/{sub_id}/rate-limit",
            json={"qps": 50, "concurrent": 10, "burst": 20},
            headers=admin_headers(),
        )
        assert resp.status_code == 200, resp.text
        # 查回应能读回
        resp = c.get(f"/api/v1/subscriptions/{sub_id}/rate-limit", headers=admin_headers())
        assert resp.status_code == 200, resp.text
        body = resp.json()
        assert body["qps"] == 50
        assert body["concurrent"] == 10
        assert body["burst"] == 20
