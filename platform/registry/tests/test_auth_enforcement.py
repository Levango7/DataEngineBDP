"""鉴权强制测试：AUTH_MODE=jwt 下业务端点必须携带有效 Bearer token.

修复前：registry 全部端点无鉴权，任何调用方可注册模型/创建部署。
修复后：身份强制来自 JWT（对齐平台 jwt_auth 镜像模式）。
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest

from app.api.registry_routes import SimpleModelRegistry, create_router
from app.core.deployment_manager import DeploymentManager
from app.core.health_checker import HealthChecker

SECRET = "unit-test-secret-key-at-least-32-bytes!!"


def _enc(obj) -> str:
    return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()


def make_jwt(
    secret: str = SECRET,
    sub: str = "u1",
    tenant: str = "tenant-001",
    role: str = "admin",
) -> str:
    """签发 HS256 JWT（claims 与 jwt_auth.py 兼容）."""
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
    sig = base64.urlsafe_b64encode(
        hmac.new(secret.encode(), si.encode(), hashlib.sha256).digest()
    ).rstrip(b"=").decode()
    return f"{si}.{sig}"


def _build_app() -> FastAPI:
    application = FastAPI()
    router = create_router(
        deployment_manager=DeploymentManager(mock_mode=True),
        health_checker=HealthChecker(mock_mode=True, timeout=5),
        model_registry=SimpleModelRegistry(),
    )
    application.include_router(router)
    return application


@pytest.fixture
def jwt_client(monkeypatch) -> TestClient:
    monkeypatch.setenv("AUTH_MODE", "jwt")
    monkeypatch.setenv("JWT_SECRET", SECRET)
    return TestClient(_build_app())


@pytest.fixture
def anon_client(monkeypatch) -> TestClient:
    monkeypatch.setenv("AUTH_MODE", "none")
    return TestClient(_build_app())


class TestAuthEnforcement:
    """AUTH_MODE=jwt 下鉴权强制."""

    def test_missing_token_returns_401(self, jwt_client):
        resp = jwt_client.get("/api/v1/registry/models")
        assert resp.status_code == 401

    def test_invalid_token_returns_401(self, jwt_client):
        resp = jwt_client.get(
            "/api/v1/registry/models",
            headers={"Authorization": "Bearer not-a-jwt"},
        )
        assert resp.status_code == 401

    def test_wrong_secret_token_returns_401(self, jwt_client):
        token = make_jwt(secret="wrong-secret-key-at-least-32-bytes!!")
        resp = jwt_client.get(
            "/api/v1/registry/models",
            headers={"Authorization": f"Bearer {token}"},
        )
        assert resp.status_code == 401

    def test_valid_token_passes(self, jwt_client):
        resp = jwt_client.get(
            "/api/v1/registry/models",
            headers={"Authorization": f"Bearer {make_jwt()}"},
        )
        assert resp.status_code == 200

    def test_post_models_requires_auth(self, jwt_client):
        resp = jwt_client.post(
            "/api/v1/registry/models",
            json={
                "modelName": "qwen2-7b",
                "version": "0.1.0",
                "path": "/data/models/qwen2-7b",
                "baseModel": "Qwen/Qwen2-7B",
                "framework": "peft",
                "method": "lora",
                "tenantId": "tenant-001",
                "metadata": {},
            },
        )
        assert resp.status_code == 401

    def test_anon_mode_still_works_for_local_dev(self, anon_client):
        """AUTH_MODE=none（显式）保持本地开发可用."""
        resp = anon_client.get("/api/v1/registry/models")
        assert resp.status_code == 200
