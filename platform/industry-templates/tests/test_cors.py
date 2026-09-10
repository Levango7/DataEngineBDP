"""CORS 预检与白名单测试.

覆盖：
    - 未配置 CORS_ALLOWED_ORIGINS 时拒绝跨域（不回显 Origin）
    - 配置白名单后预检返回正确 CORS 头
    - 未授权 Origin 不回显
    - allow-methods 包含 GET/POST/PUT/DELETE/PATCH/OPTIONS

注意：CORS 中间件在 create_app 时读取环境变量，故测试须在设置
环境变量后构建 app（不复用 conftest 的 client fixture）。
"""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from industry_templates.api.app import create_app
from industry_templates.config.settings import Settings, reset_settings
from industry_templates.services.registry import build_services


@pytest.fixture
def make_client():
    """返回构建 client 的工厂（每次重新读环境变量）。"""

    def _build() -> TestClient:
        reset_settings()
        settings = Settings(deployMode="mock")
        registry = build_services(settings=settings)
        return TestClient(create_app(settings=settings, registry=registry))

    return _build


# ============================================================
# 未配置 CORS_ALLOWED_ORIGINS → 拒绝跨域
# ============================================================
def test_preflight_denied_when_unconfigured(make_client, monkeypatch):
    monkeypatch.delenv("CORS_ALLOWED_ORIGINS", raising=False)
    client = make_client()
    resp = client.options(
        "/api/v1/templates",
        headers={"Origin": "http://evil.example.com", "Access-Control-Request-Method": "GET"},
    )
    # 未配置白名单：不应回显 access-control-allow-origin
    assert "access-control-allow-origin" not in resp.headers


def test_get_no_allow_origin_for_unauthorized_origin(make_client, monkeypatch):
    monkeypatch.delenv("CORS_ALLOWED_ORIGINS", raising=False)
    client = make_client()
    resp = client.get("/api/v1/templates", headers={"Origin": "http://random-site.org"})
    assert "access-control-allow-origin" not in resp.headers


# ============================================================
# 配置白名单 → 预检通过
# ============================================================
def test_preflight_allowed_origin(make_client, monkeypatch):
    monkeypatch.setenv("CORS_ALLOWED_ORIGINS", "https://front.example.com")
    client = make_client()
    resp = client.options(
        "/api/v1/templates",
        headers={"Origin": "https://front.example.com", "Access-Control-Request-Method": "GET"},
    )
    assert resp.status_code == 200
    assert resp.headers["access-control-allow-origin"] == "https://front.example.com"
    assert resp.headers["access-control-allow-credentials"] == "true"


def test_preflight_unknown_origin_not_echoed(make_client, monkeypatch):
    monkeypatch.setenv("CORS_ALLOWED_ORIGINS", "https://front.example.com")
    client = make_client()
    resp = client.options(
        "/api/v1/templates",
        headers={"Origin": "http://evil.example.com", "Access-Control-Request-Method": "GET"},
    )
    assert "access-control-allow-origin" not in resp.headers


def test_cors_allow_methods_contains_write_verbs(make_client, monkeypatch):
    monkeypatch.setenv("CORS_ALLOWED_ORIGINS", "https://front.example.com")
    client = make_client()
    resp = client.options(
        "/api/v1/templates",
        headers={"Origin": "https://front.example.com", "Access-Control-Request-Method": "POST"},
    )
    assert resp.status_code == 200
    methods = resp.headers.get("access-control-allow-methods", "")
    for verb in ("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"):
        assert verb in methods, f"allow-methods 缺少 {verb}: {methods}"


def test_cors_allow_headers_includes_auth_headers(make_client, monkeypatch):
    monkeypatch.setenv("CORS_ALLOWED_ORIGINS", "https://front.example.com")
    client = make_client()
    resp = client.options(
        "/api/v1/templates",
        headers={
            "Origin": "https://front.example.com",
            "Access-Control-Request-Method": "GET",
            "Access-Control-Request-Headers": "Authorization,X-Tenant-Id",
        },
    )
    assert resp.status_code == 200
    allowed_headers = resp.headers.get("access-control-allow-headers", "")
    for h in ("authorization", "content-type", "x-tenant-id", "x-request-id"):
        assert h in allowed_headers.lower(), f"allow-headers 缺少 {h}: {allowed_headers}"


def test_cors_max_age_3600(make_client, monkeypatch):
    monkeypatch.setenv("CORS_ALLOWED_ORIGINS", "https://front.example.com")
    client = make_client()
    resp = client.options(
        "/api/v1/templates",
        headers={"Origin": "https://front.example.com", "Access-Control-Request-Method": "GET"},
    )
    assert resp.status_code == 200
    assert resp.headers.get("access-control-max-age") == "3600"


def test_cors_multiple_origins(make_client, monkeypatch):
    monkeypatch.setenv("CORS_ALLOWED_ORIGINS", "https://a.example.com,https://b.example.com")
    client = make_client()
    for origin in ("https://a.example.com", "https://b.example.com"):
        resp = client.options(
            "/api/v1/templates",
            headers={"Origin": origin, "Access-Control-Request-Method": "GET"},
        )
        assert resp.status_code == 200
        assert resp.headers["access-control-allow-origin"] == origin