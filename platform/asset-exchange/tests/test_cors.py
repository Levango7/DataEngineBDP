"""CORS 预检与白名单测试."""

from __future__ import annotations

from fastapi.testclient import TestClient

from asset_exchange.api.app import create_app


def _preflight(client: TestClient, origin: str):
    return client.options(
        "/api/v1/assets",
        headers={
            "Origin": origin,
            "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "authorization, content-type",
        },
    )


class TestCorsPreflight:
    def test_preflight_default_origin_allowed(self, client) -> None:
        resp = _preflight(client, "http://localhost:5173")
        assert resp.status_code == 200
        assert resp.headers["access-control-allow-origin"] == "http://localhost:5173"
        assert resp.headers["access-control-allow-credentials"] == "true"

    def test_preflight_unknown_origin_not_echoed(self, client) -> None:
        resp = _preflight(client, "http://evil.example.com")
        assert "access-control-allow-origin" not in resp.headers

    def test_preflight_env_whitelist(self, registry, monkeypatch) -> None:
        monkeypatch.setenv("CORS_ORIGINS", "https://front.example.com")
        c = TestClient(create_app(settings=registry.settings, registry=registry))
        resp = _preflight(c, "https://front.example.com")
        assert resp.status_code == 200
        assert resp.headers["access-control-allow-origin"] == "https://front.example.com"

    def test_simple_get_allowed_origin_echoed(self, client) -> None:
        resp = client.get("/api/v1/health", headers={"Origin": "http://localhost:5173"})
        assert resp.status_code == 200
        assert resp.headers["access-control-allow-origin"] == "http://localhost:5173"

    def test_simple_get_unknown_origin_no_header(self, client) -> None:
        resp = client.get("/api/v1/health", headers={"Origin": "http://evil.example.com"})
        assert "access-control-allow-origin" not in resp.headers
