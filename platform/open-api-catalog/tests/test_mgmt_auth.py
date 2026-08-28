"""管理面 JWT 鉴权测试：401 拦截 / 合法 token 放行 / health 豁免 / invoke 保持 AK/SK."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time

from fastapi.testclient import TestClient

SECRET = "oac-unit-test-secret-key-32b!"


def _enc(obj) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()


def make_token(
    secret: str = SECRET,
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
        base64.urlsafe_b64encode(hmac.new(secret.encode(), si.encode(), hashlib.sha256).digest()).rstrip(b"=").decode()
    )
    return f"{si}.{sig}"


def auth_headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def jwt_client(monkeypatch, app) -> TestClient:
    monkeypatch.setenv("AUTH_MODE", "jwt")
    monkeypatch.setenv("JWT_SECRET", SECRET)
    return TestClient(app)


def register_body(name: str = "mgmt-auth-api") -> dict:
    return {
        "name": name,
        "version": "1.0.0",
        "description": "管理面鉴权测试 API",
        "category": "test",
        "method": "GET",
        "path": "/mgmt-auth",
        "upstream": {
            "type": "trino",
            "url": "http://trino:8080/v1/statement",
            "method": "GET",
            "timeout": 30000,
            "retries": 2,
        },
        "providerTenantId": "tenant-provider",
    }


class TestManagementPlaneEnforcement:
    def test_list_apis_without_token_rejected(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        assert c.get("/api/v1/apis").status_code == 401

    def test_create_api_without_token_rejected(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        assert c.post("/api/v1/apis", json=register_body()).status_code == 401

    def test_list_subscriptions_without_token_rejected(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        assert c.get("/api/v1/subscriptions").status_code == 401

    def test_get_billing_without_token_rejected(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        assert c.get("/api/v1/apis/api-1/billing").status_code == 401

    def test_generate_without_token_rejected(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        assert c.post("/api/v1/apis/generate/sql", json={}).status_code == 401

    def test_metrics_without_token_rejected(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        assert c.get("/api/v1/apis/api-1/metrics").status_code == 401

    def test_expired_token_rejected(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        tok = make_token(exp=int(time.time()) - 10)
        assert c.get("/api/v1/apis", headers=auth_headers(tok)).status_code == 401

    def test_tampered_signature_rejected(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        tok = list(make_token())
        tok[0] = "A" if tok[0] != "A" else "B"
        assert c.get("/api/v1/apis", headers=auth_headers("".join(tok))).status_code == 401


class TestValidTokenAccess:
    def test_list_apis_with_valid_token(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        resp = c.get("/api/v1/apis", headers=auth_headers(make_token()))
        assert resp.status_code == 200
        assert isinstance(resp.json(), list)

    def test_create_api_with_valid_token(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        resp = c.post(
            "/api/v1/apis",
            json=register_body(),
            headers=auth_headers(make_token(role="admin")),
        )
        assert resp.status_code == 201, resp.text
        assert resp.json()["name"] == "mgmt-auth-api"


class TestHealthExempt:
    def test_health_exempt_in_jwt_mode(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        assert c.get("/api/v1/health").status_code == 200
        assert c.get("/api/v1/health").json()["status"] == "UP"

    def test_health_exempt_in_none_mode(self, app, monkeypatch) -> None:
        monkeypatch.setenv("AUTH_MODE", "none")
        c = TestClient(app)
        assert c.get("/api/v1/health").status_code == 200


class TestAuthModeNonePassthrough:
    def test_none_mode_allows_management_plane_anonymous(self, app, monkeypatch) -> None:
        monkeypatch.setenv("AUTH_MODE", "none")
        c = TestClient(app)
        assert c.get("/api/v1/apis").status_code == 200


class TestInvokeKeepsAkSk:
    def test_invoke_not_jwt_gated_in_jwt_mode(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        resp = c.post("/api/v1/apis/api-1/call", json={})
        assert resp.status_code == 401
        assert resp.json()["message"] == "缺少鉴权凭证: X-API-Key 或 Authorization"
