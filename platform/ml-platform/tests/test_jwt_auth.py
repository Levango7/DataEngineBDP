"""jwt_auth 依赖行为测试（ml-platform 侧，与 llmops 同规格）。"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time

import pytest
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

from ml_platform.api.jwt_auth import AuthContext, getAuthContext, loadAuthSettings

SECRET = "unit-test-secret-key-at-least-32-bytes!!"


def _enc(obj) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()


def makeToken(secret=SECRET, sub="u1", tenant="t1", role="user", exp=None, alg="HS256"):
    header = {"alg": alg, "typ": "JWT"}
    claims = {
        "iss": "shuqing-bigdata",
        "sub": sub,
        "tenantId": tenant,
        "role": role,
        "iat": int(time.time()),
        "exp": exp if exp is not None else int(time.time()) + 600,
    }
    si = f"{_enc(header)}.{_enc(claims)}"
    sig = base64.urlsafe_b64encode(
        hmac.new(secret.encode(), si.encode(), hashlib.sha256).digest()
    ).rstrip(b"=").decode()
    return f"{si}.{sig}"


@pytest.fixture()
def client(monkeypatch):
    monkeypatch.setenv("AUTH_MODE", "jwt")
    monkeypatch.setenv("JWT_SECRET", SECRET)
    app = FastAPI()

    @app.get("/whoami")
    def whoami(ctx: AuthContext = Depends(getAuthContext)):
        return {"userId": ctx.userId, "tenantId": ctx.tenantId, "role": ctx.role}

    return TestClient(app)


def test_missing_token_401(client):
    assert client.get("/whoami").status_code == 401


def test_valid_token_200(client):
    r = client.get("/whoami", headers={"Authorization": f"Bearer {makeToken()}"})
    assert r.status_code == 200
    assert r.json() == {"userId": "u1", "tenantId": "t1", "role": "user"}


def test_expired_401(client):
    tok = makeToken(exp=int(time.time()) - 10)
    assert client.get("/whoami", headers={"Authorization": f"Bearer {tok}"}).status_code == 401


def test_tampered_401(client):
    tok = list(makeToken())
    tok[0] = "A" if tok[0] != "A" else "B"
    r = client.get("/whoami", headers={"Authorization": f"Bearer {''.join(tok)}"})
    assert r.status_code == 401


def test_none_mode_anonymous(monkeypatch):
    monkeypatch.setenv("AUTH_MODE", "none")
    app = FastAPI()

    @app.get("/w")
    def w(ctx: AuthContext = Depends(getAuthContext)):
        return ctx.userId

    assert TestClient(app).get("/w").status_code == 200


def test_fail_fast_without_secret(monkeypatch):
    monkeypatch.setenv("AUTH_MODE", "jwt")
    monkeypatch.setenv("JWT_SECRET", "")
    with pytest.raises(RuntimeError):
        loadAuthSettings()


def test_effectiveTenant_rules():
    from ml_platform.api.jwt_auth import effectiveTenant

    admin = AuthContext(userId="a", tenantId="ta", role="admin")
    user = AuthContext(userId="u", tenantId="tu", role="user")
    assert effectiveTenant(user, "other") == "tu"
    assert effectiveTenant(admin, "other") == "other"
    assert effectiveTenant(user, None) == "tu"
