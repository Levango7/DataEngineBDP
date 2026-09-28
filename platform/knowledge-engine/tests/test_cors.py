"""CORS 中间件安全配置测试.

验证 knowledge-engine 服务的 CORS 中间件：
  - 从环境变量 CORS_ALLOWED_ORIGINS 读取逗号分隔白名单
  - 未配置时默认拒绝跨域（allow_origins=[]，不用通配符 "*"）
  - 白名单内来源获得正确 CORS 头
  - 白名单外来源被拒绝
"""

from __future__ import annotations

from fastapi.testclient import TestClient

from knowledge_engine.api.app import create_app


class TestCorsMiddleware:
    """CORS 中间件安全配置测试."""

    def test_preflight_allowed_origin_returns_cors_headers(self, monkeypatch, registry):
        """预检请求（OPTIONS）对白名单内来源返回正确 CORS 头."""
        monkeypatch.setenv("CORS_ALLOWED_ORIGINS", "https://app.example.com")
        app = create_app(settings=registry.settings, registry=registry)
        client = TestClient(app)
        resp = client.options(
            "/health",
            headers={
                "Origin": "https://app.example.com",
                "Access-Control-Request-Method": "GET",
            },
        )
        assert resp.status_code == 200
        assert resp.headers["access-control-allow-origin"] == "https://app.example.com"
        assert "GET" in resp.headers["access-control-allow-methods"]
        assert resp.headers["access-control-allow-credentials"] == "true"

    def test_simple_request_allowed_origin(self, monkeypatch, registry):
        """简单请求对白名单内来源返回 Access-Control-Allow-Origin."""
        monkeypatch.setenv("CORS_ALLOWED_ORIGINS", "https://app.example.com")
        app = create_app(settings=registry.settings, registry=registry)
        client = TestClient(app)
        resp = client.get("/health", headers={"Origin": "https://app.example.com"})
        assert resp.headers.get("access-control-allow-origin") == "https://app.example.com"

    def test_preflight_disallowed_origin_no_acao(self, monkeypatch, registry):
        """预检请求对不在白名单的来源不返回 Access-Control-Allow-Origin."""
        monkeypatch.setenv("CORS_ALLOWED_ORIGINS", "https://app.example.com")
        app = create_app(settings=registry.settings, registry=registry)
        client = TestClient(app)
        resp = client.options(
            "/health",
            headers={
                "Origin": "https://evil.example.com",
                "Access-Control-Request-Method": "GET",
            },
        )
        assert "access-control-allow-origin" not in resp.headers

    def test_no_origins_configured_rejects_cross_origin(self, monkeypatch, registry):
        """未配置 CORS_ALLOWED_ORIGINS 时拒绝所有跨域请求."""
        monkeypatch.delenv("CORS_ALLOWED_ORIGINS", raising=False)
        app = create_app(settings=registry.settings, registry=registry)
        client = TestClient(app)
        resp = client.options(
            "/health",
            headers={
                "Origin": "https://evil.example.com",
                "Access-Control-Request-Method": "GET",
            },
        )
        assert "access-control-allow-origin" not in resp.headers

    def test_cors_allowed_methods_and_headers(self, monkeypatch, registry):
        """预检响应包含配置的允许方法和请求头."""
        monkeypatch.setenv("CORS_ALLOWED_ORIGINS", "https://app.example.com")
        app = create_app(settings=registry.settings, registry=registry)
        client = TestClient(app)
        resp = client.options(
            "/health",
            headers={
                "Origin": "https://app.example.com",
                "Access-Control-Request-Method": "POST",
                "Access-Control-Request-Headers": "Authorization,Content-Type",
            },
        )
        assert resp.status_code == 200
        methods = resp.headers["access-control-allow-methods"]
        for m in ["GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"]:
            assert m in methods
        allowed_headers = resp.headers["access-control-allow-headers"].lower()
        for h in ["authorization", "content-type", "x-tenant-id", "x-request-id"]:
            assert h in allowed_headers
