"""evaluation JWT 鉴权行为测试."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time

from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

from app.jwt_auth import AuthContext, getAuthContext

SECRET = "eval-unit-test-secret-key-32-bytes!!"


def _enc(obj) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()


def makeToken(secret=SECRET, sub="u1", tenant="t1", role="user", exp=None):
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


def _app(monkeypatch):
    monkeypatch.setenv("AUTH_MODE", "jwt")
    monkeypatch.setenv("JWT_SECRET", SECRET)
    app = FastAPI()

    @app.get("/w")
    def w(ctx: AuthContext = Depends(getAuthContext)):
        return {"userId": ctx.userId}

    return app


def test_missing_token_401(monkeypatch):
    c = TestClient(_app(monkeypatch))
    assert c.get("/w").status_code == 401


def test_valid_token_200(monkeypatch):
    c = TestClient(_app(monkeypatch))
    r = c.get("/w", headers={"Authorization": f"Bearer {makeToken()}"})
    assert r.status_code == 200
    assert r.json() == {"userId": "u1"}


def test_expired_401(monkeypatch):
    c = TestClient(_app(monkeypatch))
    tok = makeToken(exp=int(time.time()) - 5)
    assert c.get("/w", headers={"Authorization": f"Bearer {tok}"}).status_code == 401


def test_none_mode_default_open(monkeypatch):
    monkeypatch.setenv("AUTH_MODE", "none")
    monkeypatch.setenv("JWT_SECRET", "")
    app = FastAPI()

    @app.get("/w")
    def w(ctx: AuthContext = Depends(getAuthContext)):
        return {"ok": True}

    assert TestClient(app).get("/w").status_code == 200
