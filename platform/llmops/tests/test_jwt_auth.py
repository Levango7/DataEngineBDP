"""jwt_auth 依赖行为测试。

覆盖：
- AUTH_MODE=none 匿名放行
- AUTH_MODE=jwt：缺 token 401 / 合法 token 200 / 过期 401 /
  篡改签名 401 / alg=none 401 / 错误密钥 401
- loadAuthSettings fail-fast（jwt 模式缺密钥）
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time

import pytest
from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

from llmops.api.jwt_auth import AuthContext, getAuthContext, loadAuthSettings

SECRET = "unit-test-secret-key-at-least-32-bytes!!"


def _enc(obj) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()


def makeToken(
    secret: str = SECRET,
    sub: str = "u1",
    tenant: str = "t1",
    role: str = "user",
    exp: float | None = None,
    alg: str = "HS256",
    tamper: bool = False,
) -> str:
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
    if tamper:
        sig = ("A" if sig[0] != "A" else "B") + sig[1:]
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


def test_jwt_mode_missing_token_401(client):
    assert client.get("/whoami").status_code == 401


def test_jwt_mode_valid_token(client):
    r = client.get(
        "/whoami", headers={"Authorization": f"Bearer {makeToken()}"}
    )
    assert r.status_code == 200
    body = r.json()
    assert body == {"userId": "u1", "tenantId": "t1", "role": "user"}


def test_jwt_mode_expired_token_401(client):
    tok = makeToken(exp=int(time.time()) - 10)
    r = client.get("/whoami", headers={"Authorization": f"Bearer {tok}"})
    assert r.status_code == 401


def test_jwt_mode_tampered_signature_401(client):
    r = client.get(
        "/whoami",
        headers={"Authorization": f"Bearer {makeToken(tamper=True)}"},
    )
    assert r.status_code == 401


def test_jwt_mode_alg_none_401(client):
    r = client.get(
        "/whoami", headers={"Authorization": f"Bearer {makeToken(alg='none')}"}
    )
    assert r.status_code == 401


def test_jwt_mode_wrong_secret_401(client):
    r = client.get(
        "/whoami",
        headers={"Authorization": f"Bearer {makeToken(secret='another-secret-key-at-least-32b!')}"}
    )
    assert r.status_code == 401


def test_none_mode_anonymous_pass(monkeypatch):
    monkeypatch.setenv("AUTH_MODE", "none")
    app = FastAPI()

    @app.get("/whoami")
    def whoami(ctx: AuthContext = Depends(getAuthContext)):
        return {"userId": ctx.userId, "role": ctx.role}

    c = TestClient(app)
    r = c.get("/whoami")
    assert r.status_code == 200
    assert r.json()["userId"] == "anonymous"


def test_load_auth_settings_fail_fast_without_secret(monkeypatch):
    monkeypatch.setenv("AUTH_MODE", "jwt")
    monkeypatch.setenv("JWT_SECRET", "")
    with pytest.raises(RuntimeError):
        loadAuthSettings()


def test_load_auth_settings_invalid_mode(monkeypatch):
    monkeypatch.setenv("AUTH_MODE", "bogus")
    with pytest.raises(RuntimeError):
        loadAuthSettings()
