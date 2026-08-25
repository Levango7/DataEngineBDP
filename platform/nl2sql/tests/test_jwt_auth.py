"""nl2sql 鉴权与租户裁决测试."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time

from fastapi.testclient import TestClient

from jwt_auth import AuthContext, effectiveTenant, loadAuthSettings

SECRET = "nl2sql-unit-test-secret-key-32bytes!!"


def _enc(obj) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()


def makeToken(secret=SECRET, sub="u1", tenant="tenant-a", role="user", exp=None):
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


def _jwtClient(monkeypatch, app):
    monkeypatch.setenv("AUTH_MODE", "jwt")
    monkeypatch.setenv("JWT_SECRET", SECRET)
    return TestClient(app)


def test_execute_requires_token(app, monkeypatch):
    c = _jwtClient(monkeypatch, app)
    assert c.post("/api/v1/nl2sql/execute", json={"query": "查询全部用户"}).status_code == 401


def test_generate_with_token(app, monkeypatch):
    c = _jwtClient(monkeypatch, app)
    r = c.post(
        "/api/v1/nl2sql/generate",
        json={"query": "统计订单总数"},
        headers={"Authorization": f"Bearer {makeToken()}"},
    )
    assert r.status_code == 200


def test_expired_rejected(app, monkeypatch):
    c = _jwtClient(monkeypatch, app)
    tok = makeToken(exp=int(time.time()) - 10)
    r = c.post(
        "/api/v1/nl2sql/generate",
        json={"query": "统计订单总数"},
        headers={"Authorization": f"Bearer {tok}"},
    )
    assert r.status_code == 401


def test_fail_fast_without_secret(monkeypatch):
    monkeypatch.setenv("AUTH_MODE", "jwt")
    monkeypatch.setenv("JWT_SECRET", "")
    try:
        loadAuthSettings()
        raised = False
    except RuntimeError:
        raised = True
    assert raised


def test_effectiveTenant_rules():
    admin = AuthContext(userId="a", tenantId="ta", role="admin")
    user = AuthContext(userId="u", tenantId="tu", role="user")
    assert effectiveTenant(user, "other") == "tu"
    assert effectiveTenant(admin, "other") == "other"
