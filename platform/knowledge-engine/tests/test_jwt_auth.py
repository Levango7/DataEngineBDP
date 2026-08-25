"""鉴权测试：401/403 路径与原生 nGQL 端点收紧."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time

from fastapi.testclient import TestClient

from knowledge_engine.api.jwt_auth import loadAuthSettings

SECRET = "ke-unit-test-secret-key-32b!!"


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
        base64.urlsafe_b64encode(hmac.new(secret.encode(), si.encode(), hashlib.sha256).digest())
        .rstrip(b"=")
        .decode()
    )
    return f"{si}.{sig}"


def auth_headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def jwt_client(monkeypatch, app) -> TestClient:
    monkeypatch.setenv("AUTH_MODE", "jwt")
    monkeypatch.setenv("JWT_SECRET", SECRET)
    return TestClient(app)


class TestAuthEnforcement:
    def test_health_exempt(self, client: TestClient) -> None:
        resp = client.get("/health")
        assert resp.status_code == 200

    def test_create_space_without_token_rejected(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        assert c.post("/api/v1/spaces", json={"name": "kg1", "schema": {}}).status_code == 401

    def test_list_spaces_without_token_rejected(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        assert c.get("/api/v1/spaces").status_code == 401

    def test_create_space_with_token(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        resp = c.post("/api/v1/spaces", json={"name": "kg1", "schema": {}}, headers=auth_headers(make_token()))
        assert resp.status_code == 201

    def test_expired_token_rejected(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        tok = make_token(exp=int(time.time()) - 10)
        assert c.get("/api/v1/spaces", headers=auth_headers(tok)).status_code == 401

    def test_tampered_signature_rejected(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        tok = make_token()[:-4] + "AAAA"
        assert c.delete("/api/v1/spaces/kg1", headers=auth_headers(tok)).status_code == 401

    def test_non_bearer_scheme_rejected(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        tok = make_token()
        assert c.get("/api/v1/spaces", headers={"Authorization": f"Basic {tok}"}).status_code == 401


class TestRawQueryEndpoint:
    def test_anonymous_mode_forbidden(self, client: TestClient, monkeypatch) -> None:
        monkeypatch.setenv("AUTH_MODE", "none")
        assert client.post("/api/v1/spaces", json={"name": "kg1", "schema": {}}).status_code == 201
        resp = client.post("/api/v1/spaces/kg1/query", json={"nql": "DROP SPACE kg1"})
        assert resp.status_code == 403

    def test_authenticated_query_allowed(self, app, monkeypatch) -> None:
        c = jwt_client(monkeypatch, app)
        headers = auth_headers(make_token())
        assert c.post("/api/v1/spaces", json={"name": "kg1", "schema": {}}, headers=headers).status_code == 201
        assert (
            c.post(
                "/api/v1/spaces/kg1/entities",
                json={"entities": [{"id": "v1", "name": "x", "type": "Person"}]},
                headers=headers,
            ).status_code
            == 200
        )
        resp = c.post("/api/v1/spaces/kg1/query", json={"nql": "MATCH (v) RETURN v"}, headers=headers)
        assert resp.status_code == 200
        body = resp.json()
        assert body["columns"] == ["v"]
        assert len(body["rows"]) >= 1


class TestAuthSettings:
    def test_fail_fast_without_secret(self, monkeypatch) -> None:
        monkeypatch.setenv("AUTH_MODE", "jwt")
        monkeypatch.setenv("JWT_SECRET", "")
        raised = False
        try:
            loadAuthSettings()
        except RuntimeError:
            raised = True
        assert raised

    def test_invalid_mode_rejected(self, monkeypatch) -> None:
        monkeypatch.setenv("AUTH_MODE", "openid")
        raised = False
        try:
            loadAuthSettings()
        except RuntimeError:
            raised = True
        assert raised
