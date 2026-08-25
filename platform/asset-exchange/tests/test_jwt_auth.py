"""鉴权与租户裁决测试：401/403、claim 回填、admin 覆盖."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time

from fastapi.testclient import TestClient

SECRET = "ae-unit-test-secret-key-32b!!"


def _enc(obj) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()


def make_token(
    secret: str = SECRET,
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
        base64.urlsafe_b64encode(hmac.new(secret.encode(), si.encode(), hashlib.sha256).digest())
        .rstrip(b"=")
        .decode()
    )
    return f"{si}.{sig}"


def auth_headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def admin_token() -> str:
    return make_token(sub="root", tenant="platform-admin", role="admin")


def user_token(tenant: str = "tenant-b") -> str:
    return make_token(sub=f"user-{tenant}", tenant=tenant)


def jwt_client(monkeypatch, app) -> TestClient:
    monkeypatch.setenv("AUTH_MODE", "jwt")
    monkeypatch.setenv("JWT_SECRET", SECRET)
    return TestClient(app)


def list_asset_as_admin(c: TestClient, name: str = "asset-jwt", owner: str = "tenant-a") -> str:
    resp = c.post(
        "/api/v1/assets",
        json={"name": name, "type": "table", "owner": owner, "qualityScore": 85.0},
        headers=auth_headers(admin_token()),
    )
    assert resp.status_code == 201, resp.text
    return resp.json()["id"]


class TestAuthEnforcement:
    def test_health_exempt_in_jwt_mode(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        assert c.get("/api/v1/health").status_code == 200

    def test_assets_without_token_rejected(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        assert c.get("/api/v1/assets").status_code == 401

    def test_subscriptions_without_token_rejected(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        assert c.post("/api/v1/subscriptions/sub-1/approve", json={"action": "approve", "approverId": "x"}).status_code == 401

    def test_audit_logs_without_token_rejected(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        assert c.get("/api/v1/audit-logs").status_code == 401

    def test_expired_token_rejected(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        tok = make_token(exp=int(time.time()) - 10)
        assert c.get("/api/v1/assets", headers=auth_headers(tok)).status_code == 401

    def test_tampered_signature_rejected(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        tok = make_token()[:-4] + "AAAA"
        assert c.get("/api/v1/assets", headers=auth_headers(tok)).status_code == 401


class TestTenantResolution:
    def test_register_backfills_claim(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        resp = c.post(
            "/api/v1/assets/register",
            json={"name": "backfill-asset", "type": "table"},
            headers=auth_headers(user_token("tenant-b")),
        )
        assert resp.status_code == 201, resp.text
        assert resp.json()["tenantId"] == "tenant-b"

    def test_register_mismatch_rejected_for_user(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        resp = c.post(
            "/api/v1/assets/register",
            json={"name": "forge-asset", "type": "table", "tenantId": "tenant-x"},
            headers=auth_headers(user_token("tenant-b")),
        )
        assert resp.status_code == 403

    def test_admin_override_via_owner_alias(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = list_asset_as_admin(c, name="admin-override-asset", owner="tenant-A")
        resp = c.get(f"/api/v1/assets/{aid}", headers=auth_headers(admin_token()))
        assert resp.json()["tenantId"] == "tenant-A"

    def test_market_filter_other_tenant_rejected_for_user(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        resp = c.get("/api/v1/assets?tenantId=tenant-x", headers=auth_headers(user_token("tenant-b")))
        assert resp.status_code == 403

    def test_market_filter_own_tenant_allowed(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        list_asset_as_admin(c, name="own-filter-asset", owner="tenant-b")
        resp = c.get("/api/v1/assets?owner=tenant-b", headers=auth_headers(user_token("tenant-b")))
        assert resp.status_code == 200
        body = resp.json()
        assert len(body) >= 1
        assert all(a["tenantId"] == "tenant-b" for a in body)

    def test_audit_log_tenant_filter_guard(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        resp = c.get("/api/v1/audit-logs?tenantId=tenant-x", headers=auth_headers(user_token("tenant-b")))
        assert resp.status_code == 403


class TestSubscriberIdentity:
    def test_download_backfills_claim(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = list_asset_as_admin(c, name="dl-backfill", owner="tenant-a")
        resp = c.post(
            f"/api/v1/assets/{aid}/download",
            json={"rows": 10},
            headers=auth_headers(user_token("tenant-b")),
        )
        assert resp.status_code == 200, resp.text
        assert resp.json()["subscriberId"] == "tenant-b"

    def test_download_forged_subscriber_rejected(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = list_asset_as_admin(c, name="dl-forge", owner="tenant-a")
        resp = c.post(
            f"/api/v1/assets/{aid}/download",
            json={"subscriberId": "tenant-b"},
            headers=auth_headers(user_token("tenant-c")),
        )
        assert resp.status_code == 403

    def test_invoke_backfills_claim(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = list_asset_as_admin(c, name="inv-backfill", owner="tenant-a")
        resp = c.post(
            f"/api/v1/assets/{aid}/invoke",
            json={"params": {"q": 1}},
            headers=auth_headers(user_token("tenant-c")),
        )
        assert resp.status_code == 200, resp.text
        body = resp.json()
        assert body["subscriberId"] == "tenant-c"

    def test_invoke_forged_subscriber_rejected(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = list_asset_as_admin(c, name="inv-forge", owner="tenant-a")
        resp = c.post(
            f"/api/v1/assets/{aid}/invoke",
            json={"subscriberId": "tenant-z"},
            headers=auth_headers(user_token("tenant-c")),
        )
        assert resp.status_code == 403

    def test_subscribe_backfills_claim(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        aid = list_asset_as_admin(c, name="sub-backfill", owner="tenant-a")
        resp = c.post(
            f"/api/v1/assets/{aid}/subscribe",
            json={"period": "monthly"},
            headers=auth_headers(user_token("tenant-b")),
        )
        assert resp.status_code == 201, resp.text
        assert resp.json()["subscriberId"] == "tenant-b"


class TestAuthSettings:
    def test_fail_fast_without_secret(self, monkeypatch) -> None:
        from asset_exchange.api.jwt_auth import loadAuthSettings

        monkeypatch.setenv("AUTH_MODE", "jwt")
        monkeypatch.setenv("JWT_SECRET", "")
        raised = False
        try:
            loadAuthSettings()
        except RuntimeError:
            raised = True
        assert raised
